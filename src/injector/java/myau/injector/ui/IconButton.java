package myau.injector.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** 小号图标按钮（窗口按钮、刷新、复制）：悬停出现圆角底色；danger 悬停时底色变红。 */
public class IconButton extends JComponent {
    public interface Painter {
        void paint(Graphics2D g, float cx, float cy, float s, Color color);
    }

    private final Painter painter;
    private final boolean danger;
    private final Anim hover = new Anim(this, 0, 14);
    private final Anim press = new Anim(this, 0, 20);
    private Runnable action;

    public IconButton(int size, boolean danger, String tooltip, Painter painter) {
        this.painter = painter;
        this.danger = danger;
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(getPreferredSize());
        setMaximumSize(getPreferredSize());
        setOpaque(false);
        setToolTipText(tooltip);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover.to(1); }
            @Override public void mouseExited(MouseEvent e) { hover.to(0); press.to(0); }
            @Override public void mousePressed(MouseEvent e) { if (isEnabled()) press.to(1); }
            @Override public void mouseReleased(MouseEvent e) {
                boolean inside = contains(e.getPoint());
                press.to(0);
                if (inside && isEnabled() && action != null && SwingUtilities.isLeftMouseButton(e)) action.run();
            }
        };
        addMouseListener(mouse);
    }

    public IconButton onClick(Runnable action) {
        this.action = action;
        return this;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.hq(g0);
        float w = getWidth(), h = getHeight();
        float hv = isEnabled() ? hover.get() : 0;
        if (hv > 0.01f) {
            Color bg = danger ? Theme.alpha(new Color(0xe5, 0x3a, 0x30), 0.85f * hv)
                : Theme.alpha(Color.WHITE, (0.07f + 0.05f * press.get()) * hv);
            g.setColor(bg);
            g.fill(Theme.round(1, 1, w - 2, h - 2, Math.min(w, h) * 0.28f));
        }
        Color fg = !isEnabled() ? Theme.alpha(Theme.FAINT, 0.6f)
            : danger ? Theme.mix(Theme.MUTED, Color.WHITE, hv) : Theme.mix(Theme.MUTED, Theme.TEXT, hv);
        painter.paint(g, w / 2f, h / 2f, Math.min(w, h) * 0.62f, fg);
        g.dispose();
    }
}
