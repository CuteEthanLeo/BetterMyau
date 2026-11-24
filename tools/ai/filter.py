"""Check one teacher batch and turn the good answers into SFT samples.

    python filter.py 0            # tasks/batch_000.jsonl + teacher/batch_000.jsonl
    python filter.py 0 --show     # also print every prompt/answer pair

Writes data/sft/batch_000.jsonl ({"id", "kind", "messages": [system, user, assistant]}) and prints a
report. Exit code 2 when the batch as a whole looks templated (the teacher generated answers by
script instead of judging each one): throw it away and run it again.
"""
import argparse
import collections
import json
import os
import re
import sys

import render

HERE = os.path.dirname(os.path.abspath(__file__))
# Must match the SYSTEM line of the Ollama Modelfile and LocalLLM.java.
SYSTEM_PROMPT = "你是 BetterMyau 的 PvP 军师。"

# kind -> (soft limit, hard limit) in characters, not counting the 先打 line
LIMITS = {"target": (120, 180), "ask": (200, 280), "qa": (300, 400), "review": (400, 520)}
TARGET_LINE = re.compile(r"^\s*先打\s*[：:]\s*(E\d+|无)\s*$")
TOOL_TALK = re.compile(r"(TEACHER|jsonl|\.md\b|batch_|脚本|运行命令|我将|我会先|工作区|workspace|```|^#|\|---)",
                       re.MULTILINE)
SENTENCE_SPLIT = re.compile(r"[。！？!?\n]")
TEMPLATE_SHARE = 0.15  # more than this share of answers sharing sentences -> templated batch


def _read(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def check_one(task, reply):
    """List of problems (empty = fine) and a list of soft warnings."""
    problems, warnings = [], []
    if not isinstance(reply, str) or not reply.strip():
        return ["empty reply"], warnings
    kind = task["kind"]
    body = reply
    if kind == "target":
        first, _, body = reply.partition("\n")
        m = TARGET_LINE.match(first)
        if not m:
            problems.append("first line is not 先打：Ex")
        elif m.group(1) != "无" and m.group(1) not in render.enemy_ids(task["prompt"]):
            problems.append("target %s not in prompt" % m.group(1))
    soft, hard = LIMITS[kind]
    length = len(body.strip())
    if length > hard:
        problems.append("too long (%d > %d)" % (length, hard))
    elif length > soft:
        warnings.append("long (%d > %d)" % (length, soft))
    if TOOL_TALK.search(reply):
        problems.append("tool talk / markdown: %r" % TOOL_TALK.search(reply).group(0))
    return problems, warnings


def templated_ids(rows):
    """Ids of answers that share a sentence (8+ chars) with 2+ other answers."""
    owners = collections.defaultdict(set)
    for ident, reply in rows:
        for sentence in SENTENCE_SPLIT.split(reply):
            sentence = sentence.strip()
            if len(sentence) >= 8 and not sentence.startswith("先打"):
                owners[sentence].add(ident)
    flagged = set()
    for ids in owners.values():
        if len(ids) >= 3:
            flagged |= ids
    return flagged


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("number", type=int)
    ap.add_argument("--show", action="store_true")
    args = ap.parse_args()
    name = "batch_%03d.jsonl" % args.number
    tasks = _read(os.path.join(HERE, "tasks", name))
    answers = _read(os.path.join(HERE, "teacher", name))

    by_id = {}
    for a in answers:
        by_id[a.get("id")] = a.get("reply")
    missing = [t["id"] for t in tasks if t["id"] not in by_id]
    extra = [i for i in by_id if i not in {t["id"] for t in tasks}]

    kept, dropped, warned = [], [], 0
    for t in tasks:
        if t["id"] not in by_id:
            continue
        reply = by_id[t["id"]]
        problems, warnings = check_one(t, reply)
        warned += bool(warnings)
        if args.show:
            print("=" * 60)
            print("[%s] %s%s" % (t["id"], t["kind"], ("  !! " + "; ".join(problems + warnings)) if problems or warnings else ""))
            print(t["prompt"])
            print("--- 老师:")
            print(reply)
        if problems:
            dropped.append((t["id"], problems))
        else:
            kept.append(t)

    flagged = templated_ids([(t["id"], by_id[t["id"]]) for t in kept])
    share = len(flagged) / max(1, len(kept))

    out_dir = os.path.join(HERE, "data", "sft")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, name), "w", encoding="utf-8", newline="\n") as f:
        for t in kept:
            f.write(json.dumps({"id": t["id"], "kind": t["kind"], "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": t["prompt"]},
                {"role": "assistant", "content": by_id[t["id"]].strip()},
            ]}, ensure_ascii=False) + "\n")

    print("=" * 60)
    print("%s: %d tasks, %d answers, kept %d, dropped %d, long %d, missing %d, extra %d"
          % (name, len(tasks), len(answers), len(kept), len(dropped), warned, len(missing), len(extra)))
    for ident, problems in dropped:
        print("  drop %s: %s" % (ident, "; ".join(problems)))
    if missing:
        print("  missing: " + ", ".join(missing))
    print("  shared-sentence answers: %d (%.0f%%)" % (len(flagged), share * 100))
    if share > TEMPLATE_SHARE:
        print("  !! looks templated, redo this batch")
        sys.exit(2)


if __name__ == "__main__":
    main()
