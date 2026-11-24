package myau.injector.ui;

import myau.injector.TargetDescriptor;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 游戏进程列表：每个进程一张玻璃卡片（方块图标、启动器、PID / Java、状态胶囊）。
 * 单击选中、双击或回车注入、上下键切换。没有进程时显示引导文字。
 */
public final class TargetList extends JPanel implements Scrollable {
    private static final int CARD_HEIGHT = 70, GAP = 8;

    private final List<Card> cards = new ArrayList<>();
    private final Map<String, Card> byPid = new HashMap<>();
    private String selectedPid;
    private Consumer<TargetDescriptor> onSelect = t -> { };
    private Consumer<TargetDescriptor> onActivate = t -> { };
    private Set<String> injected = Set.of();
    private String busyPid;

    public TargetList() {
        super(null);
        setOpaque(false);
        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int i = indexOf(selectedPid);
                if (e.getKeyCode() == KeyEvent.VK_DOWN && i + 1 < cards.size()) select(cards.get(i + 1).target.pid, true);
                else if (e.getKeyCode() == KeyEvent.VK_UP && i > 0) select(cards.get(i - 1).target.pid, true);
                else if (e.getKeyCode() == KeyEvent.VK_ENTER && i >= 0) onActivate.accept(cards.get(i).target);
            }
        });
    }

    public void onSelect(Consumer<TargetDescriptor> c) { this.onSelect = c; }
    public void onActivate(Consumer<TargetDescriptor> c) { this.onActivate = c; }

    /** 已注入的 PID（卡片显示「已注入」）与正在注入的 PID（胶囊显示「注入中」）。 */
    public void setMarks(Set<String> injected, String busyPid) {
        this.injected = injected;
        this.busyPid = busyPid;
        repaint();
    }

    public TargetDescriptor selected() {
        Card c = byPid.get(selectedPid);
        return c == null ? null : c.target;
    }

    /** 用新的扫描结果更新卡片：保留已有卡片（动画状态不跳），增删有变化的，选中项尽量保持。 */
    public void setTargets(List<TargetDescriptor> targets) {
        Map<String, Card> old = new HashMap<>(byPid);
        cards.clear();
        byPid.clear();
        removeAll();
        for (TargetDescriptor t : targets) {
            Card c = old.remove(t.pid);
            if (c == null) c = new Card(t); else c.target = t;
            cards.add(c);
            byPid.put(t.pid, c);
            add(c);
        }
        if (selectedPid == null || !byPid.containsKey(selectedPid)) {
            // 自动选中第一个能注入且还没注入的进程，否则第一个。
            String pick = null;
            for (Card c : cards) {
                if (c.target.compatible && !injected.contains(c.target.pid)) { pick = c.target.pid; break; }
            }
            if (pick == null && !cards.isEmpty()) pick = cards.get(0).target.pid;
            select(pick, false);
        } else {
            for (Card c : cards) c.sel.snap(c.target.pid.equals(selectedPid) ? 1 : 0);
            onSelect.accept(selected());
        }
        updateBreathing();
        revalidate();
        repaint();
    }

    private int indexOf(String pid) {
        for (int i = 0; i < cards.size(); i++) if (cards.get(i).target.pid.equals(pid)) return i;
        return -1;
    }

    private void select(String pid, boolean scroll) {
        selectedPid = pid;
        for (Card c : cards) c.sel.to(c.target.pid.equals(pid) ? 1 : 0);
        Card c = byPid.get(pid);
        if (c != null && scroll) c.scrollRectToVisible(new Rectangle(0, 0, c.getWidth(), c.getHeight()));
        onSelect.accept(selected());
    }

    // ───────────────────────── 布局 ─────────────────────────

    @Override
    public void doLayout() {
        int w = getWidth() - 6;   // 右边给细滚动条留一点
        int y = 2;
        for (Card c : cards) {
            c.setBounds(2, y, w - 2, CARD_HEIGHT);
            y += CARD_HEIGHT + GAP;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int h = cards.isEmpty() ? 200 : cards.size() * (CARD_HEIGHT + GAP) + 4;
        return new Dimension(300, h);
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return CARD_HEIGHT * 2; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
    }

    /** 空状态：画在列表区域中间。 */
    @Override
    protected void paintComponent(Graphics g0) {
        if (!cards.isEmpty()) return;
        Graphics2D g = Theme.hq(g0);
        float w = getWidth(), h = getHeight();
        float boxW = Math.min(w - 8, 380), boxH = 168;
        float bx = (w - boxW) / 2, by = Math.max(4, (h - boxH) / 2 - 12);
        g.setColor(Theme.alpha(Color.WHITE, 0.025f));
        g.fill(Theme.round(bx, by, boxW, boxH, 14));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, 0f));
        g.setColor(Theme.alpha(Color.WHITE, 0.10f));
        g.draw(Theme.round(bx + 0.5f, by + 0.5f, boxW - 1, boxH - 1, 14));

        // 呼吸的方块
        float pulse = (float) (0.5 + 0.5 * Math.sin(Anim.seconds() * 2.4));
        Icons.block(g, w / 2, by + 44, 34, Theme.mix(new Color(0x3a4150), Theme.ACCENT, 0.25f + 0.35f * pulse));

        g.setFont(Theme.ui(14f, true));
        FontMetrics fm = g.getFontMetrics();
        String title = "等待 Minecraft 启动";
        g.setColor(Theme.TEXT);
        g.drawString(title, w / 2 - fm.stringWidth(title) / 2f, by + 92);

        g.setFont(Theme.ui(12f, false));
        FontMetrics sm = g.getFontMetrics();
        g.setColor(Theme.FAINT);
        float ly = by + 116;
        for (String line : Theme.wrap(sm, "先正常启动不带 BetterMyau 的 Forge 1.8.9（可带 OptiFine），它会自动出现在这里", boxW - 40, 2)) {
            g.drawString(line, w / 2 - sm.stringWidth(line) / 2f, ly);
            ly += 19;
        }
        g.dispose();
    }

    /** 空状态方块的呼吸：等游戏时窗口可能一直开着，用 20fps 的独立定时器，比 60fps 省得多。 */
    private final Timer breath = new Timer(50, e -> repaint());

    /** 只在空状态时呼吸；有卡片时停掉。 */
    private void updateBreathing() {
        boolean want = cards.isEmpty() && isDisplayable();
        if (want && !breath.isRunning()) breath.start();
        else if (!want && breath.isRunning()) breath.stop();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        updateBreathing();
    }

    @Override
    public void removeNotify() {
        breath.stop();
        super.removeNotify();
    }

    // ───────────────────────── 卡片 ─────────────────────────

    private final class Card extends JComponent {
        TargetDescriptor target;
        final Anim hover = new Anim(this, 0, 14);
        final Anim sel = new Anim(this, 0, 11);
        final Anim appear = new Anim(this, 0, 7);

        Card(TargetDescriptor target) {
            this.target = target;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            ToolTipManager.sharedInstance().registerComponent(this);
            appear.to(1);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover.to(1); }
                @Override public void mouseExited(MouseEvent e) { hover.to(0); }
                @Override public void mousePressed(MouseEvent e) {
                    TargetList.this.requestFocusInWindow();
                    select(Card.this.target.pid, false);
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) onActivate.accept(Card.this.target);
                }
            });
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            return target.compatible ? null : target.status;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.hq(g0);
            float w = getWidth(), h = getHeight();
            float hv = hover.get(), sv = sel.get(), ap = appear.get();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, ap))));
            g.translate(0, (1 - ap) * 8);

            boolean isInjected = injected.contains(target.pid);
            boolean isBusy = target.pid.equals(busyPid);
            boolean ok = target.compatible && !isInjected;

            // 选中：主题色外发光 + 描边
            if (sv > 0.01f) {
                for (int i = 1; i <= 4; i++) {
                    g.setColor(Theme.alpha(Theme.ACCENT, 0.10f * sv * (1 - i / 5f)));
                    g.setStroke(new BasicStroke(i * 1.8f));
                    g.draw(Theme.round(1, 1, w - 2, h - 2, 12));
                }
            }
            Theme.glass(g, 1, 1, w - 2, h - 2, 12, Math.max(hv * 0.6f, sv * 0.9f));
            if (sv > 0.01f) {
                g.setStroke(new BasicStroke(1.3f));
                g.setColor(Theme.alpha(Theme.ACCENT, 0.85f * sv));
                g.draw(Theme.round(1.5f, 1.5f, w - 3, h - 3, 11.5f));
            }

            // 方块图标底座
            float ix = 14, isz = 42, iy = (h - isz) / 2;
            Color tile = ok || isInjected ? Theme.ACCENT : new Color(0x5b6170);
            g.setColor(Theme.alpha(tile, ok || isInjected ? 0.13f : 0.10f));
            g.fill(Theme.round(ix, iy, isz, isz, 11));
            Icons.block(g, ix + isz / 2, iy + isz / 2, 24, ok || isInjected ? Theme.ACCENT : new Color(0x6c7282));

            // 状态胶囊
            String chip;
            Color chipColor;
            if (isBusy) { chip = "注入中"; chipColor = Theme.ACCENT; }
            else if (isInjected) { chip = "已注入"; chipColor = Theme.ACCENT; }
            else if (target.compatible) { chip = "可注入"; chipColor = Theme.OK; }
            else { chip = "不可注入"; chipColor = Theme.BAD; }
            g.setFont(Theme.ui(11.5f, true));
            FontMetrics cf = g.getFontMetrics();
            float cw = cf.stringWidth(chip) + 20, ch = 22;
            float cx = w - 14 - cw, cyy = h / 2 - ch / 2;
            g.setColor(Theme.alpha(chipColor, 0.14f));
            g.fill(Theme.round(cx, cyy, cw, ch, ch / 2));
            g.setColor(Theme.alpha(chipColor, 0.30f));
            g.setStroke(new BasicStroke(1f));
            g.draw(Theme.round(cx + 0.5f, cyy + 0.5f, cw - 1, ch - 1, ch / 2 - 0.5f));
            g.setColor(Theme.mix(chipColor, Color.WHITE, 0.12f));
            g.drawString(chip, cx + 10, cyy + ch / 2 + (cf.getAscent() - cf.getDescent()) / 2f);

            // 文字
            float tx = ix + isz + 14, maxW = cx - 12 - tx;
            g.setFont(Theme.ui(14.5f, true));
            g.setColor(ok || isInjected ? Theme.TEXT : Theme.MUTED);
            Theme.textFit(g, target.kind, tx, h / 2 - 3, maxW);
            g.setFont(Theme.ui(12f, false));
            g.setColor(Theme.FAINT);
            String sub = target.compatible || isInjected
                ? "PID " + target.pid + "  ·  Java " + target.javaVersion
                : "PID " + target.pid + "  ·  " + target.status;
            Theme.textFit(g, sub, tx, h / 2 + 17, maxW);
            g.dispose();
        }
    }

    // ───────────────────────── 细滚动条 ─────────────────────────

    /** 把列表放进一个透明、带细滚动条的滚动面板。 */
    public JScrollPane inScroll() {
        JScrollPane sp = new JScrollPane(this, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setViewportBorder(null);
        JScrollBar bar = sp.getVerticalScrollBar();
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(6, 10));
        bar.setUnitIncrement(24);
        bar.setUI(new BasicScrollBarUI() {
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }
            @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
                if (r.isEmpty()) return;
                Graphics2D g = Theme.hq(g0);
                g.setColor(Theme.alpha(Color.WHITE, isDragging ? 0.30f : 0.16f));
                g.fill(Theme.round(r.x + 1, r.y + 2, r.width - 2, r.height - 4, (r.width - 2) / 2f));
                g.dispose();
            }
            @Override protected JButton createDecreaseButton(int o) { return zero(); }
            @Override protected JButton createIncreaseButton(int o) { return zero(); }
            private JButton zero() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                b.setMinimumSize(new Dimension(0, 0));
                b.setMaximumSize(new Dimension(0, 0));
                return b;
            }
        });
        return sp;
    }
}
