package myau.injector.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 底部可折叠的「详细日志」：收起时只显示最后一行，展开时带高度动画；右侧「复制日志」。
 */
public final class LogDrawer extends JPanel {
    private static final int HEADER = 42, CONTENT = 156;

    private final JTextArea area = new JTextArea();
    private final JScrollPane scroll;
    private final Header header = new Header();
    private final Anim open = new Anim(this, 0, 10).onChange(this::revalidateUp);
    private String lastLine = "就绪";

    public LogDrawer() {
        super(new BorderLayout());
        setOpaque(false);

        area.setEditable(false);
        area.setOpaque(false);
        area.setForeground(Theme.MUTED);
        area.setFont(Theme.ui(12f, false));
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setBorder(BorderFactory.createEmptyBorder(2, 22, 10, 18));
        area.setSelectionColor(Theme.alpha(Theme.ACCENT, 0.35f));
        area.setSelectedTextColor(Theme.TEXT);
        area.setCaretColor(Theme.MUTED);

        scroll = new JScrollPane(area, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(8, 10));
        bar.setUI(new BasicScrollBarUI() {
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }
            @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
                if (r.isEmpty()) return;
                Graphics2D g = Theme.hq(g0);
                g.setColor(Theme.alpha(Color.WHITE, 0.16f));
                g.fill(Theme.round(r.x + 2, r.y + 2, r.width - 4, r.height - 4, (r.width - 4) / 2f));
                g.dispose();
            }
            @Override protected JButton createDecreaseButton(int o) { return zero(); }
            @Override protected JButton createIncreaseButton(int o) { return zero(); }
            private JButton zero() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                return b;
            }
        });

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    public void append(String line) {
        area.append(line + "\n");
        area.setCaretPosition(area.getDocument().getLength());
        if (!line.isBlank() && !line.startsWith("──")) lastLine = line;
        header.repaint();
    }

    public void expand() {
        open.to(1);
    }

    public void toggle() {
        open.to(open.target() > 0.5f ? 0 : 1);
    }

    private void revalidateUp() {
        revalidate();
        Container p = getParent();
        if (p != null) {
            p.revalidate();
            p.repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(10, HEADER + Math.round(CONTENT * open.get()));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.hq(g0);
        // 和窗体之间的分隔线 + 展开后的底色
        g.setColor(Theme.alpha(Color.WHITE, 0.06f));
        g.fill(new Rectangle(0, 0, getWidth(), 1));
        float o = open.get();
        if (o > 0.01f) {
            g.setColor(Theme.alpha(Color.BLACK, 0.18f * o));
            g.fill(new Rectangle(0, HEADER, getWidth(), getHeight() - HEADER));
        }
        g.dispose();
    }

    private final class Header extends JPanel {
        private final Anim hover = new Anim(this, 0, 14);

        Header() {
            super(new FlowLayout(FlowLayout.RIGHT, 0, (HEADER - 28) / 2));
            setOpaque(false);
            setPreferredSize(new Dimension(10, HEADER));
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            add(new IconButton(28, false, "复制日志", Icons::copy).onClick(() -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(area.getText()), null);
                append("（日志已复制到剪贴板）");
            }));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover.to(1); }
                @Override public void mouseExited(MouseEvent e) { hover.to(0); }
                @Override public void mouseClicked(MouseEvent e) { toggle(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.hq(g0);
            float cy = HEADER / 2f;
            Color fg = Theme.mix(Theme.MUTED, Theme.TEXT, hover.get());
            Icons.chevron(g, 26, cy, 16, fg, open.get());
            g.setFont(Theme.ui(12.5f, true));
            FontMetrics fm = g.getFontMetrics();
            float base = cy + (fm.getAscent() - fm.getDescent()) / 2f;
            g.setColor(fg);
            g.drawString("详细日志", 40, base);
            float x = 40 + fm.stringWidth("详细日志") + 14;
            g.setFont(Theme.ui(12f, false));
            g.setColor(Theme.FAINT);
            Theme.textFit(g, lastLine, x, base, getWidth() - x - 60);
            g.dispose();
        }
    }
}
