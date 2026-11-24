"""#v1 prompt format: situation JSON -> the compact Chinese text block the model reads.

This must stay byte-for-byte identical to src/main/java/myau/ai/PromptFormat.java; golden/*.jsonl
pins both sides (python render.py --check, tools/tests/PromptFormatCheck.java).

Numbers arrive already rounded (hp/abs/dy ints, dist one decimal) so the two languages never
disagree on rounding: Java rounds when it captures a snapshot, synth_situations.py when it generates.
Flags are written only when true, to keep prompts short.
"""
import json
import sys

VERSION = "#v1"
TARGET_QUESTION = "现在先打谁？"


def _num(value):
    """Hit points etc.: ints print as ints, unknown as '?'."""
    if value is None:
        return "?"
    return str(int(value))


def _dist(value):
    return "?" if value is None else "%.1f" % value


def _gear(parts, armor, prot, item_key, item, ench_key, ench):
    parts.append("甲=" + (armor or "?"))
    if prot:
        parts.append("保护%d" % prot)
    parts.append(item_key + "=" + (item or "?"))
    if ench:
        parts.append(ench_key + "%d" % ench)


def _hp(parts, hp, maximum, absorption):
    parts.append("血" + _num(hp) + ("" if maximum is None else "/" + _num(maximum)))
    if absorption:
        parts.append("吸收" + _num(absorption))


def header(s):
    parts = [VERSION, "模式=" + s.get("mode", "未知")]
    if s.get("scale"):
        parts.append(s["scale"])
    if s.get("time"):
        parts.append("时间=" + s["time"])
    if s.get("my_team"):
        parts.append("我方=" + s["my_team"])
    if s.get("my_bed") is not None:
        parts.append("床=" + ("在" if s["my_bed"] else "无"))
    if s.get("mates_total"):
        parts.append("队友存活=%d/%d" % (s.get("mates_alive", 0), s["mates_total"]))
    if s.get("players_left") is not None:
        parts.append("剩余玩家=%d" % s["players_left"])
    return " ".join(parts)


def me_line(m):
    parts = ["我"]
    _hp(parts, m.get("hp"), m.get("max", 20), m.get("abs"))
    _gear(parts, m.get("armor"), m.get("prot"), "手持", m.get("held"), "锋利", m.get("sharp"))
    parts.append("金苹果%d" % m.get("gapple", 0))
    parts.append("珍珠%d" % m.get("pearl", 0))
    parts.append("方块%d" % m.get("blocks", 0))
    if m.get("effects"):
        parts.append("药水=" + ",".join(m["effects"]))
    if m.get("void_edge"):
        parts.append("虚空边")
    return " ".join(parts)


ENEMY_FLAGS = [
    ("hitting_me", "在打我"),
    ("facing_me", "朝向我"),
    ("bow_drawn", "拉弓"),
    ("void_edge", "虚空边"),
    ("near_my_bed", "近我方床"),
    ("invis", "隐身"),
]


def enemy_line(index, e):
    parts = ["E%d" % index]
    if e.get("team"):
        parts.append(e["team"])
    if e.get("bed") is not None:
        parts.append("床=" + ("在" if e["bed"] else "无"))
    parts.append("距" + _dist(e.get("dist")))
    _hp(parts, e.get("hp"), None, e.get("abs"))
    _gear(parts, e.get("armor"), e.get("prot"), "武器", e.get("weapon"), "锋利", e.get("sharp"))
    dy = e.get("dy", 0)
    if dy:
        parts.append("高差%+d" % dy)
    for key, label in ENEMY_FLAGS:
        if e.get(key):
            parts.append(label)
    return " ".join(parts)


def mate_line(index, t):
    parts = ["T%d" % index, "距" + _dist(t.get("dist"))]
    _hp(parts, t.get("hp"), None, t.get("abs"))
    parts.append("甲=" + (t.get("armor") or "?"))
    return " ".join(parts)


REVIEW_QUESTION = "复盘这局。"
# stat key -> label, in display order; only keys present are written
STATS = [
    ("kills", "击杀"), ("final_kills", "最终击杀"), ("deaths", "死亡"), ("beds_broken", "拆床"),
    ("dmg_dealt", "造成伤害"), ("dmg_taken", "受到伤害"), ("gapples", "金苹果"), ("pearls", "珍珠"),
    ("void_falls", "掉虚空"),
]


def render_review(r):
    """Match record -> review prompt. Events are already short Chinese lines with mm:ss times."""
    parts = [VERSION, "复盘", "模式=" + r.get("mode", "未知")]
    if r.get("scale"):
        parts.append(r["scale"])
    if r.get("my_team"):
        parts.append("我方=" + r["my_team"])
    parts.append("结果=" + r.get("result", "未知"))
    if r.get("duration"):
        parts.append("时长=" + r["duration"])
    lines = [" ".join(parts)]
    stats = r.get("stats", {})
    items = ["%s%d" % (label, stats[key]) for key, label in STATS if key in stats]
    if stats.get("swings"):
        items.append("命中%d/%d" % (stats.get("hits", 0), stats["swings"]))
    if items:
        lines.append("统计 " + " ".join(items))
    lines.append("时间线")
    for ev in r.get("events", []):
        lines.append(ev["t"] + " " + ev["e"])
    lines.append("问：" + (r.get("question") or REVIEW_QUESTION))
    return "\n".join(lines)


def render(s):
    """Situation dict -> prompt text. kind 'qa' has no situation, only the question."""
    if s.get("kind") == "qa":
        return VERSION + " 问答\n问：" + s["question"]
    if s.get("kind") == "review":
        return render_review(s)
    lines = [header(s)]
    if s.get("teams"):
        lines.append("各队 " + " ".join(t["team"] + t["state"] for t in s["teams"]))
    lines.append(me_line(s["me"]))
    for i, t in enumerate(s.get("mates", []), 1):
        lines.append(mate_line(i, t))
    enemies = s.get("enemies", [])
    if not enemies:
        lines.append("附近没有敌人")
    for i, e in enumerate(enemies, 1):
        lines.append(enemy_line(i, e))
    lines.append("问：" + (TARGET_QUESTION if s.get("kind") == "target" else s["question"]))
    return "\n".join(lines)


def enemy_ids(prompt):
    """E-numbers present in a rendered prompt (for checking a '先打：Ex' answer)."""
    ids = set()
    for line in prompt.split("\n"):
        head = line.split(" ", 1)[0]
        if len(head) > 1 and head[0] == "E" and head[1:].isdigit():
            ids.add(head)
    return ids


def _check(path):
    """golden file: one {"situation": ..., "text": ...} per line; exit 1 on any mismatch."""
    bad = 0
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            row = json.loads(line)
            got = render(row["situation"])
            if got != row["text"]:
                bad += 1
                print("mismatch on line %d:\n--- expected\n%s\n--- got\n%s" % (n, row["text"], got))
    print("golden %s: %s" % (path, "OK" if not bad else "%d mismatches" % bad))
    return bad


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--check":
        sys.exit(1 if _check(sys.argv[2]) else 0)
    for raw in sys.stdin:
        if raw.strip():
            print(render(json.loads(raw)))
            print()
