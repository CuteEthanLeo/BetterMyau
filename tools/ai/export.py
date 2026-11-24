"""Merge the LoRA adapter into the fp16 base, quantize to GGUF and register it with Ollama.

    tools\\ai\\.venv\\Scripts\\python tools\\ai\\export.py E:\\AI\\bettermyau\\run-v1
    ... export.py <run dir> [--name bettermyau-pvp] [--quant Q4_K_M] [--skip-merge] [--skip-convert]
    ... export.py E:\\AI\\bettermyau\\base --base-only --name bettermyau-base     (untrained baseline)

Steps (merge/convert can be skipped when their output already exists):
  1. merge     <run>\\merged            fp16 base + LoRA, tensor by tensor on the CPU, written in ~2 GB
                                         shards: peaks at ~3 GB RAM (16 GB machine, about half in use).
  2. convert   <run>\\model-f16.gguf    llama.cpp convert_hf_to_gguf.py (clone at E:\\AI\\llama.cpp)
  3. quantize  <run>\\model-q4_k_m.gguf llama-quantize, built locally: E:\\AI\\llama.cpp\\build-quantize.bat
  4. template  the GGUF's chat template is replaced by tools\\ai\\chat_template.jinja
  5. ollama    `ollama create <name> -f <run>\\Modelfile`, stored under OLLAMA_MODELS (E:)
  6. check     the template Ollama reports must be chat_template.jinja

Pitfalls this script avoids (all hit on 2026-11-21/22, Ollama 0.40):
  * The fp16 base is NOT downloaded here: through the proxy it stalled for 70 min. Fetch it beforehand from
    hf-mirror without the proxy (sha256-checked) into E:\\AI\\models\\Qwen3-4B-Instruct-2507 (--base default).
  * `ollama create --quantize` refuses GGUF input, and for safetensors it only offers int4/int8/nvfp4/mx*
    on a new engine; so the GGUF is quantized here with llama.cpp and imported as is.
  * Ollama 0.40 renders with the GGUF's own chat template and ignores the Modelfile TEMPLATE. The Qwen3
    template's generation prompt lacks the empty think block the training text has ("<|im_start|>assistant\\n
    <think>\\n\\n</think>\\n\\n" + answer), the model then has to write those tokens itself and, quantized, picks
    </tool_call> instead of <think> (~8% top-1). chat_template.jinja puts the block in the prompt; it renders all
    2020 training rows byte for byte. The Modelfile TEMPLATE says the same in Go syntax, in case a later
    Ollama prefers it again.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys

for key, sub in (("HF_HOME", "hf"), ("TORCH_HOME", "torch"), ("TMP", "tmp"), ("TEMP", "tmp")):
    os.environ.setdefault(key, os.path.join(r"E:\AI\cache", sub))
os.makedirs(os.environ["TMP"], exist_ok=True)

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_BASE = r"E:\AI\models\Qwen3-4B-Instruct-2507"
LLAMA_CPP = r"E:\AI\llama.cpp"
QUANTIZE = os.path.join(LLAMA_CPP, "build", "bin", "llama-quantize.exe")
CHAT_TEMPLATE = os.path.join(HERE, "chat_template.jinja")
SYSTEM_PROMPT = "你是 BetterMyau 的 PvP 军师。"  # == filter.py / LocalLLM.java
# Go template equivalent of chat_template.jinja ("\n" written out so the prefix is unambiguous)
GO_TEMPLATE = ("{{- range .Messages }}<|im_start|>{{ .Role }}\n{{ .Content }}<|im_end|>\n{{ end }}"
               "<|im_start|>assistant\n<think>\n\n</think>\n\n")
SHARD_BYTES = 2 * 1024 ** 3


def merge(base, lora_dir, out):
    from safetensors import safe_open
    from safetensors.torch import save_file

    with open(os.path.join(lora_dir, "adapter_config.json"), encoding="utf-8") as f:
        cfg = json.load(f)
    if cfg.get("use_dora") or cfg.get("modules_to_save"):
        sys.exit("adapter uses DoRA / modules_to_save: this merge does not handle that")
    r, alpha = cfg["r"], cfg["lora_alpha"]
    scale = alpha / (r ** 0.5) if cfg.get("use_rslora") else alpha / r

    adapter = safe_open(os.path.join(lora_dir, "adapter_model.safetensors"), "pt")
    prefix = "base_model.model."
    pending = {k[len(prefix):-len(".lora_A.weight")] for k in adapter.keys() if k.endswith(".lora_A.weight")}
    print("adapter: %d modules, r=%d alpha=%d scale=%.3f" % (len(pending), r, alpha, scale))

    with open(os.path.join(base, "model.safetensors.index.json"), encoding="utf-8") as f:
        weight_map = json.load(f)["weight_map"]
    by_file = {}
    for name, fname in weight_map.items():
        by_file.setdefault(fname, []).append(name)

    os.makedirs(out, exist_ok=True)
    shards, current, size = [], {}, 0

    def flush():
        nonlocal current, size
        if current:
            fname = "model-%05d.safetensors" % (len(shards) + 1)
            save_file(current, os.path.join(out, fname), metadata={"format": "pt"})
            shards.append((fname, list(current)))
            print("  wrote %s (%.2f GB)" % (fname, size / 1024 ** 3))
            current, size = {}, 0

    for fname in sorted(by_file):
        with safe_open(os.path.join(base, fname), "pt") as src:
            for name in sorted(by_file[fname]):
                w = src.get_tensor(name)
                module = name[:-len(".weight")] if name.endswith(".weight") else None
                if module in pending:
                    a = adapter.get_tensor(prefix + module + ".lora_A.weight").float()
                    b = adapter.get_tensor(prefix + module + ".lora_B.weight").float()
                    w = (w.float() + scale * (b @ a)).to(w.dtype)
                    pending.discard(module)
                current[name] = w.contiguous()
                size += w.numel() * w.element_size()
                if size >= SHARD_BYTES:
                    flush()
    flush()
    if pending:
        sys.exit("LoRA modules not found in the base (wrong base?): %s" % sorted(pending)[:5])

    total = sum(os.path.getsize(os.path.join(out, f)) for f, _ in shards)
    index = {"metadata": {"total_size": total}, "weight_map": {n: f for f, names in shards for n in names}}
    with open(os.path.join(out, "model.safetensors.index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, indent=2)
    # config + the base's own tokenizer files (the adapter added no tokens)
    for f in os.listdir(base):
        if not f.endswith(".safetensors") and f != "model.safetensors.index.json" and os.path.isfile(os.path.join(base, f)):
            shutil.copy2(os.path.join(base, f), os.path.join(out, f))
    print("merged model in", out)


def set_chat_template(gguf):
    tmp = gguf + ".tmp"
    subprocess.run([sys.executable, os.path.join(LLAMA_CPP, "gguf-py", "gguf", "scripts", "gguf_new_metadata.py"),
                    gguf, tmp, "--chat-template-file", CHAT_TEMPLATE, "--force"], check=True)
    os.replace(tmp, gguf)


def check_template(name):
    want = open(CHAT_TEMPLATE, encoding="utf-8").read().strip()
    got = subprocess.run(["ollama", "show", name, "--template"], capture_output=True, encoding="utf-8").stdout.strip()
    if got not in (want, GO_TEMPLATE.strip()):
        sys.exit("!! Ollama renders %s with a template that is not chat_template.jinja:\n%s" % (name, got[:400]))
    print("template check OK")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--name", default="bettermyau-pvp")
    ap.add_argument("--quant", default="Q4_K_M")
    ap.add_argument("--skip-merge", action="store_true")
    ap.add_argument("--skip-convert", action="store_true")
    ap.add_argument("--base-only", action="store_true",
                    help="export the untrained base with the same template (eval baseline): <run> is just an output dir")
    args = ap.parse_args()
    if not os.path.isfile(QUANTIZE):
        sys.exit("llama-quantize missing: run E:\\AI\\llama.cpp\\build-quantize.bat first")
    merged = args.base if args.base_only else os.path.join(args.run, "merged")
    f16 = os.path.join(args.run, "model-f16.gguf")
    quant = os.path.join(args.run, "model-%s.gguf" % args.quant.lower())
    os.makedirs(args.run, exist_ok=True)

    if not args.skip_merge and not args.base_only:
        if not os.path.isfile(os.path.join(args.base, "model.safetensors.index.json")):
            sys.exit("fp16 base not found in %s (download it first, see the docstring)" % args.base)
        merge(args.base, os.path.join(args.run, "lora"), merged)

    if not args.skip_convert:
        subprocess.run([sys.executable, os.path.join(LLAMA_CPP, "convert_hf_to_gguf.py"), merged,
                        "--outtype", "f16", "--outfile", f16], check=True)
    subprocess.run([QUANTIZE, f16, quant, args.quant.upper()], check=True)
    set_chat_template(quant)

    modelfile = os.path.join(args.run, "Modelfile")
    with open(modelfile, "w", encoding="utf-8", newline="\n") as f:
        f.write("FROM %s\n" % quant)
        f.write('TEMPLATE """%s"""\n' % GO_TEMPLATE)
        f.write('SYSTEM """%s"""\n' % SYSTEM_PROMPT)
        for line in ('stop "<|im_end|>"', 'stop "<|im_start|>"', "temperature 0.4", "num_ctx 4096"):
            f.write("PARAMETER %s\n" % line)
    subprocess.run(["ollama", "create", args.name, "-f", modelfile], check=True)
    check_template(args.name)
    print("done: in game, .ai model %s" % args.name)
    print("the f16 GGUF (%s) and the merged folder are only needed to re-quantize; delete them to free ~16 GB" % f16)


if __name__ == "__main__":
    main()
