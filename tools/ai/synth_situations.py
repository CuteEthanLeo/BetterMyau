"""Random but plausible Bed Wars / SkyWars situations for the teacher to judge.

Only fields the client can really capture are generated (see PromptFormat.java), so the student
never learns to rely on something it will not see in game. Roughly half the situations start from
a "hard" template (a dilemma between two reasonable targets), because easy ones teach little.
"""
import random

BW_COLORS = ["红", "蓝", "绿", "黄", "青", "白", "粉", "灰"]
BW_SCALES = [("单人", 8, 1), ("双人", 8, 2), ("3v3", 4, 3), ("4v4", 4, 4)]
SW_SCALES = [("单人", 1), ("双人", 2)]

SWORDS = ["木剑", "石剑", "铁剑", "钻石剑"]
ARMORS = ["皮", "锁链", "铁", "钻"]

# Bed Wars gear by phase (minutes): (armor weights, sword weights, max prot, max sharp)
BW_PHASES = [
    (0, [70, 25, 5, 0], [60, 40, 0, 0], 0, 0),
    (6, [25, 30, 40, 5], [10, 55, 30, 5], 1, 0),
    (15, [5, 10, 50, 35], [0, 25, 45, 30], 2, 1),
    (25, [0, 5, 35, 60], [0, 10, 40, 50], 3, 1),
]

ASK_QUESTIONS_BW = [
    "我现在该回家守床还是去拆对面的床？",
    "对面装备比我好，该硬打还是先拉开？",
    "金苹果现在吃还是留着？",
    "要不要扔珍珠跑？",
    "被两个人夹了怎么办？",
    "现在适合去拆床吗？",
    "我该不该先搭路回家？",
    "队友快死了，我去救还是自己先撤？",
    "对面在拉弓，我该怎么走位？",
    "这波能打过吗？",
    "我的床被人摸了，现在怎么处理？",
    "该不该现在去中岛抢钻石？",
]
ASK_QUESTIONS_SW = [
    "现在去中岛合适吗？",
    "对面在虚空边，怎么把他打下去最稳？",
    "我血不多了，该打还是该躲？",
    "要不要扔珍珠跑？",
    "金苹果现在吃还是留着？",
    "被两个人夹了怎么办？",
    "这波能打过吗？",
    "对面在拉弓，我该怎么走位？",
    "我该先搭路过去还是等他过来？",
]
QA_QUESTIONS = [
    "起床战争开局前两分钟最该做什么？",
    "起床战争里什么时候该买铁甲？",
    "空岛战争开局怎么快速搜刮？",
    "空岛战争里怎么用钓竿打连击？",
    "起床战争怎么防守床比较稳？",
    "怎么判断一场团战该不该进？",
    "被连击的时候怎么脱身？",
    "搭路的时候被人用弓打怎么办？",
    "空岛战争决赛圈该注意什么？",
    "起床战争单人模式和四人模式打法有什么区别？",
    "怎么用末影珍珠救命？",
    "残血的时候该怎么走位？",
    "起床战争钻石升级先点什么？",
    "起床战争里火球怎么用最有效？",
    "TNT 炸床怎么配合队友？",
    "起床战争什么时候该去中岛拿绿宝石？",
    "单人起床开局要不要先拆隔壁的床？",
    "家被偷了床之后怎么打？",
    "起床战争里怎么判断对面有没有在偷床？",
    "黑曜石护床值不值得买？",
    "被人用击退棒打下虚空怎么防？",
    "空岛战争要不要第一时间冲中岛？",
    "空岛战争开局没拿到好装备怎么办？",
    "空岛战争里怎么防止被人打下虚空？",
    "空岛战争里钓竿和雪球哪个更好用？",
    "空岛战争最后剩两个人的时候怎么打？",
    "跳桥的时候被人追怎么办？",
    "怎么提高连击的稳定性？",
    "W-tap 是什么，什么时候用？",
    "对面一直格挡怎么打？",
    "被多个人围攻时第一件事做什么？",
    "什么时候该吃金苹果，什么时候该留着？",
    "高处和低处打架各有什么优势？",
    "怎么利用地形卡对面的走位？",
    "和队友一起打团怎么配合？",
    "被弓手压着打怎么接近他？",
    "残局里对面装备比我好，还有机会赢吗？",
    "起床战争四人模式开局怎么分工？",
    "怎么判断该追击还是该回家？",
    "掉线重连后局势不明，第一步该看什么？",
]


def _pick(weights, items):
    return random.choices(items, weights=weights)[0]


def _hp(lo=1, hi=20):
    return random.randint(lo, hi)


def _dist(lo, hi):
    return round(random.uniform(lo, hi), 1)


def _bw_phase(minutes):
    chosen = BW_PHASES[0]
    for phase in BW_PHASES:
        if minutes >= phase[0]:
            chosen = phase
    return chosen


def _bw_gear(minutes):
    _, armor_w, sword_w, max_prot, max_sharp = _bw_phase(minutes)
    armor = _pick(armor_w, ARMORS)
    sword = _pick(sword_w, SWORDS)
    prot = random.randint(0, max_prot) if armor in ("铁", "钻") else 0
    sharp = random.randint(0, max_sharp) if sword in ("铁剑", "钻石剑") else 0
    return armor, prot, sword, sharp


def _enemy(dist, gear, team=None, bed=None):
    armor, prot, weapon, sharp = gear
    if random.random() < 0.08:
        weapon = "弓"
        sharp = 0
    close = dist <= 4.5
    facing = random.random() < (0.85 if close else 0.4)
    e = {
        "dist": dist,
        "hp": _hp(2, 20),
        "abs": random.choice([0, 0, 0, 4]),
        "armor": armor,
        "prot": prot,
        "weapon": weapon,
        "sharp": sharp,
        "hitting_me": close and facing and weapon != "弓" and random.random() < 0.7,
        "facing_me": facing,
        "bow_drawn": weapon == "弓" and dist > 6 and facing and random.random() < 0.7,
        "dy": random.choice([0] * 6 + [-3, -2, -1, 1, 2, 4, 8]),
        "void_edge": random.random() < 0.12,
    }
    if team:
        e["team"] = team
    if bed is not None:
        e["bed"] = bed
    if random.random() < 0.03:
        e["invis"] = True
        e["armor"] = "?"
        e["prot"] = 0
    return e


def _me(gear, minutes, mode):
    armor, prot, held, sharp = gear
    r = random.random()
    if r < 0.12:
        held = "弓"
        sharp = 0
    elif r < 0.2:
        held = "羊毛" if mode == "起床" else "方块"
    elif r < 0.24:
        held = "镐"
        sharp = 0
    me = {
        # mostly healthy: a uniform 1..20 made a third of the pilot "too low to fight" (先打：无)
        "hp": max(1, min(20, round(random.triangular(3, 20, 18)))),
        "max": 20,
        "abs": random.choice([0, 0, 0, 4, 4, 8]),
        "armor": armor,
        "prot": prot,
        "held": held,
        "sharp": sharp,
        "gapple": random.randint(0, 2 + minutes // 6),
        "pearl": random.choice([0, 0, 0, 1, 1, 2]) if minutes > 4 else 0,
        "blocks": random.choice([0, 8, 16, 24, 32, 48, 64, 96]),
        "effects": [],
        "void_edge": random.random() < 0.1,
    }
    if random.random() < 0.15:
        me["effects"] = random.sample(["速度II", "跳跃提升V", "急迫I", "力量I"], k=1)
    return me


def _apply_template(s, mode):
    """Turn a random situation into a deliberate dilemma (about half the time)."""
    enemies = s["enemies"]
    me = s["me"]
    kind = random.choice(["far_low_vs_close_full", "two_attackers", "bed_threat",
                          "void_knock", "self_low", "archer", "none"])
    if kind == "far_low_vs_close_full" and len(enemies) >= 2:
        enemies[0].update(dist=_dist(2.0, 3.5), hp=_hp(16, 20), hitting_me=True, facing_me=True)
        enemies[1].update(dist=_dist(6, 14), hp=_hp(1, 5), hitting_me=False)
    elif kind == "two_attackers" and len(enemies) >= 2:
        for e in enemies[:2]:
            e.update(dist=_dist(1.8, 4.0), hitting_me=True, facing_me=True)
    elif kind == "bed_threat" and mode == "起床" and s.get("my_bed"):
        enemies[-1]["near_my_bed"] = True
        enemies[-1]["dist"] = _dist(8, 25)
        enemies[-1]["hitting_me"] = False
    elif kind == "void_knock":
        enemies[0].update(void_edge=True, dist=_dist(2.5, 5.0), hp=_hp(6, 20))
    elif kind == "self_low":
        me.update(hp=_hp(1, 6), abs=0)
        enemies[0].update(dist=_dist(2.0, 4.0), hitting_me=True, facing_me=True)
    elif kind == "archer":
        enemies[-1].update(weapon="弓", sharp=0, dist=_dist(12, 30), facing_me=True,
                           bow_drawn=True, hitting_me=False)
    for e in enemies:
        if e.get("hitting_me") and e.get("weapon") == "弓":
            e["weapon"] = "石剑"  # a template made a bow user melee us
        if e.get("bow_drawn") and e.get("weapon") != "弓":
            e["bow_drawn"] = False
    # keep E-numbers ordered by distance, like the client does
    enemies.sort(key=lambda e: e["dist"])


def bedwars(kind):
    scale, teams_n, size = random.choice(BW_SCALES)
    colors = BW_COLORS[:teams_n]
    minutes = random.choice([1, 3, 5, 8, 11, 14, 18, 22, 27, 33])
    my_team = random.choice(colors)
    my_bed = random.random() < max(0.15, 0.95 - minutes * 0.03)
    teams = []
    for c in colors:
        if c == my_team:
            continue
        r = random.random()
        if r < max(0.1, 0.9 - minutes * 0.03):
            state = "✔"
        elif r < 0.85:
            state = str(random.randint(1, size))
        else:
            state = "✘"
        teams.append({"team": c, "state": state})
    alive = [t for t in teams if t["state"] != "✘"] or teams[:1]
    count = random.choices([1, 2, 3, 4, 5, 6, 8, 10], weights=[18, 24, 18, 12, 10, 8, 6, 4])[0]
    enemies = []
    for _ in range(count):
        t = random.choice(alive)
        enemies.append(_enemy(_dist(1.6, 35), _bw_gear(minutes), t["team"], t["state"] == "✔"))
    enemies.sort(key=lambda e: e["dist"])
    s = {
        "kind": kind, "mode": "起床", "scale": scale,
        "time": "%02d:%02d" % (minutes, random.randint(0, 59)),
        "my_team": my_team, "my_bed": my_bed, "teams": teams,
        "me": _me(_bw_gear(minutes), minutes, "起床"), "enemies": enemies,
    }
    if size > 1:
        mates_alive = random.randint(0, size - 1)
        s["mates_total"] = size - 1
        s["mates_alive"] = mates_alive
        s["mates"] = [{"dist": _dist(2, 30), "hp": _hp(2, 20), "armor": _bw_gear(minutes)[0]}
                      for _ in range(random.randint(0, mates_alive))]
    return s


def skywars(kind):
    scale, size = random.choice(SW_SCALES)
    minutes = random.choice([0, 1, 2, 3, 5, 7, 10])
    count = random.choices([1, 2, 3, 4, 5, 6], weights=[30, 28, 18, 12, 7, 5])[0]

    def gear():
        armor = _pick([20, 20, 35, 25], ARMORS)
        sword = _pick([5, 30, 40, 25], SWORDS)
        return (armor, random.choice([0, 0, 1, 2]) if armor in ("铁", "钻") else 0,
                sword, random.choice([0, 0, 1]) if sword in ("铁剑", "钻石剑") else 0)

    enemies = [_enemy(_dist(1.6, 40), gear()) for _ in range(count)]
    for e in enemies:
        e["void_edge"] = random.random() < 0.25
    enemies.sort(key=lambda e: e["dist"])
    me = _me(gear(), minutes + 6, "空岛")
    me["void_edge"] = random.random() < 0.2
    s = {
        "kind": kind, "mode": "空岛", "scale": scale,
        "time": "%02d:%02d" % (minutes, random.randint(0, 59)),
        "players_left": count + random.randint(0, 6) + 1,
        "me": me, "enemies": enemies,
    }
    if size > 1:
        s["mates_total"] = 1
        s["mates_alive"] = random.randint(0, 1)
        s["mates"] = [{"dist": _dist(2, 25), "hp": _hp(2, 20), "armor": gear()[0]}
                      for _ in range(s["mates_alive"]) if random.random() < 0.7]
    return s


def situation(kind):
    """kind: 'target' (先打谁) or 'ask' (局势 + 自由问题)."""
    s = bedwars(kind) if random.random() < 0.55 else skywars(kind)
    if kind == "target" and s["enemies"] and random.random() < 0.8:
        # "who first" is asked in a fight: with nobody within 6 blocks the teacher (rightly) said
        # 先打：无 79% of the time, which made "无" the majority answer of the first batches
        e = s["enemies"][0]
        e["dist"] = _dist(1.8, 6.0)
        if e["dist"] <= 4.5 and random.random() < 0.6:
            e.update(facing_me=True, hitting_me=e.get("weapon") != "弓")
        s["enemies"].sort(key=lambda x: x["dist"])
    if random.random() < 0.5:
        _apply_template(s, s["mode"])
    if kind == "ask":
        pool = ASK_QUESTIONS_BW if s["mode"] == "起床" else ASK_QUESTIONS_SW
        s["question"] = random.choice(pool)
    return s


_qa_pool = []


def qa():
    """Questions are dealt from a shuffled deck so a batch repeats as little as possible."""
    if not _qa_pool:
        _qa_pool.extend(QA_QUESTIONS)
        random.shuffle(_qa_pool)
    return {"kind": "qa", "question": _qa_pool.pop()}
