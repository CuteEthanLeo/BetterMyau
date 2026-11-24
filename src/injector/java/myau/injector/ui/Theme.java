package myau.injector.ui;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注入器的视觉常量，和游戏内玻璃 UI 同一套（{@code GlassTheme} / {@code GuiPalette.GLASS} / {@code MenuBackdrop}）：
 * 深色底、青绿主题色、Apple 风格的状态色、Segoe UI / 微软雅黑 UI。
 */
public final class Theme {
    private Theme() {
    }

    public static final Color BG_TOP = new Color(0x0b0d11);
    public static final Color BG_BOTTOM = new Color(0x050608);
    public static final Color TEXT = new Color(0xf3f4f7);
    public static final Color MUTED = new Color(0xa7adba);
    public static final Color FAINT = new Color(0x767d8b);
    public static final Color ACCENT = new Color(0x3ed6b5);
    public static final Color ON_ACCENT = new Color(0x05261f);
    public static final Color OK = new Color(0x34c759);
    public static final Color BAD = new Color(0xff453a);
    public static final Color WARN = new Color(0xffcc00);
    /** 玻璃卡片染色（上、下），取自 GuiPalette.GLASS 的 tintTop / tintBottom。 */
    public static final Color GLASS_TOP = new Color(0x23, 0x27, 0x2f, 0x6a);
    public static final Color GLASS_BOTTOM = new Color(0x15, 0x18, 0x1d, 0x86);

    // ───────────────────────── 颜色工具 ─────────────────────────

    public static Color alpha(Color c, float a) {
        int v = Math.max(0, Math.min(255, Math.round(a * 255)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), v);
    }

    public static Color mix(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
            Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
            Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
            Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    // ───────────────────────── 字体 ─────────────────────────

    private static final String[] CJK = {"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC"};
    private static final String[] BRAND = {"Segoe UI Semibold", "Segoe UI", "SF Pro Display", "Inter"};
    private static Set<String> families;
    private static final Map<String, Font> fontCache = new HashMap<>();

    /** 界面文字：中文必须用带中文字形的字体（Swing 不会逐字回退，Segoe UI 会把中文画成方框）。 */
    public static Font ui(float size, boolean bold) {
        return font(CJK, bold ? Font.BOLD : Font.PLAIN, size);
    }

    /** 品牌字标 "BetterMyau"：和开机画面同一款 Segoe UI Semibold（纯拉丁字母）。 */
    public static Font brand(float size) {
        return font(BRAND, Font.PLAIN, size);
    }

    private static synchronized Font font(String[] names, int style, float size) {
        String key = names[0] + "|" + style + "|" + size;
        Font cached = fontCache.get(key);
        if (cached != null) return cached;
        if (families == null) {
            families = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        }
        Font font = null;
        for (String name : names) {
            if (families.contains(name)) {
                font = new Font(name, style, 12).deriveFont(size);
                break;
            }
        }
        if (font == null) font = new Font(Font.DIALOG, style, 12).deriveFont(size);
        fontCache.put(key, font);
        return font;
    }

    // ───────────────────────── 绘制 ─────────────────────────

    /** 复制一个开好抗锯齿的 Graphics2D（调用方负责 dispose）。透明窗口上不能用 LCD 次像素文字，用灰度抗锯齿。 */
    public static Graphics2D hq(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        return g2;
    }

    public static RoundRectangle2D.Float round(float x, float y, float w, float h, float r) {
        return new RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2);
    }

    /**
     * 玻璃卡片：上下渐变染色 + 顶部一条柔光 + 上亮下暗的描边（和游戏内 frosted 面板一个画法）。
     * @param lift 0..1，悬停 / 选中时把卡片提亮一点
     */
    public static void glass(Graphics2D g, float x, float y, float w, float h, float r, float lift) {
        RoundRectangle2D.Float shape = round(x, y, w, h, r);
        g.setPaint(new GradientPaint(0, y, mix(GLASS_TOP, alpha(new Color(0x2c3240), 0.62f), lift),
            0, y + h, mix(GLASS_BOTTOM, alpha(new Color(0x1b1f27), 0.70f), lift)));
        g.fill(shape);
        // 顶部柔光
        Shape old = g.getClip();
        g.clip(shape);
        g.setPaint(new GradientPaint(0, y, alpha(Color.WHITE, 0.07f + 0.04f * lift), 0, y + Math.min(h, 40), alpha(Color.WHITE, 0f)));
        g.fill(new Rectangle.Float(x, y, w, Math.min(h, 40)));
        g.setClip(old);
        // 描边
        g.setStroke(new BasicStroke(1f));
        g.setPaint(new GradientPaint(0, y, alpha(Color.WHITE, 0.20f + 0.08f * lift), 0, y + h, alpha(Color.WHITE, 0.05f)));
        g.draw(round(x + 0.5f, y + 0.5f, w - 1, h - 1, r));
    }

    /** 在指定宽度内画一行文字，放不下就截断加省略号。返回实际画出的宽度。 */
    public static float textFit(Graphics2D g, String text, float x, float y, float maxWidth) {
        if (text == null) return 0;
        FontMetrics fm = g.getFontMetrics();
        String s = text;
        if (fm.stringWidth(s) > maxWidth) {
            String ell = "…";
            int end = s.length();
            while (end > 0 && fm.stringWidth(s.substring(0, end) + ell) > maxWidth) end--;
            s = s.substring(0, end) + ell;
        }
        g.drawString(s, x, y);
        return fm.stringWidth(s);
    }

    /** 按宽度把文字折成最多 maxLines 行（中文逐字折行），超出的最后一行加省略号。 */
    public static List<String> wrap(FontMetrics fm, String text, float maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null) return lines;
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                char c = para.charAt(i);
                if (fm.stringWidth(line.toString() + c) > maxWidth && line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                    if (c == ' ') continue;
                }
                line.append(c);
            }
            lines.add(line.toString());
        }
        if (lines.size() > maxLines) {
            List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
            String last = cut.get(maxLines - 1);
            while (!last.isEmpty() && fm.stringWidth(last + "…") > maxWidth) last = last.substring(0, last.length() - 1);
            cut.set(maxLines - 1, last + "…");
            return cut;
        }
        return lines;
    }

    // ───────────────────────── Logo ─────────────────────────

    private static BufferedImage logoSource;
    private static boolean logoLoaded;
    private static final Map<Integer, BufferedImage> logoCache = new HashMap<>();

    /** BetterMyau 图标（TUBIAO），圆角裁剪、高质量缩放到 size 像素；没打进资源时返回 null。 */
    public static synchronized BufferedImage logo(int size) {
        BufferedImage cached = logoCache.get(size);
        if (cached != null) return cached;
        if (!logoLoaded) {
            logoLoaded = true;
            try (InputStream in = Theme.class.getResourceAsStream("/myau/injector/logo.jpg")) {
                if (in != null) logoSource = ImageIO.read(in);
            } catch (Exception ignored) {
            }
        }
        if (logoSource == null) return null;
        BufferedImage scaled = downscale(logoSource, size);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fill(round(0, 0, size, size, size * 0.23f));
        g.setComposite(AlphaComposite.SrcIn);
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        logoCache.put(size, out);
        return out;
    }

    /** 逐次减半的双线性缩放，大图缩小图标也不发虚、不起锯齿。 */
    private static BufferedImage downscale(BufferedImage src, int size) {
        BufferedImage cur = src;
        int w = src.getWidth(), h = src.getHeight();
        while (w / 2 >= size && h / 2 >= size) {
            w /= 2;
            h /= 2;
            cur = resize(cur, w, h);
        }
        return resize(cur, size, size);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 窗口 / 任务栏图标的多种尺寸。 */
    public static List<Image> windowIcons() {
        List<Image> icons = new ArrayList<>();
        for (int s : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) {
            BufferedImage img = logo(s);
            if (img != null) icons.add(img);
        }
        return icons;
    }
}
