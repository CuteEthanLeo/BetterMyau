"""Write one teacher batch.

    python make_batch.py 7 --count 100 --mix target:50,ask:30,qa:20
    python make_batch.py --golden 40        # golden/situations.jsonl for PromptFormatCheck

Outputs (ids look like b007-012):
  tasks/batch_007.jsonl       {"id", "kind", "prompt"}       <- what Codex reads
  data/situations/batch_007.jsonl {"id", "situation"}        <- kept for training / checks
The batch number seeds the generator, so re-running a batch reproduces it exactly.
"""
import argparse
import json
import os
import random

import render
import synth_matches
import synth_situations as synth

HERE = os.path.dirname(os.path.abspath(__file__))


def _write(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def _parse_mix(text):
    mix = []
    for part in text.split(","):
        kind, weight = part.split(":")
        mix.append((kind.strip(), float(weight)))
    return mix


def _make(kind):
    if kind == "qa":
        return synth.qa()
    if kind == "review":
        return synth_matches.match()
    return synth.situation(kind)


def batch(number, count, mix):
    random.seed(1000 + number)
    kinds = [k for k, _ in mix]
    weights = [w for _, w in mix]
    # exact proportions, then shuffled, so a 20-item pilot really gets its 12/4/4
    total = sum(weights)
    plan = []
    for kind, weight in mix:
        plan += [kind] * round(count * weight / total)
    while len(plan) < count:
        plan.append(random.choices(kinds, weights=weights)[0])
    plan = plan[:count]
    random.shuffle(plan)

    tasks, situations = [], []
    for i, kind in enumerate(plan):
        ident = "b%03d-%03d" % (number, i)
        s = _make(kind)
        tasks.append({"id": ident, "kind": kind, "prompt": render.render(s)})
        situations.append({"id": ident, "situation": s})
    _write(os.path.join(HERE, "tasks", "batch_%03d.jsonl" % number), tasks)
    _write(os.path.join(HERE, "data", "situations", "batch_%03d.jsonl" % number), situations)
    print("batch %03d: %d items (%s)" % (number, count,
          ", ".join("%s=%d" % (k, plan.count(k)) for k in kinds)))


def golden(count):
    random.seed(424242)
    rows = []
    for i in range(count):
        s = _make(["target", "ask", "qa", "review"][i % 4])
        rows.append({"situation": s, "text": render.render(s)})
    # a few hand-made edge cases: unknown health, no enemies, negative height, every flag
    edge_me = {"hp": 3, "max": 20, "abs": 0, "armor": "皮", "prot": 0, "held": "木剑", "sharp": 0,
               "gapple": 0, "pearl": 0, "blocks": 0, "effects": [], "void_edge": True}
    rows.append({"situation": {"kind": "target", "mode": "未知", "me": edge_me, "enemies": []}})
    rows.append({"situation": {"kind": "target", "mode": "空岛", "scale": "单人", "players_left": 2,
                               "me": edge_me, "enemies": [{
                                   "dist": 12.0, "hp": None, "abs": 0, "armor": "?", "prot": 0,
                                   "weapon": "弓", "sharp": 0, "hitting_me": True, "facing_me": True,
                                   "bow_drawn": True, "dy": -12, "void_edge": True,
                                   "near_my_bed": True, "invis": True}]}})
    for row in rows:
        row["text"] = render.render(row["situation"])
    _write(os.path.join(HERE, "golden", "situations.jsonl"), rows)
    print("golden: %d rows" % len(rows))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("number", type=int, nargs="?")
    ap.add_argument("--count", type=int, default=100)
    ap.add_argument("--mix", default="target:50,ask:30,qa:20")
    ap.add_argument("--golden", type=int)
    args = ap.parse_args()
    if args.golden:
        golden(args.golden)
    else:
        batch(args.number, args.count, _parse_mix(args.mix))
