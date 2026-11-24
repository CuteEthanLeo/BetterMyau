package myau.injector.ui;

import myau.injector.InjectionSession;
import myau.injector.TargetDescriptor;
import myau.injector.TargetScanner;

import javax.swing.*;
import java.awt.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 注入器主窗口：左栏游戏进程卡片（每 2 秒自动检测），右栏注入步骤 + 主按钮，底部详细日志。
 * 扫描与注入都在后台线程（SwingWorker），界面只在 EDT 上改。
 */
public final class InjectorWindow extends GlassFrame {
    private static final String IDLE_TEXT = "注入 BetterMyau";

    private final Set<String> injected = new HashSet<>();
    private final TargetList list = new TargetList();
    private final StepsPanel steps = new StepsPanel();
    private final PrimaryButton button = new PrimaryButton(IDLE_TEXT);
    private final Hint hint = new Hint();
    private final LogDrawer log = new LogDrawer();
    /** 每个进程的识别结论有变化时写一行日志（在扫描线程上回调，切回 EDT 再写）。 */
    private final TargetScanner scanner = new TargetScanner(msg -> SwingUtilities.invokeLater(() -> log.append(msg)));
    private final ListHeader listHeader = new ListHeader();
    private final IconButton refresh;
    private final Timer poller = new Timer(2000, e -> scan(false));

    private boolean busy, scanning, manualScan, scanErrorLogged;
    private String busyPid, resultPid;

    public InjectorWindow() {
        super("BetterMyau 热注入", version(), 900, 620);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        // 关闭前先停自动检测；注入进行中直接退出也安全（Agent 在游戏里独立跑完，只是回报连不上）。
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                poller.stop();
            }
        });
        refresh =new IconButton(30, false, "重新扫描", (g, cx, cy, s, c) ->
            Icons.refresh(g, cx, cy, s, c, manualScan ? Anim.seconds() * 7 : 0)).onClick(() -> scan(true));
        build();

        list.onSelect(t -> {
            if (!busy && (t == null || !t.pid.equals(resultPid))) {
                steps.reset();
                resultPid = null;
            }
            updateControls();
        });
        list.onActivate(this::inject);
        button.onClick(() -> inject(list.selected()));

        log.append("BetterMyau 热注入 " + version() + " 已启动，正在查找游戏进程 …");
        log.append("识别诊断写在 " + TargetScanner.scanLogPath() + "，游戏开着却识别不到时请把它发给作者。");
        updateControls();
    }

    /** 显示窗口后开始扫描（窗口先出来，不让第一次 attach 扫描拖慢启动）。 */
    public void start() {
        setVisible(true);
        scan(true);
        poller.start();
    }

    private static String version() {
        String v = InjectorWindow.class.getPackage() == null ? null
            : InjectorWindow.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : "v" + v;
    }

    // ───────────────────────── 布局 ─────────────────────────

    private void build() {
        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        // 左栏：标题行 + 卡片列表
        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setOpaque(false);
        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        headerRow.add(listHeader, BorderLayout.CENTER);
        headerRow.add(refresh, BorderLayout.EAST);
        left.add(headerRow, BorderLayout.NORTH);
        left.add(list.inScroll(), BorderLayout.CENTER);

        // 右栏：玻璃卡片里放步骤、按钮、提示
        GlassPanel right = new GlassPanel(null, 16);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setBorder(BorderFactory.createEmptyBorder(18, 18, 16, 18));
        JLabel title = new JLabel("注入进度");
        title.setFont(Theme.ui(13.5f, true));
        title.setForeground(Theme.MUTED);
        title.setAlignmentX(LEFT_ALIGNMENT);
        steps.setAlignmentX(LEFT_ALIGNMENT);
        steps.setMaximumSize(new Dimension(Integer.MAX_VALUE, steps.getPreferredSize().height));
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        right.add(title);
        right.add(Box.createVerticalStrut(14));
        right.add(steps);
        right.add(Box.createVerticalGlue());
        right.add(button);
        right.add(Box.createVerticalStrut(6));
        right.add(hint);

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        c.gridx = 0;
        c.weightx = 0.6;
        c.insets = new Insets(0, 0, 0, 16);
        content.add(left, c);
        c.gridx = 1;
        c.weightx = 0.4;
        c.insets = new Insets(0, 0, 0, 0);
        content.add(right, c);
        // 两栏宽度按 weight 分，不受各自首选宽度影响
        left.setPreferredSize(new Dimension(10, 10));
        right.setPreferredSize(new Dimension(10, 10));

        body().add(content, BorderLayout.CENTER);
        body().add(log, BorderLayout.SOUTH);
    }

    // ───────────────────────── 扫描 ─────────────────────────

    private void scan(boolean full) {
        if (scanning || busy) return;
        scanning = true;
        if (full) {
            manualScan = true;
            Anim.startContinuous(refresh);
        }
        new SwingWorker<List<TargetDescriptor>, Void>() {
            @Override protected List<TargetDescriptor> doInBackground() {
                return full ? scanner.scan() : scanner.poll();
            }
            @Override protected void done() {
                try {
                    List<TargetDescriptor> found = get();
                    int before = listHeader.count;
                    list.setTargets(found);
                    listHeader.setCount(found.size());
                    if (found.size() != before || full) {
                        long ok = found.stream().filter(t -> t.compatible && !injected.contains(t.pid)).count();
                        if (full || found.size() > before) {
                            log.append(found.isEmpty() ? "还没发现 Forge 1.8.9 游戏进程，启动后会自动出现。"
                                : "发现 " + found.size() + " 个进程，其中 " + ok + " 个可以注入。");
                        }
                    }
                    scanErrorLogged = false;
                } catch (Exception e) {
                    if (!scanErrorLogged) log.append("扫描进程失败：" + rootMessage(e));
                    scanErrorLogged = true;
                } finally {
                    scanning = false;
                    if (manualScan) {
                        manualScan = false;
                        Anim.stopContinuous(refresh);
                        refresh.repaint();
                    }
                    listHeader.flash();
                    updateControls();
                }
            }
        }.execute();
    }

    // ───────────────────────── 注入 ─────────────────────────

    private void inject(TargetDescriptor target) {
        if (busy || target == null || !target.compatible || injected.contains(target.pid)) return;
        busy = true;
        busyPid = target.pid;
        resultPid = target.pid;
        poller.stop();
        steps.enter(0);
        log.append("────────────");
        log.append("开始注入 PID " + target.pid + "（" + target.kind + "）");
        updateControls();

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                InjectionSession session = new InjectionSession(
                    msg -> SwingUtilities.invokeLater(() -> log.append(msg)),
                    stage -> SwingUtilities.invokeLater(() -> onStage(stage)));
                return session.inject(target);
            }
            @Override protected void done() {
                String error;
                try {
                    error = get();
                } catch (Exception e) {
                    error = "注入出错：" + rootMessage(e);
                }
                if (error == null) {
                    injected.add(target.pid);
                    steps.complete("BetterMyau 已在游戏里启动，切回游戏就能用了。");
                    log.append("✓ 注入成功，BetterMyau 已在游戏里启动。");
                } else {
                    steps.fail(firstLine(error));
                    log.append("✗ " + error);
                    log.expand();
                }
                busy = false;
                busyPid = null;
                updateControls();
                poller.start();
            }
        }.execute();
    }

    private void onStage(InjectionSession.Stage stage) {
        if (!busy) return;
        switch (stage) {
            case PREPARE -> steps.enter(0);
            case ATTACH -> steps.enter(1);
            case LOAD_AGENT -> steps.enter(2);
            case WAIT_AGENT -> steps.enter(3);
            case DONE -> { }   // 由 done() 统一收尾
        }
    }

    private void updateControls() {
        list.setMarks(injected, busyPid);
        TargetDescriptor t = list.selected();
        if (busy) {
            button.setEnabled(true);
            button.setBusy(true);
            button.setText("正在注入 …");
            hint.set("游戏可能会短暂卡顿一下，属正常现象", Theme.FAINT);
            return;
        }
        button.setBusy(false);
        if (t == null) {
            button.setText(IDLE_TEXT);
            button.setEnabled(false);
            hint.set("等待游戏启动 …", Theme.FAINT);
        } else if (injected.contains(t.pid)) {
            button.setText("已注入");
            button.setEnabled(false);
            hint.set("BetterMyau 已在这个游戏里运行", Theme.ACCENT);
        } else if (!t.compatible) {
            button.setText("无法注入");
            button.setEnabled(false);
            hint.set(t.status, Theme.mix(Theme.BAD, Color.WHITE, 0.25f));
        } else {
            button.setText(IDLE_TEXT);
            button.setEnabled(true);
            hint.set("注入后无法卸载，换版本需重启游戏", Theme.FAINT);
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    // ───────────────────────── 小组件 ─────────────────────────

    /** 左栏标题：「游戏进程」+ 自动检测指示点（每次扫描闪一下）+ 数量。 */
    private static final class ListHeader extends JComponent {
        private final Anim pulse = new Anim(this, 0, 3.5f);
        int count = -1;

        ListHeader() {
            setOpaque(false);
            setPreferredSize(new Dimension(200, 30));
        }

        void setCount(int n) {
            count = n;
            repaint();
        }

        void flash() {
            pulse.snap(1);
            pulse.to(0);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.hq(g0);
            float cy = getHeight() / 2f;
            g.setFont(Theme.ui(13.5f, true));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Theme.MUTED);
            g.drawString("游戏进程", 2, cy + (fm.getAscent() - fm.getDescent()) / 2f);
            float x = 2 + fm.stringWidth("游戏进程") + 12;

            float p = pulse.get();
            g.setColor(Theme.alpha(Theme.ACCENT, 0.18f * p));
            g.fill(new java.awt.geom.Ellipse2D.Float(x - 4 * p, cy - 3 - 4 * p, 6 + 8 * p, 6 + 8 * p));
            g.setColor(Theme.mix(Theme.alpha(Theme.ACCENT, 0.75f), Theme.ACCENT, p));
            g.fill(new java.awt.geom.Ellipse2D.Float(x, cy - 3, 6, 6));
            x += 12;

            g.setFont(Theme.ui(12f, false));
            FontMetrics sm = g.getFontMetrics();
            g.setColor(Theme.FAINT);
            String text = count < 0 ? "正在查找 …" : count == 0 ? "自动检测中" : "自动检测中 · " + count + " 个";
            g.drawString(text, x, cy + (sm.getAscent() - sm.getDescent()) / 2f);
            g.dispose();
        }
    }

    /** 按钮下面一行说明文字（居中、放不下就截断，完整内容在提示框里）。 */
    private static final class Hint extends JComponent {
        private String text = "";
        private Color color = Theme.FAINT;

        Hint() {
            setOpaque(false);
            setPreferredSize(new Dimension(200, 20));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        }

        void set(String text, Color color) {
            this.text = text == null ? "" : text;
            this.color = color;
            setToolTipText(this.text.isEmpty() ? null : this.text);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.hq(g0);
            g.setFont(Theme.ui(11.5f, false));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(color);
            float w = getWidth();
            float tw = Math.min(fm.stringWidth(text), w);
            Theme.textFit(g, text, (w - tw) / 2f, getHeight() / 2f + (fm.getAscent() - fm.getDescent()) / 2f, w);
            g.dispose();
        }
    }
}
