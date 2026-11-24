"""QLoRA fine-tune of the student model on the filtered teacher answers.

    tools\\ai\\.venv\\Scripts\\python tools\\ai\\train_lora.py            (close Minecraft first: 8 GB VRAM)
    ... train_lora.py --base unsloth/Qwen3-4B-Instruct-2507-unsloth-bnb-4bit --epochs 2 --max-len 2048

Reads data/sft/batch_*.jsonl (written by filter.py), holds 5% out for validation (data/sft/val.jsonl
is written so eval.py uses the same split), trains only on the assistant part, and saves the LoRA
adapter under E:\\AI\\bettermyau\\<run>\\lora. Everything heavy stays on E:.
"""
import argparse
import glob
import hashlib
import json
import os
import time

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_ROOT = r"E:\AI\bettermyau"
for key, sub in (("HF_HOME", "hf"), ("TORCH_HOME", "torch"), ("TRITON_CACHE_DIR", "triton")):
    os.environ.setdefault(key, os.path.join(r"E:\AI\cache", sub))


def load_samples():
    seen, rows = set(), []
    for path in sorted(glob.glob(os.path.join(HERE, "data", "sft", "batch_*.jsonl"))):
        with open(path, encoding="utf-8") as f:
            for line in f:
                row = json.loads(line)
                if row["id"] not in seen:
                    seen.add(row["id"])
                    rows.append(row)
    return rows


def is_val(ident, share=0.05):
    return int(hashlib.md5(ident.encode("utf-8")).hexdigest(), 16) % 1000 < share * 1000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="unsloth/Qwen3-4B-Instruct-2507-unsloth-bnb-4bit")
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--max-len", type=int, default=2048)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--run", default=time.strftime("run-%Y%m%d-%H%M"))
    ap.add_argument("--max-steps", type=int, default=-1, help="smoke test: stop after N optimizer steps, save nothing")
    args = ap.parse_args()
    smoke = args.max_steps > 0

    rows = load_samples()
    train_rows = [r for r in rows if not is_val(r["id"])]
    val_rows = [r for r in rows if is_val(r["id"])]
    with open(os.path.join(HERE, "data", "sft", "val.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for r in val_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("samples: %d train, %d val" % (len(train_rows), len(val_rows)))

    # imported late: unsloth must come before transformers / trl
    from unsloth import FastLanguageModel
    from unsloth.chat_templates import train_on_responses_only
    from datasets import Dataset
    from trl import SFTConfig, SFTTrainer

    model, tokenizer = FastLanguageModel.from_pretrained(args.base, max_seq_length=args.max_len, load_in_4bit=True)
    model = FastLanguageModel.get_peft_model(
        model, r=args.rank, lora_alpha=args.rank * 2, lora_dropout=0.0, bias="none",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        use_gradient_checkpointing="unsloth", random_state=3407)

    def texts(rows_):
        # only a "text" column: with "messages" present TRL would treat the set as conversational
        return Dataset.from_list([{"text": tokenizer.apply_chat_template(r["messages"], tokenize=False)} for r in rows_])

    train_ds, val_ds = texts(train_rows), texts(val_rows)
    longest = max(len(tokenizer(t)["input_ids"]) for t in train_ds["text"])
    print("longest sample: %d tokens (max-len %d)" % (longest, args.max_len))
    out_dir = os.path.join(OUT_ROOT, args.run)
    trainer = SFTTrainer(
        model=model, processing_class=tokenizer, train_dataset=train_ds, eval_dataset=val_ds,
        args=SFTConfig(
            output_dir=os.path.join(out_dir, "checkpoints"),
            dataset_text_field="text", max_length=args.max_len, packing=False,
            per_device_train_batch_size=1, gradient_accumulation_steps=8,
            num_train_epochs=args.epochs, max_steps=args.max_steps, learning_rate=args.lr,
            lr_scheduler_type="cosine", warmup_steps=10, weight_decay=0.01, optim="adamw_8bit",
            logging_steps=1 if smoke else 10,
            eval_strategy="no" if smoke else "epoch", save_strategy="no" if smoke else "epoch",
            save_total_limit=1, report_to="none", seed=3407))
    trainer = train_on_responses_only(trainer, instruction_part="<|im_start|>user\n",
                                      response_part="<|im_start|>assistant\n")
    import torch
    torch.cuda.reset_peak_memory_stats()
    trainer.train()
    print("peak VRAM: %.2f GB" % (torch.cuda.max_memory_allocated() / 1024 ** 3))
    if smoke:
        print("smoke test OK, nothing saved")
        return
    print("final eval:", trainer.evaluate())
    lora_dir = os.path.join(out_dir, "lora")
    model.save_pretrained(lora_dir)
    tokenizer.save_pretrained(lora_dir)
    with open(os.path.join(out_dir, "run.json"), "w", encoding="utf-8") as f:
        json.dump({"base": args.base, "train": len(train_rows), "val": len(val_rows), "epochs": args.epochs,
                   "rank": args.rank, "lr": args.lr, "max_len": args.max_len}, f, ensure_ascii=False, indent=2)
    print("saved adapter to", lora_dir)


if __name__ == "__main__":
    main()
