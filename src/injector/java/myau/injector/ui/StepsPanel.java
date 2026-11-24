package myau.injector.ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * 注入步骤进度：准备载荷 → 连接游戏进程 → 加载 Agent → 启动 BetterMyau。
 * 每步四种状态：等待（空心圆）、进行中（转圈）、完成（主题色对勾）、失败（红叉）。下方是结果横幅。
 */
public final class StepsPanel extends JComponent {
    private static final String[] STEPS = {"准备载荷", "连接游戏进程", "加载 Agent", "启动 BetterMyau"};
    private static final int ROW = 30;
    private static final int PENDING = 0, ACTIVE = 1, DONE = 2, FAILED = 3;

    private final int[] state = new int[STEPS.length];
    private final Anim[] fill = new Anim[STEPS.length];
    private final Anim banner = new Anim(this, 0, 8);
    private String bannerText;
    private boolean bannerOk;
    private boolean spinning;

    public StepsPanel() {
        setOpaque(false);
        for (int i = 0; i < fill.length; i++) fill[i] = new Anim(this, 0, 10);
    }

    /** 回到初始状态（全部等待、无横幅）。 */
    public void reset() {
        for (int i = 0; i < state.length; i++) {
            state[i] = PENDING;
            fill[i].to(0);
        }
        bannerText = null;
        banner.to(0);
        setSpinning(false);
        repaint();
    }

    /** 进入第 index 步：之前的打勾，这一步转圈。 */
    public void enter(int index) {
        for (int i = 0; i < state.length; i++) {
            if (i < index) { state[i] = DONE; fill[i].to(1); }
            else if (i == index) { state[i] = ACTIVE; fill[i].to(0); }
            else { state[i] = PENDING; fill[i].to(0); }
        }
        bannerText = null;
        banner.to(0);
        setSpinning(true);
        repaint();
    }

    public void complete(String message) {
        for (int i = 0; i < state.length; i++) {
            state[i] = DONE;
            fill[i].to(1);
        }
        showBanner(message, true);
    }

    /** 当前进行中的那一步标成失败（还没开始就失败则标第一步）。 */
    public void fail(String message) {
        int at = -1;
        for (int i = 0; i < state.length; i++) if (state[i] == ACTIVE) at = i;
        if (at < 0) at = 0;
        state[at] = FAILED;
        fill[at].to(1);
        showBanner(message, false);
    }

    private void showBanner(String message, boolean ok) {
        bannerText = message;
        bannerOk = ok;
        banner.snap(0);
        banner.to(1);
        setSpinning(false);
        repaint();
    }

    private void setSpinning(boolean on) {
        if (on == spinning) return;
        spinning = on;
        if (on) Anim.startContinuous(this); else Anim.stopContinuous(this);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(240, STEPS.length * ROW + 78);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.hq(g0);
        float w = getWidth();
        float cx = 11;

        // 连接线
        for (int i = 0; i < STEPS.length - 1; i++) {
            float y1 = i * ROW + ROW / 2f + 11, y2 = (i + 1) * ROW + ROW / 2f - 11;
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Theme.mix(Theme.alpha(Color.WHITE, 0.10f), Theme.alpha(Theme.ACCENT, 0.55f), state[i] == DONE ? fill[i].get() : 0));
            g.draw(new java.awt.geom.Line2D.Float(cx, y1, cx, y2));
        }

        g.setFont(Theme.ui(13f, false));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < STEPS.length; i++) {
            float cy = i * ROW + ROW / 2f;
            float f = fill[i].get();
            switch (state[i]) {
                case ACTIVE -> Icons.spinner(g, cx, cy, 16, Theme.ACCENT, 2f);
                case DONE -> {
                    g.setColor(Theme.alpha(Theme.ACCENT, 0.18f + 0.82f * f));
                    g.fill(new java.awt.geom.Ellipse2D.Float(cx - 9, cy - 9, 18, 18));
                    Icons.check(g, cx, cy, 16, Theme.alpha(Theme.ON_ACCENT, Math.min(1, f * 1.4f)), 2f);
                }
                case FAILED -> {
                    g.setColor(Theme.alpha(Theme.BAD, 0.25f + 0.75f * f));
                    g.fill(new java.awt.geom.Ellipse2D.Float(cx - 9, cy - 9, 18, 18));
                    Icons.cross(g, cx, cy, 16, Color.WHITE, 2f);
                }
                default -> {
                    g.setColor(Theme.alpha(Color.WHITE, 0.22f));
                    g.setStroke(new BasicStroke(1.5f));
                    g.draw(new java.awt.geom.Ellipse2D.Float(cx - 8, cy - 8, 16, 16));
                }
            }
            Color tc = switch (state[i]) {
                case ACTIVE -> Theme.TEXT;
                case DONE -> Theme.mix(Theme.MUTED, Theme.TEXT, 0.5f);
                case FAILED -> Theme.mix(Theme.BAD, Color.WHITE, 0.3f);
                default -> Theme.FAINT;
            };
            g.setColor(tc);
            g.drawString(STEPS[i], cx + 20, cy + (fm.getAscent() - fm.getDescent()) / 2f);
        }

        // 结果横幅
        float bv = banner.get();
        if (bannerText != null && bv > 0.01f) {
            float by = STEPS.length * ROW + 12 + (1 - bv) * 6, bh = 60;
            Color c = bannerOk ? Theme.OK : Theme.BAD;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, bv)));
            g.setColor(Theme.alpha(c, 0.12f));
            g.fill(Theme.round(0, by, w, bh, 10));
            g.setColor(Theme.alpha(c, 0.32f));
            g.setStroke(new BasicStroke(1f));
            g.draw(Theme.round(0.5f, by + 0.5f, w - 1, bh - 1, 10));
            g.setColor(c);
            g.fill(new java.awt.geom.Ellipse2D.Float(12, by + 12, 16, 16));
            if (bannerOk) Icons.check(g, 20, by + 20, 14, new Color(0x06210e), 1.8f);
            else Icons.cross(g, 20, by + 20, 14, Color.WHITE, 1.8f);
            g.setFont(Theme.ui(12.5f, false));
            FontMetrics bf = g.getFontMetrics();
            List<String> lines = Theme.wrap(bf, bannerText, w - 48, 2);
            float ly = by + 20 + (bf.getAscent() - bf.getDescent()) / 2f;
            g.setColor(Theme.mix(c, Color.WHITE, 0.55f));
            for (String line : lines) {
                g.drawString(line, 36, ly);
                ly += 19;
            }
        }
        g.dispose();
    }
}
