package myau.ui.glass;

import myau.ui.GlassTextField;
import myau.ui.GlassTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The frame shared by the full-page glass screens (main menu, world list, account manager): the
 * "design unit" measurement, the pointer in units, a hit table filled while drawing, hover and
 * press feel per control, the wheel, and the common controls (buttons, chips, search boxes, the
 * account chip). It is a helper object, not a base class, because those screens each extend a
 * different vanilla screen.
 * <p>
 * Two ways to fit the window:
 * <ul>
 *   <li>{@link #zoom(float, float)}: units follow the GUI scale, shrinking when that leaves less
 *       than {@code minW x minH} (lists, panels). Drawn under {@code pushTransform(zoom)}, which
 *       stays at or below 1 so text is never magnified.</li>
 *   <li>{@link #scale(float)}: the window is always {@code designH} units tall (the main menu),
 *       drawn with {@code Draw.setScale(ui)} so text is rasterised at its final size.</li>
 * </ul>
 * Per frame: {@link #begin()}, then the backdrop, then {@link #push()} .. draw .. {@link #pop()}.
 */
public final class DesignKit {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private final boolean heightBased;
    private final float minW, minH, designH;

    /** Pixels per unit; GUI units per unit; window size and pointer in units. */
    public float ui = 2, zoom = 1, sw = 760, sh = 420, mx, my;
    /** Seconds since {@link #restart()}. */
    public float openTime;

    public static final class Hit {
        public float x0, y0, x1, y1;
        public Runnable action;
        public int tag;
    }

    private final Map<String, Interaction> feels = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private int hitCount;
    private final float[] clipped = new float[4];
    private final float[] cover = new float[4], coverNext = new float[4];
    private int wheel;

    private DesignKit(boolean heightBased, float minW, float minH, float designH) {
        this.heightBased = heightBased;
        this.minW = minW;
        this.minH = minH;
        this.designH = designH;
    }

    /** Units follow the GUI scale, shrinking when the window has less than minW x minH of them. */
    public static DesignKit zoom(float minW, float minH) {
        return new DesignKit(false, minW, minH, 0);
    }

    /** The window is always about {@code designH} units tall (never magnified below 1 px per unit). */
    public static DesignKit scale(float designH) {
        return new DesignKit(true, 0, 0, designH);
    }

    public void restart() {
        openTime = 0;
    }

    public void measure() {
        float gui = new ScaledResolution(mc).getScaleFactor();
        if (heightBased) {
            float scale = mc.displayHeight / designH;
            ui = Math.max(1, Math.min(6, scale));
            zoom = ui / gui;
        } else {
            float scale = gui;
            if (mc.displayWidth / scale < minW || mc.displayHeight / scale < minH) {
                scale = Math.min(mc.displayWidth / minW, mc.displayHeight / minH);
                scale = Math.max(0.5f, (float) Math.floor(scale * 4) / 4f);
            }
            ui = scale;
            zoom = ui / gui;
        }
        sw = mc.displayWidth / ui;
        sh = mc.displayHeight / ui;
    }

    /** Start of a frame: advance the clock, measure, read the pointer, clear the hit table. */
    public float begin() {
        float dt = Anim.dt();
        openTime += dt;
        measure();
        mx = Mouse.getX() / ui;
        my = (mc.displayHeight - Mouse.getY() - 1) / ui;
        hitCount = 0;
        System.arraycopy(coverNext, 0, cover, 0, 4);
        coverNext[2] = 0;
        return dt;
    }

    /**
     * Switch drawing to design units. {@code Draw} emits geometry in whatever units the GL matrix
     * is in (GUI units); its scale only says how many pixels one unit covers (anti-aliasing, glyph
     * rasterising, clipping). So a height-based screen scales the matrix by {@link #zoom} itself,
     * outside Draw's transform stack, and tells Draw the real pixels per unit: text is rasterised at
     * its final size instead of being magnified.
     */
    public void push() {
        if (heightBased) {
            GlStateManager.pushMatrix();
            GlStateManager.scale(zoom, zoom, 1);
            Draw.setScale(ui);
        } else {
            Draw.pushTransform(0, 0, zoom, 0, 0);
        }
    }

    /** Back to GUI units. */
    public void pop() {
        if (heightBased) {
            Draw.useGuiScale();
            GlStateManager.popMatrix();
        } else {
            Draw.popTransform();
        }
    }

    // ---------------------------------------------------------------- pointer and hits

    public boolean interactive() {
        return openTime > 0.12f;
    }

    /** A mouse event's position in units. */
    public float eventX() {
        return Mouse.getEventX() / ui;
    }

    public float eventY() {
        return (mc.displayHeight - Mouse.getEventY() - 1) / ui;
    }

    /** GUI coordinates (as vanilla passes them) to units. */
    public float fromGui(float v) {
        return v / zoom;
    }

    public void hit(float x, float y, float w, float h, Runnable action) {
        hit(x, y, w, h, action, 0);
    }

    /** Registers a clickable area (clipped to the current clip) for the next press. */
    public void hit(float x, float y, float w, float h, Runnable action, int tag) {
        clipped[0] = x;
        clipped[1] = y;
        clipped[2] = x + w;
        clipped[3] = y + h;
        if (!Draw.clipTo(clipped)) return;
        Hit hit;
        if (hitCount < hits.size()) {
            hit = hits.get(hitCount);
        } else {
            hit = new Hit();
            hits.add(hit);
        }
        hitCount++;
        hit.x0 = clipped[0];
        hit.y0 = clipped[1];
        hit.x1 = clipped[2];
        hit.y1 = clipped[3];
        hit.action = action;
        hit.tag = tag;
    }

    /** The topmost area under (x, y), or null. */
    public Hit hitAt(float x, float y) {
        for (int i = hitCount - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (x >= hit.x0 && x < hit.x1 && y >= hit.y0 && y < hit.y1) return hit;
        }
        return null;
    }

    /** Runs the topmost area's action under (x, y); false when there was none. */
    public boolean click(float x, float y) {
        Hit hit = hitAt(x, y);
        if (hit == null || hit.action == null) return false;
        hit.action.run();
        return true;
    }

    /** An overlay (dropdown, popup) drawn this frame: controls under it stop reacting next frame. */
    public void cover(float x, float y, float w, float h) {
        coverNext[0] = x;
        coverNext[1] = y;
        coverNext[2] = w;
        coverNext[3] = h;
    }

    public boolean covered(float x, float y) {
        return cover[2] > 0 && inside(x, y, cover[0], cover[1], cover[2], cover[3]);
    }

    /** The pointer is over the area, inside the clip and not under an overlay. */
    public boolean over(float x, float y, float w, float h) {
        return !covered(mx, my) && overTop(x, y, w, h);
    }

    /** Like {@link #over} but ignoring overlays (for the overlay itself). */
    public boolean overTop(float x, float y, float w, float h) {
        return interactive() && inside(mx, my, x, y, w, h) && Draw.insideClip(mx, my);
    }

    public static boolean inside(float x, float y, float bx, float by, float bw, float bh) {
        return x >= bx && x < bx + bw && y >= by && y < by + bh;
    }

    public void addWheel(int delta) {
        wheel += delta;
    }

    /** Wheel notches since the last call (positive = away from the user). */
    public float takeWheel() {
        float notches = wheel / 120f;
        wheel = 0;
        return notches;
    }

    public Interaction feel(String id) {
        Interaction feel = feels.get(id);
        if (feel == null) {
            feel = new Interaction();
            feels.put(id, feel);
        }
        return feel;
    }

    // ---------------------------------------------------------------- controls

    public static final int PLAIN = 0, ACCENT = 1, ARMED = 2, DANGER = 3;

    /**
     * A glass button.
     *
     * @param style {@link #PLAIN}, {@link #ACCENT}, {@link #ARMED} (red fill) or {@link #DANGER} (red label)
     */
    public void button(String id, float x, float y, float w, float h, String label, int style, boolean enabled, Runnable action) {
        Interaction feel = feel(id);
        boolean over = enabled && over(x, y, w, h);
        feel.update(over, over && Mouse.isButtonDown(0), Anim.dt());
        float r = Math.min(8, h / 2), hot = feel.hot;
        int accent = style == ARMED ? GlassTheme.BAD : GlassTheme.accent();
        Draw.pushTransform(x + w / 2, y + h / 2, feel.scale(0.04f), 0, 0);
        Draw.pushAlpha(enabled ? 1 : 0.45f);
        if (style == ACCENT || style == ARMED) {
            Draw.glow(x, y, w, h, r, 8, Draw.alpha(accent, 0.18f + 0.22f * hot));
            Draw.gradientV(x, y, w, h, r, Draw.mix(accent, 0xffffffff, 0.12f + 0.1f * hot), Draw.mix(accent, 0xff000000, 0.12f));
            Draw.outline(x, y, w, h, r, 0.75f, 0x50ffffff, 0x10ffffff);
        } else {
            Draw.rect(x, y, w, h, r, Draw.alpha(0x0c0e14, 0.30f + 0.12f * hot));
            GlassTheme.card(x, y, w, h, r, 0.25f + 0.75f * hot);
            if (hot > 0.01f) Draw.outline(x, y, w, h, r, 0.75f, Draw.alpha(accent, 0.7f * hot), Draw.alpha(accent, 0.3f * hot));
        }
        if (hot > 0.01f) Draw.spot(x, y, w, h, r, mx, my, Math.max(40, w * 0.5f), Draw.alpha(0xffffffff, 0.12f * hot));
        SmoothFont font = style == ACCENT || style == ARMED ? SmoothFont.bold(9) : SmoothFont.regular(9);
        int color = style == DANGER ? Draw.mix(0xffff8a80, 0xffffffff, 0.3f * hot)
                : style == PLAIN ? Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, 0.6f + 0.4f * hot) : ink(accent);
        font.drawCentered(font.trim(label, w - 8), x + w / 2, font.centerY(y, h) - 0.5f, color);
        Draw.popAlpha();
        Draw.popTransform();
        if (enabled) hit(x, y, w, h, () -> {
            feel.pulse();
            UiSound.CLICK.play();
            action.run();
        });
    }

    /** A small tinted capsule with a label; returns where the next one starts. */
    public static float chip(String text, float x, float y, int color) {
        SmoothFont font = SmoothFont.regular(7.5f);
        float w = font.width(text) + 12, h = 14;
        Draw.rect(x, y, w, h, h / 2, Draw.alpha(color, 0.16f));
        Draw.outline(x, y, w, h, h / 2, 0.6f, Draw.alpha(color, 0.45f), Draw.alpha(color, 0.2f));
        font.drawCentered(text, x + w / 2, font.centerY(y, h) - 0.4f, Draw.mix(color, 0xffffffff, 0.35f));
        return x + w + 5;
    }

    public static float chipWidth(String text) {
        return SmoothFont.regular(7.5f).width(text) + 12;
    }

    /** Text colour on an accent fill: dark on bright accents (yellow, lime), white otherwise. */
    public static int ink(int fill) {
        float r = (fill >> 16 & 255) / 255f, g = (fill >> 8 & 255) / 255f, b = (fill & 255) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b > 0.6f ? 0xff14171d : 0xffffffff;
    }

    public static void magnifier(float cx, float cy, int color) {
        Draw.outline(cx - 4, cy - 4, 6.4f, 6.4f, 3.2f, 1.2f, color, color);
        Draw.polyline(1.3f, color, cx + 1.7f, cy + 1.7f, cx + 4.2f, cy + 4.2f);
    }

    /** A rounded search box around {@code field} (which sits at x + 22): icon, placeholder, text. */
    public void searchBox(float x, float y, float w, float h, GlassTextField field, String placeholder, float focus) {
        GlassTheme.field(x, y, w, h, h / 2, focus);
        magnifier(x + 12, y + h / 2, Draw.mix(GlassTheme.FAINT, GlassTheme.TEXT, focus));
        hit(x, y, w, h, null);
        if (field.getText().isEmpty() && !field.isFocused()) {
            SmoothFont font = SmoothFont.regular(9);
            font.draw(placeholder, x + 22, font.centerY(y, h) - 0.5f, GlassTheme.FAINT);
        }
        field.drawTextBox();
    }

    /** Focus a text field from a click at {@code x} (units), placing the caret there. */
    public static void focusField(GlassTextField field, boolean focus, float x) {
        if (field == null) return;
        if (!focus) {
            field.setFocused(false);
            return;
        }
        int clampedX = Math.round(Math.max(field.xPosition, Math.min(field.xPosition + field.getWidth() - 5, x)));
        field.mouseClicked(clampedX, field.yPosition, 0);
        field.setFocused(true);
    }

    /** Width {@link #accountChip} needs for the signed-in name. */
    public static float accountChipWidth() {
        return Math.min(170, SmoothFont.regular(8.5f).width(mc.getSession().getUsername()) + 36);
    }

    /** The signed-in account as a capsule (face and name); {@code onClick} may be null. */
    public void accountChip(String id, float x, float y, float w, float h, Runnable onClick) {
        Interaction feel = feel(id);
        boolean over = onClick != null && over(x, y, w, h);
        feel.update(over, over && Mouse.isButtonDown(0), Anim.dt());
        int accent = GlassTheme.accent();
        Draw.pushTransform(x + w / 2, y + h / 2, feel.scale(0.04f), 0, 0);
        GlassTheme.card(x, y, w, h, h / 2, 0.3f + 0.7f * feel.hot);
        if (feel.hot > 0.01f) Draw.outline(x, y, w, h, h / 2, 0.8f, Draw.alpha(accent, 0.7f * feel.hot), Draw.alpha(accent, 0.3f * feel.hot));
        float head = Math.min(15, h - 6), hx = x + (h - head) / 2 + 0.5f, hy = y + (h - head) / 2;
        Faces.draw(hx, hy, head, head / 2, Faces.session());
        SmoothFont name = SmoothFont.regular(8.5f);
        name.draw(name.trim(mc.getSession().getUsername(), w - head - 18), hx + head + 6, name.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        Draw.popTransform();
        if (onClick != null) hit(x, y, w, h, () -> {
            feel.pulse();
            UiSound.CLICK.play();
            onClick.run();
        });
    }

    /** A turning arc, for anything in progress. */
    public static void spinner(float cx, float cy, float radius, float width, int color) {
        double t = System.nanoTime() / 1e9;
        double start = t * Math.PI * 2 * 0.9;
        double sweep = Math.PI * (0.9 + 0.5 * Math.sin(t * 2.2));
        int n = 18;
        float[] pts = new float[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            double a = start + sweep * i / n;
            pts[i * 2] = cx + (float) Math.cos(a) * radius;
            pts[i * 2 + 1] = cy + (float) Math.sin(a) * radius;
        }
        Draw.outline(cx - radius, cy - radius, radius * 2, radius * 2, radius, width, Draw.alpha(color, 0.15f), Draw.alpha(color, 0.15f));
        Draw.polyline(width, color, pts);
    }

    /** "3 分钟前"-style relative time. */
    public static String ago(long millis) {
        long d = System.currentTimeMillis() - millis;
        if (millis <= 0) return "从未";
        if (d < 60_000L) return "刚刚";
        if (d < 3_600_000L) return d / 60_000L + " 分钟前";
        if (d < 86_400_000L) return d / 3_600_000L + " 小时前";
        if (d < 30L * 86_400_000L) return d / 86_400_000L + " 天前";
        if (d < 365L * 86_400_000L) return d / (30L * 86_400_000L) + " 个月前";
        return d / (365L * 86_400_000L) + " 年前";
    }
}
