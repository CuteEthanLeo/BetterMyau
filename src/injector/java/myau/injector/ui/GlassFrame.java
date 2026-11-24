package myau.injector.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 无边框玻璃窗口：透明底上自己画柔和投影 + 圆角窗体；窗体背景是预渲染的深色极光（和游戏主菜单同色系）；
 * 顶部是自绘标题栏（Logo、字标、版本、最小化 / 关闭），拖动标题栏移动窗口。
 * 内容放进 {@link #body()}。
 */
public class GlassFrame extends JFrame {
    /** 投影占用的外边距（窗口比可见窗体大这么多）。 */
    static final int SHADOW = 18;
    static final float RADIUS = 16;
    static final int TITLE_HEIGHT = 54;

    private final JPanel body = new JPanel(new BorderLayout());

    public GlassFrame(String title, String version, int width, int height) {
        super(title);
        setUndecorated(true);
        setBackground(new Color(0, 0, 0, 0));
        setIconImages(Theme.windowIcons());

        Chrome chrome = new Chrome();
        chrome.setLayout(new BorderLayout());
        chrome.setBorder(BorderFactory.createEmptyBorder(SHADOW, SHADOW, SHADOW, SHADOW));
        setContentPane(chrome);

        JPanel inner = new JPanel(new BorderLayout());
        inner.setOpaque(false);
        inner.add(new TitleBar(version), BorderLayout.NORTH);
        body.setOpaque(false);
        inner.add(body, BorderLayout.CENTER);
        chrome.add(inner, BorderLayout.CENTER);

        setSize(width + SHADOW * 2, height + SHADOW * 2);
        setResizable(false);
        setLocationRelativeTo(null);
    }

    public JPanel body() {
        return body;
    }

    // ───────────────────────── 窗体：投影 + 极光背景 ─────────────────────────

    private final class Chrome extends JPanel {
        private BufferedImage cache;

        Chrome() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            int w = getWidth(), h = getHeight();
            if (cache == null || cache.getWidth() != w || cache.getHeight() != h) cache = render(w, h);
            Graphics2D g = (Graphics2D) g0.create();
            g.setComposite(AlphaComposite.Src);   // 透明窗口：整块覆盖，避免和上一帧叠加
            g.drawImage(cache, 0, 0, null);
            g.dispose();
        }

        /** 投影和背景只跟尺寸有关，渲染一次缓存起来。 */
        private BufferedImage render(int w, int h) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = Theme.hq(img.getGraphics());
            float x = SHADOW, y = SHADOW, bw = w - SHADOW * 2f, bh = h - SHADOW * 2f;

            // 投影：一圈圈逐渐变淡的圆角描边，近似高斯模糊；下方略重。
            for (int i = SHADOW; i >= 1; i--) {
                float t = 1f - i / (float) SHADOW;
                g.setColor(new Color(0, 0, 0, Math.round(255 * 0.055f * t * t)));
                g.fill(Theme.round(x - i, y - i + 3, bw + i * 2, bh + i * 2, RADIUS + i));
            }

            RoundRectangle2D.Float shape = Theme.round(x, y, bw, bh, RADIUS);
            g.setComposite(AlphaComposite.Src);
            g.setColor(Theme.BG_TOP);
            g.fill(shape);
            g.setComposite(AlphaComposite.SrcOver);
            g.clip(shape);

            g.setPaint(new GradientPaint(0, y, Theme.BG_TOP, 0, y + bh, Theme.BG_BOTTOM));
            g.fill(shape);
            // 极光光斑：低饱和的青绿 / 蓝，大半径径向渐变，叠出磨砂玻璃后面的柔光。
            blob(g, x + bw * 0.12f, y - bh * 0.10f, bw * 0.62f, new Color(0x3ed6b5), 0.20f);
            blob(g, x + bw * 0.98f, y + bh * 0.22f, bw * 0.48f, new Color(0x4a74f5), 0.14f);
            blob(g, x + bw * 0.62f, y + bh * 1.08f, bw * 0.58f, new Color(0x2fb59d), 0.10f);
            blob(g, x + bw * 0.30f, y + bh * 0.85f, bw * 0.30f, new Color(0x7a5cf0), 0.05f);
            // 顶部标题栏区域略微提亮，和内容区分开
            g.setPaint(new GradientPaint(0, y, Theme.alpha(Color.WHITE, 0.035f), 0, y + TITLE_HEIGHT, Theme.alpha(Color.WHITE, 0.01f)));
            g.fill(new Rectangle.Float(x, y, bw, TITLE_HEIGHT));
            g.setColor(Theme.alpha(Color.WHITE, 0.06f));
            g.fill(new Rectangle.Float(x, y + TITLE_HEIGHT - 1, bw, 1));
            dither(img, (int) x, (int) y, (int) bw, (int) bh);

            // 窗体描边：上亮下暗
            g.setClip(null);
            g.setStroke(new BasicStroke(1f));
            g.setPaint(new GradientPaint(0, y, Theme.alpha(Color.WHITE, 0.16f), 0, y + bh, Theme.alpha(Color.WHITE, 0.05f)));
            g.draw(Theme.round(x + 0.5f, y + 0.5f, bw - 1, bh - 1, RADIUS));
            g.dispose();
            return img;
        }

        private void blob(Graphics2D g, float cx, float cy, float radius, Color c, float strength) {
            float[] stops = {0f, 0.45f, 1f};
            Color[] colors = {Theme.alpha(c, strength), Theme.alpha(c, strength * 0.35f), Theme.alpha(c, 0f)};
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), radius, stops, colors));
            g.fill(new Rectangle.Float(cx - radius, cy - radius, radius * 2, radius * 2));
        }

        /** 极淡的随机噪点，打散深色渐变的色带。 */
        private void dither(BufferedImage img, int x0, int y0, int w, int h) {
            Random rnd = new Random(7);
            for (int y = y0; y < y0 + h; y++) {
                for (int x = x0; x < x0 + w; x++) {
                    int argb = img.getRGB(x, y);
                    if ((argb >>> 24) == 0) continue;
                    int n = rnd.nextInt(5) - 2;
                    int r = clamp((argb >> 16 & 255) + n), gr = clamp((argb >> 8 & 255) + n), b = clamp((argb & 255) + n);
                    img.setRGB(x, y, (argb & 0xff000000) | r << 16 | gr << 8 | b);
                }
            }
        }

        private int clamp(int v) {
            return Math.max(0, Math.min(255, v));
        }
    }

    // ───────────────────────── 标题栏 ─────────────────────────

    private final class TitleBar extends JPanel {
        private final String version;
        private Point dragFrom;

        TitleBar(String version) {
            this.version = version;
            setOpaque(false);
            setPreferredSize(new Dimension(10, TITLE_HEIGHT));
            setLayout(new FlowLayout(FlowLayout.RIGHT, 6, (TITLE_HEIGHT - 32) / 2));
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

            add(new IconButton(32, false, "最小化", Icons::minimize).onClick(() -> GlassFrame.this.setState(Frame.ICONIFIED)));
            // 必须写 GlassFrame.this：在 TitleBar 里裸写 dispatchEvent 会解析成面板自己的方法，
            // WINDOW_CLOSING 发给 JPanel 直接被丢掉，关闭按钮就点不动（1.9.1 的 bug）。
            add(new IconButton(32, true, "关闭", Icons::close).onClick(() -> GlassFrame.this.dispatchEvent(
                new java.awt.event.WindowEvent(GlassFrame.this, java.awt.event.WindowEvent.WINDOW_CLOSING))));

            MouseAdapter drag = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    dragFrom = e.getLocationOnScreen();
                    Point loc = GlassFrame.this.getLocation();
                    dragFrom.translate(-loc.x, -loc.y);
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragFrom == null) return;
                    Point p = e.getLocationOnScreen();
                    GlassFrame.this.setLocation(p.x - dragFrom.x, p.y - dragFrom.y);
                }
                @Override public void mouseReleased(MouseEvent e) {
                    dragFrom = null;
                }
            };
            addMouseListener(drag);
            addMouseMotionListener(drag);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Theme.hq(g0);
            int cy = TITLE_HEIGHT / 2;
            float x = 18;

            BufferedImage logo = Theme.logo(28);
            if (logo != null) {
                g.drawImage(logo, (int) x, cy - 14, 28, 28, null);
            } else {
                g.setColor(Theme.alpha(Theme.ACCENT, 0.2f));
                g.fill(Theme.round(x, cy - 14, 28, 28, 7));
            }
            x += 28 + 11;

            g.setFont(Theme.brand(16.5f));
            FontMetrics fm = g.getFontMetrics();
            float base = cy + (fm.getAscent() - fm.getDescent()) / 2f;
            g.setColor(Theme.TEXT);
            g.drawString("BetterMyau", x, base);
            x += fm.stringWidth("BetterMyau") + 10;

            // 「Inject」胶囊
            g.setFont(Theme.brand(11.5f));
            FontMetrics pf = g.getFontMetrics();
            float pw = pf.stringWidth("Inject") + 16, ph = 20;
            g.setColor(Theme.alpha(Theme.ACCENT, 0.15f));
            g.fill(Theme.round(x, cy - ph / 2, pw, ph, ph / 2));
            g.setColor(Theme.alpha(Theme.ACCENT, 0.35f));
            g.setStroke(new BasicStroke(1f));
            g.draw(Theme.round(x + 0.5f, cy - ph / 2 + 0.5f, pw - 1, ph - 1, ph / 2 - 0.5f));
            g.setColor(Theme.ACCENT);
            g.drawString("Inject", x + 8, cy + (pf.getAscent() - pf.getDescent()) / 2f);
            x += pw + 12;

            g.setFont(Theme.ui(12f, false));
            FontMetrics vf = g.getFontMetrics();
            g.setColor(Theme.FAINT);
            g.drawString(version + " · Forge 1.8.9", x, cy + (vf.getAscent() - vf.getDescent()) / 2f);
            g.dispose();
        }
    }
}
