package myau.injector.ui;

import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/** 线条图标，全部用 Java2D 画，在任意缩放下都清晰。坐标是图标中心 (cx, cy) 与边长 s。 */
public final class Icons {
    private Icons() {
    }

    private static void pen(Graphics2D g, Color c, float width) {
        g.setColor(c);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    public static void close(Graphics2D g, float cx, float cy, float s, Color c) {
        pen(g, c, 1.4f);
        float h = s * 0.32f;
        g.draw(new Line2D.Float(cx - h, cy - h, cx + h, cy + h));
        g.draw(new Line2D.Float(cx + h, cy - h, cx - h, cy + h));
    }

    public static void minimize(Graphics2D g, float cx, float cy, float s, Color c) {
        pen(g, c, 1.4f);
        float h = s * 0.34f;
        g.draw(new Line2D.Float(cx - h, cy, cx + h, cy));
    }

    /** 刷新：带箭头的 3/4 圆弧，angle 用于转动。 */
    public static void refresh(Graphics2D g, float cx, float cy, float s, Color c, double angle) {
        Graphics2D r = (Graphics2D) g.create();
        r.rotate(angle, cx, cy);
        pen(r, c, 1.5f);
        float rad = s * 0.34f;
        r.draw(new Arc2D.Float(cx - rad, cy - rad, rad * 2, rad * 2, 60, 280, Arc2D.OPEN));
        // 箭头：在弧的起点（60°）处
        double a = Math.toRadians(60);
        float ex = cx + (float) (Math.cos(a) * rad), ey = cy - (float) (Math.sin(a) * rad);
        Path2D.Float head = new Path2D.Float();
        head.moveTo(ex - s * 0.20f, ey - s * 0.06f);
        head.lineTo(ex + s * 0.02f, ey + s * 0.02f);
        head.lineTo(ex + s * 0.02f, ey - s * 0.22f);
        r.draw(head);
        r.dispose();
    }

    public static void copy(Graphics2D g, float cx, float cy, float s, Color c) {
        pen(g, c, 1.3f);
        float w = s * 0.46f, o = s * 0.14f;
        g.draw(Theme.round(cx - w / 2 + o, cy - w / 2 - o, w, w, 2f));
        g.draw(Theme.round(cx - w / 2 - o, cy - w / 2 + o, w, w, 2f));
    }

    /** 折叠箭头；open 0..1 从朝上转到朝下。 */
    public static void chevron(Graphics2D g, float cx, float cy, float s, Color c, float open) {
        Graphics2D r = (Graphics2D) g.create();
        r.rotate(Math.PI * open, cx, cy);
        pen(r, c, 1.5f);
        float h = s * 0.26f;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx - h, cy + h * 0.5f);
        p.lineTo(cx, cy - h * 0.5f);
        p.lineTo(cx + h, cy + h * 0.5f);
        r.draw(p);
        r.dispose();
    }

    public static void check(Graphics2D g, float cx, float cy, float s, Color c, float width) {
        pen(g, c, width);
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx - s * 0.28f, cy + s * 0.02f);
        p.lineTo(cx - s * 0.08f, cy + s * 0.22f);
        p.lineTo(cx + s * 0.30f, cy - s * 0.20f);
        g.draw(p);
    }

    public static void cross(Graphics2D g, float cx, float cy, float s, Color c, float width) {
        pen(g, c, width);
        float h = s * 0.22f;
        g.draw(new Line2D.Float(cx - h, cy - h, cx + h, cy + h));
        g.draw(new Line2D.Float(cx + h, cy - h, cx - h, cy + h));
    }

    /** 加载圈：一段转动的弧。 */
    public static void spinner(Graphics2D g, float cx, float cy, float s, Color c, float width) {
        double t = Anim.seconds();
        float rad = s / 2f;
        pen(g, Theme.alpha(c, 0.18f), width);
        g.draw(new Arc2D.Float(cx - rad, cy - rad, rad * 2, rad * 2, 0, 360, Arc2D.OPEN));
        pen(g, c, width);
        double start = -(t * 360 * 1.1) % 360;
        double extent = 70 + 50 * Math.sin(t * 3.2);
        g.draw(new Arc2D.Double(cx - rad, cy - rad, rad * 2, rad * 2, start, extent, Arc2D.OPEN));
    }

    /** Minecraft 方块：三面不同明度的等轴立方体。 */
    public static void block(Graphics2D g, float cx, float cy, float s, Color base) {
        float w = s * 0.42f, h = s * 0.24f, d = s * 0.46f;
        float top = cy - d / 2 - h / 2 + s * 0.02f;
        Path2D.Float topFace = new Path2D.Float();
        topFace.moveTo(cx, top);
        topFace.lineTo(cx + w, top + h);
        topFace.lineTo(cx, top + h * 2);
        topFace.lineTo(cx - w, top + h);
        topFace.closePath();
        Path2D.Float left = new Path2D.Float();
        left.moveTo(cx - w, top + h);
        left.lineTo(cx, top + h * 2);
        left.lineTo(cx, top + h * 2 + d);
        left.lineTo(cx - w, top + h + d);
        left.closePath();
        Path2D.Float right = new Path2D.Float();
        right.moveTo(cx + w, top + h);
        right.lineTo(cx, top + h * 2);
        right.lineTo(cx, top + h * 2 + d);
        right.lineTo(cx + w, top + h + d);
        right.closePath();
        g.setColor(Theme.mix(base, Color.WHITE, 0.25f));
        g.fill(topFace);
        g.setColor(base);
        g.fill(left);
        g.setColor(Theme.mix(base, Color.BLACK, 0.30f));
        g.fill(right);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.alpha(Color.WHITE, 0.18f));
        g.draw(topFace);
    }
}
