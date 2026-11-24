package myau.injector.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;

/**
 * 主操作按钮：青绿渐变圆角、悬停外发光、按下微缩；忙碌时显示加载圈 + 文字，禁用时变成暗色玻璃。
 */
public final class PrimaryButton extends JComponent {
    private final Anim hover = new Anim(this, 0, 12);
    private final Anim press = new Anim(this, 0, 22);
    private final Anim live = new Anim(this, 1, 9);   // 1 = 可点（青绿），0 = 禁用（暗色）
    private String text;
    private boolean busy;
    private Runnable action;

    public PrimaryButton(String text) {
        this.text = text;
        setOpaque(false);
        setFocusable(true);
        setPreferredSize(new Dimension(220, 48));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover.to(1); }
            @Override public void mouseExited(MouseEvent e) { hover.to(0); press.to(0); }
            @Override public void mousePressed(MouseEvent e) { if (clickable()) press.to(1); }
            @Override public void mouseReleased(MouseEvent e) {
                press.to(0);
                if (contains(e.getPoint()) && clickable() && SwingUtilities.isLeftMouseButton(e)) fire();
            }
        });
    }

    public void onClick(Runnable action) {
        this.action = action;
    }

    public void fire() {
        if (clickable() && action != null) action.run();
    }

    private boolean clickable() {
        return isEnabled() && !busy;
    }

    public void setText(String text) {
        this.text = text;
        repaint();
    }

    public void setBusy(boolean busy) {
        if (this.busy == busy) return;
        this.busy = busy;
        if (busy) Anim.startContinuous(this); else Anim.stopContinuous(this);
        setCursor(Cursor.getPredefinedCursor(clickable() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        live.to(enabled || busy ? 1 : 0);
        setCursor(Cursor.getPredefinedCursor(clickable() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.hq(g0);
        float w = getWidth(), h = getHeight();
        float lv = live.get(), hv = clickable() ? hover.get() : 0, pr = press.get();
        float pad = 6;  // 给外发光留的位置
        float bx = pad, by = pad, bw = w - pad * 2, bh = h - pad * 2, r = 12;

        // 按下时以中心微缩
        float scale = 1f - 0.025f * pr;
        AffineTransform old = g.getTransform();
        g.translate(w / 2.0, h / 2.0);
        g.scale(scale, scale);
        g.translate(-w / 2.0, -h / 2.0);

        // 外发光
        if (lv > 0.01f) {
            float glow = lv * (0.16f + 0.22f * hv);
            for (int i = 1; i <= 6; i++) {
                float a = glow * (1f - i / 7f) * 0.35f;
                g.setColor(Theme.alpha(Theme.ACCENT, a));
                g.setStroke(new BasicStroke(i * 1.6f));
                g.draw(Theme.round(bx, by, bw, bh, r));
            }
        }

        // 底色：可点 = 青绿渐变；禁用 = 暗色玻璃
        Color dimTop = new Color(0x2a, 0x2f, 0x39), dimBottom = new Color(0x20, 0x24, 0x2c);
        Color accTop = Theme.mix(Theme.ACCENT, Color.WHITE, 0.10f + 0.10f * hv);
        Color accBottom = Theme.mix(Theme.ACCENT, new Color(0x0c, 0x6e, 0x5c), 0.42f - 0.12f * hv);
        g.setPaint(new GradientPaint(0, by, Theme.mix(dimTop, accTop, lv), 0, by + bh, Theme.mix(dimBottom, accBottom, lv)));
        g.fill(Theme.round(bx, by, bw, bh, r));
        // 顶部高光 + 描边
        g.setPaint(new GradientPaint(0, by, Theme.alpha(Color.WHITE, 0.10f + 0.20f * lv), 0, by + bh, Theme.alpha(Color.WHITE, 0.02f)));
        g.setStroke(new BasicStroke(1f));
        g.draw(Theme.round(bx + 0.5f, by + 0.5f, bw - 1, bh - 1, r));

        // 文字（忙碌时前面带加载圈）
        Color fg = Theme.mix(Theme.FAINT, Theme.ON_ACCENT, lv);
        g.setFont(Theme.ui(14.5f, true));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        float spin = busy ? 16 : 0, gap = busy ? 9 : 0;
        float total = tw + spin + gap;
        float tx = w / 2f - total / 2f;
        float ty = h / 2f + (fm.getAscent() - fm.getDescent()) / 2f;
        if (busy) Icons.spinner(g, tx + spin / 2f, h / 2f, spin, fg, 2f);
        g.setColor(fg);
        g.drawString(text, tx + spin + gap, ty);

        g.setTransform(old);
        g.dispose();
    }
}
