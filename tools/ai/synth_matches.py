"""Random but coherent match records for review questions (kind "review").

Events use only the wording MatchRecorder.java writes (see EVENT_* there), so the student learns to
review what the client really records. Stats are derived from the generated events, so they agree.
"""
import random

from synth_situations import BW_COLORS, ARMORS, SWORDS

REVIEW_QUESTIONS = [
    "复盘这局。", "复盘这局。", "复盘这局。", "复盘这局。",
    "我这局最大的失误是什么？",
    "下次怎么打才能赢？",
    "我的打架哪里有问题？",
    "这局我哪里打得好，哪里该改？",
]


def _t(seconds):
    return "%02d:%02d" % (seconds // 60, seconds % 60)


def _gear_line(armor, sword):
    return "%s甲 %s" % (armor, sword)


def match():
    bedwars = random.random() < 0.6
    mode = "起床" if bedwars else "空岛"
    if bedwars:
        scale, size = random.choice([("单人", 1), ("双人", 2), ("3v3", 3), ("4v4", 4)])
        colors = BW_COLORS[:8 if size <= 2 else 4]
        my_team = random.choice(colors)
        length = random.randint(6 * 60, 32 * 60)
    else:
        scale, size = random.choice([("单人", 1), ("双人", 2)])
        my_team = None
        length = random.randint(2 * 60, 12 * 60)

    events, stats = [], {"kills": 0, "final_kills": 0, "deaths": 0, "dmg_dealt": 0, "dmg_taken": 0,
                         "gapples": 0, "pearls": 0, "void_falls": 0}
    if bedwars:
        stats["beds_broken"] = 0
    hits = swings = 0
    enemies = {}  # E-number by first appearance

    def enemy(gear_rank):
        n = len(enemies) + 1 if random.random() < 0.6 or not enemies else random.randint(1, len(enemies))
        name = "E%d" % n
        if name not in enemies:
            armor = ARMORS[min(3, max(0, gear_rank + random.randint(-1, 1)))]
            sword = SWORDS[min(3, max(0, gear_rank + random.randint(-1, 1)))]
            enemies[name] = _gear_line(armor, sword)
        return name

    t = random.randint(0, 3)
    events.append({"t": _t(t), "e": "开局"})
    my_bed = bedwars
    alive = True
    result = None
    gear_rank = 0
    armor_shown = "皮" if bedwars else random.choice(ARMORS[:3])
    hp = 20
    while t < length and alive:
        t += random.randint(40, 200) if bedwars else random.randint(25, 110)
        if t >= length:
            break
        rank_now = min(3, t // (6 * 60 if bedwars else 3 * 60))
        if rank_now > gear_rank and random.random() < 0.7:
            gear_rank = rank_now
            new_armor = ARMORS[min(3, gear_rank + (1 if not bedwars else 0))]
            if new_armor != armor_shown:
                armor_shown = new_armor
                events.append({"t": _t(t), "e": "护甲升级 " + new_armor})
                continue
        r = random.random()
        if bedwars and my_bed and r < 0.10:
            my_bed = False
            events.append({"t": _t(t), "e": "我方床被 %s 拆了" % enemy(gear_rank)})
            continue
        if bedwars and r < 0.18:
            stats["beds_broken"] += 1
            events.append({"t": _t(t), "e": "拆了 %s 队的床" % random.choice([c for c in colors if c != my_team])})
            continue
        # a fight
        crowd = random.choices([1, 2, 3], weights=[70, 22, 8])[0]
        foe = enemy(gear_rank)
        if crowd > 1:
            events.append({"t": _t(t), "e": "被 %d 人围攻" % crowd})
        events.append({"t": _t(t), "e": "交战 %s（%s），我血%d" % (foe, enemies[foe], hp)})
        s = random.randint(4, 18)
        h = int(s * random.uniform(0.35, 0.8))
        swings += s
        hits += h
        dealt = int(h * random.uniform(1.5, 3.5))
        taken = random.randint(3, 26) * crowd
        stats["dmg_dealt"] += dealt
        stats["dmg_taken"] += taken
        hp = max(0, hp - taken)
        if 0 < hp <= 6:
            events.append({"t": _t(t + 4), "e": "残血 %d" % hp})
            if random.random() < 0.6:
                stats["gapples"] += 1
                events.append({"t": _t(t + 6), "e": "吃金苹果（血%d）" % hp})
                hp = min(20, hp + 8)
            elif random.random() < 0.3:
                stats["pearls"] += 1
                events.append({"t": _t(t + 5), "e": "扔珍珠"})
        win_chance = 0.55 / crowd + (0.1 if gear_rank >= 2 else 0)
        if hp > 0 and random.random() < win_chance:
            final = bedwars and random.random() < 0.3
            stats["kills"] += 1
            stats["final_kills"] += final
            events.append({"t": _t(t + 8), "e": ("最终击杀 " if final else "击杀 ") + foe})
            hp = max(hp, random.randint(6, 16))
        elif hp == 0 or random.random() < 0.3:
            stats["deaths"] += 1
            if random.random() < 0.25:
                stats["void_falls"] += 1
                events.append({"t": _t(t + 7), "e": "掉虚空死亡（被 %s 击退）" % foe})
            else:
                events.append({"t": _t(t + 7), "e": "被 %s 击杀（%s），死前血量%d" % (foe, enemies[foe], max(1, hp))})
            if not bedwars or not my_bed:
                alive = False
                result = "失败"
            else:
                events.append({"t": _t(t + 12), "e": "复活"})
                hp = 20
                t += 12
        else:
            events.append({"t": _t(t + 10), "e": "脱离交战，血%d" % max(1, hp)})
            hp = min(20, hp + random.randint(2, 6))
    if result is None:
        result = random.choice(["胜利", "胜利", "失败", "未知"]) if alive else "失败"
    # events inside a fight carry small offsets: sort by time, the result line goes last
    events.sort(key=lambda ev: ev["t"])
    events.append({"t": events[-1]["t"], "e": result})
    duration = events[-1]["t"]
    if len(events) > 34:  # keep the prompt short: keep the opening and the last part of the game
        cut = len(events) - 30
        while cut < len(events) and not events[cut]["e"].startswith(("交战", "被 ", "护甲", "我方床", "拆了")):
            cut += 1
        events = events[:2] + [{"t": events[cut]["t"], "e": "（中间省略）"}] + events[cut:]
    stats["hits"] = hits
    stats["swings"] = swings
    for key in [k for k, v in stats.items() if v == 0 and k not in ("kills", "deaths")]:
        del stats[key]
    r = {"kind": "review", "mode": mode, "scale": scale, "result": result, "duration": duration,
         "stats": stats, "events": events, "question": random.choice(REVIEW_QUESTIONS)}
    if my_team:
        r["my_team"] = my_team
    return r
