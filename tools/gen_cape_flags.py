#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 Cape 模块用的旗帜披风贴图。

    python tools/gen_cape_flags.py                  生成全部 PNG 到 assets 目录
    python tools/gen_cape_flags.py --preview <目录>  另外把外侧面拼成一张总览图存到该目录

贴图为 1024x512 RGBA，即原版 64x32 披风布局 x16。每面旗先按 10:16 竖幅画出
160x256 的"外侧面"（从玩家背后看到的那一面），再由 build_cape() 贴进完整布局。
徽记在 4x 超采样图层上绘制后用 LANCZOS 缩小，纯条纹直接按最终分辨率绘制。
"""
import argparse
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "src" / "main" / "resources" / "assets" / "minecraft" / "bettermyau" / "cape"

SCALE = 16                       # 贴图相对原版 64x32 的倍数
TEX_W, TEX_H = 64 * SCALE, 32 * SCALE
FACE_W, FACE_H = 10 * SCALE, 16 * SCALE   # 外侧面 160x256
SS = 4                           # 徽记超采样倍数

WHITE = (255, 255, 255, 255)
BLACK = (0, 0, 0, 255)


def rgb(hex_color):
    hex_color = hex_color.lstrip("#")
    return tuple(int(hex_color[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


# --------------------------------------------------------------------------- 披风布局

def build_cape(face):
    """把 160x256 的外侧面贴进完整的 1024x512 披风布局（盒子 10x16x1，UV 原点 0,0）。"""
    if face.size != (FACE_W, FACE_H):
        raise ValueError("face must be %dx%d" % (FACE_W, FACE_H))
    face = face.convert("RGBA")
    face.putalpha(255)
    s = SCALE
    cape = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))

    cape.paste(face, (1 * s, 1 * s))     # 外侧面 (1,1)-(11,17)
    cape.paste(face, (12 * s, 1 * s))    # 内侧面 (12,1)-(22,17)，不镜像

    left = face.crop((0, 0, 1, FACE_H)).resize((s, FACE_H), Image.NEAREST)
    right = face.crop((FACE_W - 1, 0, FACE_W, FACE_H)).resize((s, FACE_H), Image.NEAREST)
    top = face.crop((0, 0, FACE_W, 1)).resize((FACE_W, s), Image.NEAREST)
    bottom = face.crop((0, FACE_H - 1, FACE_W, FACE_H)).resize((FACE_W, s), Image.NEAREST)
    cape.paste(left, (0, 1 * s))         # 左边条 (0,1)-(1,17)
    cape.paste(right, (11 * s, 1 * s))   # 右边条 (11,1)-(12,17)
    cape.paste(top, (1 * s, 0))          # 顶边 (1,0)-(11,1)
    cape.paste(bottom, (11 * s, 0))      # 底边 (11,0)-(21,1)

    # 盒子 UV 剩下的两个角落，填邻近色
    cape.paste(face.getpixel((0, 0)), (0, 0, 1 * s, 1 * s))
    cape.paste(face.getpixel((FACE_W - 1, FACE_H - 1)), (21 * s, 0, 22 * s, 1 * s))
    return cape


# --------------------------------------------------------------------------- 绘图工具

def new_face(color):
    return Image.new("RGBA", (FACE_W, FACE_H), color)


def h_bands(colors, weights=None):
    """横向色带铺满竖幅，按最终分辨率绘制。"""
    weights = weights or [1] * len(colors)
    face = new_face(colors[0])
    draw = ImageDraw.Draw(face)
    total, acc = float(sum(weights)), 0.0
    for color, weight in zip(colors, weights):
        y0 = int(round(acc / total * FACE_H))
        acc += weight
        y1 = int(round(acc / total * FACE_H))
        draw.rectangle([0, y0, FACE_W - 1, y1 - 1], fill=color)
    return face


def v_bands(colors):
    """竖向色带铺满竖幅，按最终分辨率绘制。"""
    face = new_face(colors[0])
    draw = ImageDraw.Draw(face)
    n = len(colors)
    for i, color in enumerate(colors):
        x0 = int(round(i * FACE_W / float(n)))
        x1 = int(round((i + 1) * FACE_W / float(n)))
        draw.rectangle([x0, 0, x1 - 1, FACE_H - 1], fill=color)
    return face


class Layer:
    """4x 超采样的透明图层，坐标用最终分辨率下的像素（可为小数）。"""

    def __init__(self, w=FACE_W, h=FACE_H):
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.img)

    def poly(self, pts, fill):
        self.draw.polygon([(x * SS, y * SS) for x, y in pts], fill=fill)

    def circle(self, cx, cy, r, fill):
        self.draw.ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=fill)

    def pieslice(self, cx, cy, r, start, end, fill):
        self.draw.pieslice([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], start, end, fill=fill)

    def rect(self, x0, y0, x1, y1, fill):
        self.draw.rectangle([x0 * SS, y0 * SS, x1 * SS - 1, y1 * SS - 1], fill=fill)

    def bar(self, cx, cy, ax, ay, half_len, half_thick, fill):
        """中心 (cx,cy)、长轴方向 (ax,ay)（单位向量）的旋转矩形。"""
        nx, ny = -ay, ax
        self.poly([
            (cx - ax * half_len - nx * half_thick, cy - ay * half_len - ny * half_thick),
            (cx + ax * half_len - nx * half_thick, cy + ay * half_len - ny * half_thick),
            (cx + ax * half_len + nx * half_thick, cy + ay * half_len + ny * half_thick),
            (cx - ax * half_len + nx * half_thick, cy - ay * half_len + ny * half_thick),
        ], fill)

    def fill_mask(self, mask, fill):
        """mask 为 4x 尺寸的 L 图。"""
        self.img.paste(fill, (0, 0), mask)

    def new_mask(self):
        return Image.new("L", self.img.size, 0)

    def onto(self, base, pos=(0, 0)):
        small = self.img.resize((self.w, self.h), Image.LANCZOS)
        base.alpha_composite(small, pos)
        return base


def star_pts(cx, cy, r, n=5, inner=None, rot=-90.0):
    """n 角星多边形；rot 为第一个尖角的朝向（度，屏幕坐标，-90 = 朝上）。"""
    if inner is None:
        inner = 0.381966 if n == 5 else 4.0 / 9.0
    pts = []
    for i in range(2 * n):
        a = math.radians(rot + i * 180.0 / n)
        rr = r if i % 2 == 0 else r * inner
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


def union_jack(w, h):
    """按 w x h 矩形重新排版的米字旗：对角线走矩形自身的对角，线宽按短边/30 取。"""
    blue, red = rgb("#012169"), rgb("#C8102E")
    u = min(w, h) / 30.0
    cx, cy = w / 2.0, h / 2.0
    reach = math.hypot(w, h)

    layer = Layer(w, h)
    layer.rect(0, 0, w, h, blue)
    arms = []
    for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        length = math.hypot(w, h)
        arms.append((sx * w / length, sy * h / length))
    # 白色斜十字
    for dx, dy in arms[:2]:
        layer.bar(cx, cy, dx, dy, reach, 3 * u, WHITE)
    # 红色斜条：贴着中心线、偏向逆时针一侧（风车状错位）
    for dx, dy in arms:
        nx, ny = dy, -dx
        layer.poly([
            (cx, cy),
            (cx + dx * reach, cy + dy * reach),
            (cx + dx * reach + nx * 2 * u, cy + dy * reach + ny * 2 * u),
            (cx + nx * 2 * u, cy + ny * 2 * u),
        ], red)
    img = Image.new("RGBA", (w, h), blue)
    layer.onto(img)

    # 正十字轴对齐，直接按最终分辨率画
    draw = ImageDraw.Draw(img)
    white_half = max(1, int(round(5 * u)))
    red_half = max(1, int(round(3 * u)))
    mx, my = w // 2, h // 2
    draw.rectangle([mx - white_half, 0, mx + white_half - 1, h - 1], fill=WHITE)
    draw.rectangle([0, my - white_half, w - 1, my + white_half - 1], fill=WHITE)
    draw.rectangle([mx - red_half, 0, mx + red_half - 1, h - 1], fill=red)
    draw.rectangle([0, my - red_half, w - 1, my + red_half - 1], fill=red)
    return img


# --------------------------------------------------------------------------- 各旗

def flag_trans():
    blue, pink = rgb("#5BCEFA"), rgb("#F5A9B8")
    return h_bands([blue, pink, WHITE, pink, blue])


def flag_china():
    face = new_face(rgb("#DE2910"))
    yellow = rgb("#FFDE00")
    layer = Layer()
    # 原旗左上角 15x10 格的星位，按格宽 u 缩放后放在竖幅左上
    u, ox, oy = 8.8, 6.0, 10.0
    bx, by = ox + 5 * u, oy + 5 * u
    layer.poly(star_pts(bx, by, 3 * u), yellow)
    for gx, gy in ((10, 2), (12, 4), (12, 7), (10, 9)):
        sx, sy = ox + gx * u, oy + gy * u
        toward = math.degrees(math.atan2(by - sy, bx - sx))
        layer.poly(star_pts(sx, sy, u, rot=toward), yellow)
    return layer.onto(face)


def flag_usa():
    red, blue = rgb("#B22234"), rgb("#3C3B6E")
    face = h_bands([red if i % 2 == 0 else WHITE for i in range(13)])
    canton_w = 100
    canton_h = int(round(7 * FACE_H / 13.0))
    ImageDraw.Draw(face).rectangle([0, 0, canton_w - 1, canton_h - 1], fill=blue)
    # 竖幅星区：11 行，5 / 4 颗交错，共 50 颗
    layer = Layer()
    for row in range(11):
        for col in range(9):
            if (row + col) % 2 == 0:
                x = canton_w * (col + 1) / 10.0
                y = canton_h * (row + 1) / 12.0
                layer.poly(star_pts(x, y, 4.7), WHITE)
    return layer.onto(face)


def flag_japan():
    face = new_face(WHITE)
    layer = Layer()
    layer.circle(FACE_W / 2.0, FACE_H / 2.0, FACE_W * 0.3, rgb("#BC002D"))
    return layer.onto(face)


def flag_korea():
    face = new_face(WHITE)
    red, blue = rgb("#CD2E3A"), rgb("#0047A0")
    layer = Layer()
    cx, cy = FACE_W / 2.0, FACE_H / 2.0
    d = 88.0                      # 太极直径
    r = d / 2.0

    # 太极：分界轴沿原旗对角线方向（atan(2/3)），上红下蓝
    theta = math.degrees(math.atan2(2.0, 3.0))
    ax, ay = math.cos(math.radians(theta)), math.sin(math.radians(theta))
    layer.circle(cx, cy, r, blue)
    layer.pieslice(cx, cy, r, theta + 180, theta + 360, red)
    layer.circle(cx - ax * r / 2, cy - ay * r / 2, r / 2, red)
    layer.circle(cx + ax * r / 2, cy + ay * r / 2, r / 2, blue)

    # 四卦：沿竖幅矩形的对角线放在四角，爻与对角线垂直
    diag = math.hypot(FACE_W, FACE_H)
    ux, uy = FACE_W / diag, FACE_H / diag
    dist = r + d / 4 + d / 6      # 圆心到卦中心
    half_len, half_thick, step, gap = d / 4, d / 24, d / 8, d / 24
    trigrams = (
        (-1, -1, (1, 1, 1)),      # 左上 乾
        (1, 1, (0, 0, 0)),        # 右下 坤
        (1, -1, (0, 1, 0)),       # 右上 坎
        (-1, 1, (1, 0, 1)),       # 左下 离
    )
    for sx, sy, lines in trigrams:
        rx, ry = sx * ux, sy * uy             # 径向
        tx, ty = -ry, rx                      # 爻的长轴
        px, py = cx + rx * dist, cy + ry * dist
        for i, solid in enumerate(lines):
            bx, by = px + rx * step * (i - 1), py + ry * step * (i - 1)
            if solid:
                layer.bar(bx, by, tx, ty, half_len, half_thick, BLACK)
            else:
                seg = (half_len - gap / 2) / 2
                off = gap / 2 + seg
                layer.bar(bx + tx * off, by + ty * off, tx, ty, seg, half_thick, BLACK)
                layer.bar(bx - tx * off, by - ty * off, tx, ty, seg, half_thick, BLACK)
    return layer.onto(face)


def flag_uk():
    return union_jack(FACE_W, FACE_H)


def flag_france():
    return v_bands([rgb("#0055A4"), WHITE, rgb("#EF4135")])


def flag_germany():
    return h_bands([BLACK, rgb("#DD0000"), rgb("#FFCE00")])


def flag_russia():
    return h_bands([WHITE, rgb("#0039A6"), rgb("#D52B1E")])


def flag_italy():
    return v_bands([rgb("#009246"), WHITE, rgb("#CE2B37")])


def flag_spain():
    red, yellow = rgb("#AA151B"), rgb("#F1BF00")
    face = h_bands([red, yellow, red], [1, 2, 1])
    gold, outline, stone = rgb("#C8A000"), rgb("#6B4E00"), rgb("#E4E4E4")
    layer = Layer()
    # 简化盾徽：黄带偏上处。以盾顶中点为原点、k 为缩放的局部坐标
    ox, oy, k = FACE_W / 2.0, 100.0, 1.2
    sw, sh = 32.0, 40.0           # 盾宽 / 盾高（局部坐标）

    def X(x):
        return ox + x * k

    def Y(y):
        return oy + y * k

    def box(x0, y0, x1, y1, fill):
        layer.rect(X(x0), Y(y0), X(x1), Y(y1), fill)

    # 两侧立柱（海格力斯之柱）+ 红色绶带
    for side in (-1, 1):
        px = side * 27.0
        box(px - 4, 3, px + 4, sh - 3, outline)
        box(px - 3, 4, px + 3, sh - 4, stone)
        box(px - 5.5, 0, px + 5.5, 5, gold)
        box(px - 5.5, sh - 5, px + 5.5, sh, gold)
        box(px - 6.5, 14, px + 6.5, 19.5, red)

    # 盾：上方平、下方半圆，四分色块
    def shield_mask(inset):
        mask = layer.new_mask()
        md = ImageDraw.Draw(mask)
        x0, x1 = X(-sw / 2 + inset) * SS, X(sw / 2 - inset) * SS
        y0, y1 = Y(inset) * SS, Y(sh - inset) * SS
        rr = (x1 - x0) / 2
        md.rectangle([x0, y0, x1 - 1, y1 - rr], fill=255)
        md.ellipse([x0, y1 - 2 * rr, x1 - 1, y1 - 1], fill=255)
        return mask

    layer.fill_mask(shield_mask(0), outline)
    quarters = Image.new("RGBA", layer.img.size, (0, 0, 0, 0))
    qd = ImageDraw.Draw(quarters)
    mid = sh * 0.46

    def qbox(x0, y0, x1, y1, fill):
        qd.rectangle([X(x0) * SS, Y(y0) * SS, X(x1) * SS - 1, Y(y1) * SS - 1], fill=fill)

    qbox(-sw, 0, 0, mid, red)                 # 卡斯蒂利亚：红底金城堡
    qbox(0, 0, sw, mid, WHITE)                # 莱昂：白底紫狮
    qbox(-sw, mid, 0, sh, yellow)             # 阿拉贡：金底红竖条
    for i in range(4):
        sx = -sw / 2 + 2.6 + i * 3.6
        qbox(sx, mid, sx + 1.7, sh, red)
    qbox(0, mid, sw, sh, red)                 # 纳瓦拉：红底金锁链
    layer.img.paste(quarters, (0, 0), shield_mask(1.6))
    box(-10.5, 6, -4.5, 14, gold)
    box(-9.0, 4, -6.0, 6, gold)
    layer.circle(X(7.5), Y(10), 3.6 * k, rgb("#8E2A8E"))
    layer.circle(X(7.0), Y(28), 3.4 * k, gold)

    # 王冠：深色描边 + 金色冠体 + 红色内衬
    def crown(grow, fill):
        by = -2.0
        layer.poly([(X(x), Y(by + y)) for x, y in (
            (-12 - grow, grow), (-14 - grow, -12 - grow), (-7, -7 - grow), (-4, -14 - grow), (0, -8 - grow),
            (4, -14 - grow), (7, -7 - grow), (14 + grow, -12 - grow), (12 + grow, grow),
        )], fill)

    crown(1.2, outline)
    crown(0.0, gold)
    layer.poly([(X(x), Y(y)) for x, y in ((-9, -6), (-10, -10), (-6.5, -7.5), (-4, -11), (0, -8),
                                          (4, -11), (6.5, -7.5), (10, -10), (9, -6))], red)
    box(-12, -6, 12, -2, outline)
    box(-11, -5.2, 11, -2.8, gold)
    layer.circle(X(0), Y(-18.5), 2.6 * k, outline)
    layer.circle(X(0), Y(-18.5), 1.6 * k, gold)
    return layer.onto(face)


# 加拿大国旗枫叶的右半边轮廓（取自标准 9600x4800 图样，x 为相对中轴的偏移）
MAPLE_HALF = [
    (0, 400), (332, 1052), (423, 1079), (750, 890), (546, 1942), (657, 1999), (1080, 1545),
    (1185, 1792), (1258, 1830), (1800, 1715), (1614, 2287), (1648, 2366), (1860, 2465),
    (919, 3227), (899, 3300), (1015, 3620), (156, 3469), (45, 3567), (90, 4430),
]


def flag_canada():
    red = rgb("#D80621")
    # 竖幅：上下红带、中间白区放枫叶
    face = h_bands([red, WHITE, red], [1, 2, 1])
    leaf_h = 108.0
    k = leaf_h / (4430 - 400)
    cx = FACE_W / 2.0
    top = FACE_H / 2.0 - leaf_h / 2.0
    right = [(cx + x * k, top + (y - 400) * k) for x, y in MAPLE_HALF]
    left = [(cx - x * k, top + (y - 400) * k) for x, y in reversed(MAPLE_HALF[1:])]
    layer = Layer()
    layer.poly(right + left, red)
    return layer.onto(face)


def flag_brazil():
    face = new_face(rgb("#009C3B"))
    yellow, blue = rgb("#FFDF00"), rgb("#002776")
    layer = Layer()
    cx, cy = FACE_W / 2.0, FACE_H / 2.0
    layer.poly([(cx, 16), (FACE_W - 13, cy), (cx, FACE_H - 16), (13, cy)], yellow)
    r = 45.0
    layer.circle(cx, cy, r, blue)

    # 白色弧带：圆心在蓝圆左下方的大圆环，裁进蓝圆
    unit = r / 3.5
    bx, by = cx - 2 * unit, cy + 7 * unit
    ring = layer.new_mask()
    rd = ImageDraw.Draw(ring)
    outer, inner_r = 8.55 * unit, 7.95 * unit
    rd.ellipse([(bx - outer) * SS, (by - outer) * SS, (bx + outer) * SS, (by + outer) * SS], fill=255)
    rd.ellipse([(bx - inner_r) * SS, (by - inner_r) * SS, (bx + inner_r) * SS, (by + inner_r) * SS], fill=0)
    disc = layer.new_mask()
    ImageDraw.Draw(disc).ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=255)
    layer.fill_mask(ImageChops.multiply(ring, disc), WHITE)

    # 星点（相对圆心、以半径为单位；第三项为星的半径像素）
    stars = [
        (0.26, -0.50, 3.0),                                   # 角宿一，弧带上方
        (-0.62, 0.00, 3.0),                                   # 南河三
        (-0.52, 0.36, 3.4), (-0.70, 0.46, 2.2), (-0.44, 0.56, 2.2), (-0.60, 0.64, 2.0),   # 大犬
        (-0.24, 0.52, 3.0),                                   # 老人星
        (0.00, 0.22, 2.6), (0.00, 0.64, 3.0), (-0.15, 0.42, 2.6), (0.15, 0.38, 2.6), (0.08, 0.50, 1.8),  # 南十字
        (0.00, 0.86, 1.8),                                    # 南极星
        (0.30, 0.62, 2.2), (0.43, 0.54, 2.2), (0.40, 0.72, 2.2),                           # 南三角
        (0.32, 0.08, 2.4),                                    # 长蛇
        (0.62, 0.20, 3.0), (0.72, 0.32, 2.2), (0.64, 0.44, 2.2), (0.52, 0.34, 2.0),        # 天蝎
    ]
    for sx, sy, sr in stars:
        layer.poly(star_pts(cx + sx * r, cy + sy * r, sr), WHITE)
    return layer.onto(face)


def flag_australia():
    face = new_face(rgb("#00008B"))
    canton_w, canton_h = 96, 64
    face.paste(union_jack(canton_w, canton_h), (0, 0))
    layer = Layer()
    # 联邦星：米字旗正下方
    layer.poly(star_pts(42, 104, 27, n=7), WHITE)
    # 南十字：按官方图样的相对位置，w 为图样中的旗高
    w, fx, fy = 180.0, 102.0, 166.0
    big = w / 14.0
    layer.poly(star_pts(fx, fy + w / 3.0, big, n=7), WHITE)                         # alpha
    layer.poly(star_pts(fx - w / 4.0, fy - w / 16.0, big, n=7), WHITE)              # beta
    layer.poly(star_pts(fx, fy - w / 3.0, big, n=7), WHITE)                         # gamma
    layer.poly(star_pts(fx + w * 2 / 9.0, fy - w * 31 / 240.0, big, n=7), WHITE)    # delta
    layer.poly(star_pts(fx + w / 10.0, fy + w / 24.0, w / 24.0, n=5), WHITE)        # epsilon
    return layer.onto(face)


def flag_dutch():
    return h_bands([rgb("#AE1C28"), WHITE, rgb("#21468B")])


# (Cape.java 里的模式名, 文件名, 绘制函数)，顺序与 Cape.java 末尾追加的顺序一致
FLAGS = [
    ("TRANS", "trans", flag_trans),
    ("CHINA", "china", flag_china),
    ("USA", "usa", flag_usa),
    ("JAPAN", "japan", flag_japan),
    ("KOREA", "korea", flag_korea),
    ("UK", "uk", flag_uk),
    ("FRANCE", "france", flag_france),
    ("GERMANY", "germany", flag_germany),
    ("RUSSIA", "russia", flag_russia),
    ("ITALY", "italy", flag_italy),
    ("SPAIN", "spain", flag_spain),
    ("CANADA", "canada", flag_canada),
    ("BRAZIL", "brazil", flag_brazil),
    ("AUSTRALIA", "australia", flag_australia),
    ("DUTCH", "dutch", flag_dutch),
]


# --------------------------------------------------------------------------- 输出

def load_font(size):
    for name in ("segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def build_preview(faces, columns=5):
    cell_w, cell_h, pad, label_h = 240, 384, 24, 30
    rows = (len(faces) + columns - 1) // columns
    sheet = Image.new("RGB", (pad + columns * (cell_w + pad), pad + rows * (cell_h + label_h + pad)), (40, 42, 48))
    draw = ImageDraw.Draw(sheet)
    font = load_font(20)
    for i, (name, face) in enumerate(faces):
        x = pad + (i % columns) * (cell_w + pad)
        y = pad + (i // columns) * (cell_h + label_h + pad)
        sheet.paste(face.convert("RGB").resize((cell_w, cell_h), Image.LANCZOS), (x, y))
        draw.rectangle([x - 1, y - 1, x + cell_w, y + cell_h], outline=(90, 94, 104))
        draw.text((x + cell_w / 2.0, y + cell_h + label_h / 2.0 + 1), name, fill=(235, 235, 240), font=font, anchor="mm")
    return sheet


def main():
    parser = argparse.ArgumentParser(description="Generate flag cape textures for the Cape module.")
    parser.add_argument("--preview", metavar="DIR", help="also write an overview sheet of all outer faces into DIR")
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    faces = []
    for name, file_name, painter in FLAGS:
        face = painter()
        faces.append((name, face))
        target = OUT_DIR / (file_name + ".png")
        build_cape(face).save(target, optimize=True)
        print("wrote", target)

    if args.preview:
        preview_dir = Path(args.preview)
        preview_dir.mkdir(parents=True, exist_ok=True)
        target = preview_dir / "cape_flags_preview.png"
        build_preview(faces).save(target)
        print("wrote", target)


if __name__ == "__main__":
    main()
