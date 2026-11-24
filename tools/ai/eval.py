"""Compare a model served by Ollama with the teacher on the held-out set (data/sft/val.jsonl).

    python eval.py                       # bettermyau-pvp
    python eval.py --model bettermyau-base   # the untrained Instruct-2507 base, same template, for a before/after
                                             # (not qwen3:4b: that tag is the thinking-only model, it writes its
                                             #  reasoning into the answer even with think=false)
    python eval.py --limit 100 --show 5

Reports: format OK rate (先打 line parses and names an enemy that exists), agreement with the
teacher's target, share of 先打：无, mean answer length and latency. Writes every answer to
data/eval/<model>.jsonl so the teacher can be asked to judge them blind later.
"""
import argparse
import json
import os
import re
import time
import urllib.request

import render

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = re.compile(r"^\s*先打\s*[：:]\s*(E\d+|无)")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # local server: never via HTTP_PROXY


def chat(endpoint, model, messages):
    body = json.dumps({"model": model, "messages": messages, "stream": False, "think": False,
                       "options": {"temperature": 0.2, "num_predict": 400}}).encode("utf-8")
    req = urllib.request.Request(endpoint + "/api/chat", data=body, headers={"Content-Type": "application/json"})
    with OPENER.open(req, timeout=180) as r:
        return json.loads(r.read().decode("utf-8"))["message"]["content"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="bettermyau-pvp")
    ap.add_argument("--endpoint", default="http://127.0.0.1:11434")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--show", type=int, default=3)
    args = ap.parse_args()

    with open(os.path.join(HERE, "data", "sft", "val.jsonl"), encoding="utf-8") as f:
        rows = [json.loads(line) for line in f]
    if args.limit:
        rows = rows[:args.limit]
    out_path = os.path.join(HERE, "data", "eval", args.model.replace(":", "_") + ".jsonl")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    n_target = ok = agree = none = 0
    lengths, times, shown = [], [], 0
    with open(out_path, "w", encoding="utf-8", newline="\n") as out:
        for row in rows:
            messages = row["messages"]
            prompt, teacher = messages[1]["content"], messages[2]["content"]
            t0 = time.time()
            answer = re.sub(r"<think>.*?</think>", "", chat(args.endpoint, args.model, messages[:2]), flags=re.S).strip()
            times.append(time.time() - t0)
            lengths.append(len(answer))
            if row["kind"] == "target":
                n_target += 1
                got, want = TARGET.match(answer), TARGET.match(teacher)
                if got and (got.group(1) == "无" or got.group(1) in render.enemy_ids(prompt)):
                    ok += 1
                    none += got.group(1) == "无"
                    agree += bool(want) and got.group(1) == want.group(1)
            out.write(json.dumps({"id": row["id"], "kind": row["kind"], "prompt": prompt,
                                  "teacher": teacher, "student": answer}, ensure_ascii=False) + "\n")
            if shown < args.show:
                shown += 1
                print("=" * 50 + "\n" + prompt + "\n--- 老师:\n" + teacher + "\n--- 学生:\n" + answer)

    print("=" * 50)
    print("model %s, %d samples, mean %.1f s, mean %d chars" % (
        args.model, len(rows), sum(times) / max(1, len(times)), sum(lengths) / max(1, len(lengths))))
    if n_target:
        print("先打谁: format OK %d/%d (%.0f%%), agrees with teacher %d/%d (%.0f%%), 无 %.0f%%" % (
            ok, n_target, 100 * ok / n_target, agree, n_target, 100 * agree / n_target, 100 * none / max(1, ok)))
    print("answers saved to", out_path)


if __name__ == "__main__":
    main()
