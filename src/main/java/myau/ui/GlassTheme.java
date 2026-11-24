package myau.ui;

import myau.Myau;
import myau.module.modules.HUD;
import myau.ui.glass.Anim;
import myau.ui.glass.Draw;
import myau.ui.glass.GlassBlur;
import myau.ui.glass.GuiPalette;
import myau.ui.glass.ScreenTransition;
import myau.ui.glass.SmoothFont;
import net.minecraft.client.Minecraft;

/**
 * The one set of design constants and composite surfaces every BetterMyau screen and HUD
 * element uses: frosted panels, inner cards, input fields, the accent colour.
 */
public final class GlassTheme {
    private GlassTheme() { }

    // Corner radii (units)
    public static final float R_PANEL = 12, R_CARD = 8, R_CONTROL = 5;
    // Text
    public static final int TEXT = 0xfff3f4f7, MUTED = 0xffa7adba, FAINT = 0xff767d8b;
    // Status
    public static final int OK = 0xff34c759, BAD = 0xffff453a, WARN = 0xffffcc00;
    // Glass tint over the blurred backdrop (top, bottom) and its opaque fallback
    private static final int TINT_TOP = 0x6a23272f, TINT_BOTTOM = 0x8615181d;
    private static final int SOLID_TOP = 0xf023272f, SOLID_BOTTOM = 0xf415181d;

    /** Accent colour, ARGB, following the HUD module's colour settings. */
    public static int accent() {
        return accent(0);
    }

    public static int accent(long offset) {
        // The colour only moves with time, so each offset is worked out once per frame.
        int slot = offset >= 0 && offset < ACCENT_SLOTS ? (int) offset : -1;
        if (slot >= 0 && accentFrame[slot] == frame) return accentCache[slot];
        int value = computeAccent(offset);
        if (slot >= 0) {
            accentCache[slot] = value;
            accentFrame[slot] = frame;
        }
        return value;
    }

    /**
     * The accent for the menu screens' marks (bars, underlines, hover fills): the HUD colour, or a
     * teal when that colour is nearly grey (the default white would leave every mark colourless).
     */
    public static int vividAccent() {
        int accent = accent();
        int r = accent >> 16 & 255, g = accent >> 8 & 255, b = accent & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        boolean grey = max < 40 || (max - min) / (float) Math.max(1, max) < 0.15f;
        return grey ? 0xff3ed6b5 : accent | 0xff000000;
    }

    /** Rendered frames so far; changes once per frame. */
    public static int frame() {
        return frame;
    }

    private static final int ACCENT_SLOTS = 128;
    private static final int[] accentCache = new int[ACCENT_SLOTS], accentFrame = new int[ACCENT_SLOTS];
    private static int frame = 1;

    private static int computeAccent(long offset) {
        try {
            if (Myau.moduleManager != null) {
                HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
                if (hud != null) return 0xff000000 | hud.getColor(System.currentTimeMillis(), offset).getRGB();
            }
        } catch (RuntimeException ignored) {
        }
        return 0xff4c9bff;
    }

    // Per-frame copies of the HUD settings, so drawing a panel does not look anything up.
    private static boolean liquid = true, better = true;
    private static float liquidStrength = 0.6f, liquidClarity = 0.7f;
    // How much of a blurred, dimmed backdrop a screen has put over the scene this frame (0 in the HUD).
    private static float backdrop;
    // Whether the panel drawn last used Better glass.
    private static boolean lastBetter;

    private static HUD hud() {
        try {
            return Myau.moduleManager == null ? null : (HUD) Myau.moduleManager.modules.get(HUD.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Once per rendered frame, before anything is drawn: advance the clock, read the settings. */
    public static void beginFrame() {
        frame++;
        framesStarted = true;
        Draw.resetBatch();
        myau.util.TeamUtil.beginRenderFrame();
        boolean animations = true, glass = true, betterGlass = true;
        float speed = 1, strength = 0.6f, clarity = 0.7f;
        HUD hud = hud();
        if (hud != null) {
            animations = hud.animations.getValue();
            speed = hud.animationSpeed.getValue();
            glass = hud.liquidGlass.getValue();
            betterGlass = hud.betterLiquidGlass.getValue();
            strength = hud.liquidStrength.getValue() / 100f;
            clarity = hud.liquidClarity.getValue() / 100f;
            myau.ui.glass.BootScreen.remember(hud.bootScreen.getValue());
            myau.util.BorderlessWindow.remember(hud.borderlessFullscreen.getValue());
        }
        liquid = glass;
        better = glass && betterGlass;
        liquidStrength = strength;
        liquidClarity = Draw.clamp01(clarity);
        backdrop = 0;
        // Its program compiles here, between frames, and not under the first panel that wants it.
        if (better) Draw.betterSupported();
        myau.ui.glass.Anim.beginFrame(speed, animations);
        myau.ui.glass.GlassLight.frame();
        myau.ui.glass.VisualCheck.frame();
        myau.ui.glass.Defer.run();
        myau.guide.GuideState.frame();
    }

    /** Frosted glass panel: drop shadow, blurred backdrop, tint, top highlight and rim light. */
    public static void panel(float x, float y, float w, float h, float r) {
        panel(x, y, w, h, r, 1);
    }

    /** @param lift 0..2, how far the panel floats above the background (shadow strength) */
    public static void panel(float x, float y, float w, float h, float r, float lift) {
        panel(x, y, w, h, r, lift, GuiPalette.GLASS);
    }

    /** A panel in one of the click GUI's colour themes. */
    public static void panel(float x, float y, float w, float h, float r, float lift, GuiPalette p) {
        if (w <= 0 || h <= 0) return;
        lastBetter = false;
        // Better glass shows the scene itself at its edge, so its captures are taken before this
        // panel's own shadows are on screen (the blur alone hid a shadow well enough).
        boolean useBetter = better && p.blur && Draw.betterReady();
        if (lift > 0 && p.shadow > 0.01f) {
            Draw.shadow(x, y + 4 + 3 * lift, w, h, r, 14 + 10 * lift, Draw.alpha(0, Math.min(0.62f, 0.34f * lift + 0.1f) * p.shadow));
            Draw.shadow(x, y + 1, w, h, r, 4, Draw.fade(0x30000000, p.shadow));
        }
        if (p.blur) {
            if (useBetter && betterPanel(x, y, w, h, r, p)) {
                lastBetter = true;
                if (p.hairline != 0) Draw.outline(x, y, w, h, r, 0.6f, p.hairline, p.hairline);
                return;
            }
            if (liquid && liquidPanel(x, y, w, h, r, p)) {
                if (p.hairline != 0) Draw.outline(x, y, w, h, r, 0.6f, p.hairline, p.hairline);
                return;
            }
            boolean blur = Draw.blurred(x, y, w, h, r, 1);
            Draw.gradientV(x, y, w, h, r, blur ? p.tintTop : p.solidTop, blur ? p.tintBottom : p.solidBottom);
        } else {
            // Opaque themes never ask for the blurred screen, so it is not rendered at all.
            Draw.gradientV(x, y, w, h, r, p.solidTop, p.solidBottom);
        }
        if (p.sheen >>> 24 != 0) Draw.gradientV(x, y, w, h, r, p.sheen, p.sheen & 0xffffff);
        Draw.outline(x, y, w, h, r, 0.75f, p.rimTop, p.rimBottom);
        if (p.hairline != 0) Draw.outline(x, y, w, h, r, 0.6f, p.hairline, p.hairline);
    }

    /**
     * The liquid-glass version of a panel body: the edge bends the backdrop like a lens and
     * catches light from the top left. Small capsules get a cheaper, subtler lens; anything
     * tiny keeps the plain frosted look.
     *
     * @return false when it was not drawn and the frosted fallback should be used
     */
    private static boolean liquidPanel(float x, float y, float w, float h, float r, GuiPalette p) {
        float shortSide = Math.min(w, h);
        if (shortSide < 14) return false;
        float k = liquidStrength;
        // A lighter tint lets the refraction show; text stays readable through the inner shadow.
        int top = Draw.alpha(p.tintTop, p.liquidTop - 0.10f * k), bottom = Draw.alpha(p.tintBottom, p.liquidBottom - 0.09f * k);
        float saturation = p.saturation + 0.13f * k;
        if (h <= 22) {
            return Draw.glass(x, y, w, h, r, r, r, r, top, bottom, 5 * k, Math.min(h * 0.32f, 5), 0,
                    saturation, p.rimMain, p.rimSecond, p.innerShadow, 1.5f);
        }
        float bevel = Math.max(6, Math.min(14, shortSide * 0.10f));
        return Draw.glass(x, y, w, h, r, r, r, r, top, bottom, 10 * k, bevel, 0.12f * k,
                saturation, p.rimMain, p.rimSecond, p.innerShadow, 1.5f);
    }

    /**
     * The Better version of a panel body. Every value that depends on size is continuous in the
     * short side: the island alone moves between 22 and 44 units, and a threshold would make its
     * edge jump in the middle of that. Larger panels get a wider, thicker edge with more lensing.
     *
     * @return false when it was not drawn and the next material should be tried
     */
    private static boolean betterPanel(float x, float y, float w, float h, float r, GuiPalette p) {
        float shortSide = Math.min(w, h), k = liquidStrength;
        // The clarity slider: the palette holds the default (70%); below it the glass turns into a
        // bright light frost, above it nearly clear. Both halves meet at the default, so dragging
        // the slider never jumps.
        float frost = clarityFrost(), clear = clarityClear();
        float tintGain = 1 + 0.6f * frost - 0.7f * clear;
        int top = Draw.alpha(p.tintTop, Math.max(0, (p.betterTop - 0.03f * k) * tintGain));
        int bottom = Draw.alpha(p.tintBottom, Math.max(0, (p.betterBottom - 0.03f * k) * tintGain));
        float knee = Anim.lerp(Anim.lerp(p.betterKnee, Math.min(p.betterKnee, 0.28f), frost), Math.max(p.betterKnee, 0.6f), clear);
        float cap = Anim.lerp(Anim.lerp(p.betterCap, Math.min(p.betterCap, 0.45f), frost), Math.max(p.betterCap, 0.8f), clear);
        float saturation = p.saturation + 0.13f * k;
        // Behind a screen's backdrop the scene around the panel is blurred already: the glass stays
        // clear there too, only a little softer than over the open world.
        float clarity = Anim.lerp(Anim.lerp(p.betterClarity, 0.15f, frost), 1, clear);
        clarity *= (1 - 0.6f * backdrop) * Anim.lerp(0.6f, 1, smooth(18, 30, shortSide));
        if (shortSide < 14) {
            // Too small for a lens. The same body, so it matches its neighbours, in the frosted outline.
            if (!Draw.glassBetter(x, y, w, h, r, r, r, r, top, bottom, 0, 0, 0, 0, 0, 0, saturation, clarity, 1.5f,
                    p.betterFloor, p.betterFloorKnee, knee, cap, 0, 0, 0, 0)) return false;
            Draw.outline(x, y, w, h, r, 0.75f, p.rimTop, p.rimBottom);
            return true;
        }
        float bevel = Math.min(shortSide * 0.36f, 7 + 7 * smooth(32, 96, shortSide));
        // The whole slab is a weak lens; it grows in with size, like the bevel, and with the strength.
        float zoom = 0.035f * (k / 0.6f) * (1 - 0.5f * frost) * smooth(14, 40, shortSide);
        return Draw.glassBetter(x, y, w, h, r, r, r, r, top, bottom,
                bevel, bevel * 0.6f, 1 + smooth(22, 120, shortSide), k / 0.6f, zoom,
                0.07f * k * smooth(20, 34, shortSide), saturation, clarity, 1.5f,
                p.betterFloor, p.betterFloorKnee, knee, cap,
                p.betterKey, p.betterFill, p.betterEnv, p.betterShadow);
    }

    // The clarity slider in two halves around its default: 1 at 0%, 0 from 70% up.
    private static float clarityFrost() {
        return liquidClarity >= 0.7f ? 0 : (0.7f - liquidClarity) / 0.7f;
    }

    // 0 up to 70%, 1 at 100%.
    private static float clarityClear() {
        return liquidClarity <= 0.7f ? 0 : (liquidClarity - 0.7f) / 0.3f;
    }

    /**
     * How strong the soft halo under light text is this frame, 0 for none. Clear glass leaves
     * text on whatever is behind it; the halo keeps it readable there, more so the clearer the
     * glass is set.
     */
    public static float inkHalo() {
        // Asked for by every line of text: worked out once per frame once frames are running.
        if (framesStarted && haloFrame == frame) return halo;
        halo = !better || !Draw.betterSupported() ? 0 : Anim.lerp(Anim.lerp(1, 0.5f, clarityFrost()), 1.3f, clarityClear());
        haloFrame = frame;
        return halo;
    }

    private static float halo;
    private static int haloFrame = -1;
    private static boolean framesStarted;

    private static float smooth(float from, float to, float value) {
        float t = Draw.clamp01((value - from) / (to - from));
        return t * t * (3 - 2 * t);
    }

    /** True when the panel drawn last used Better glass, whose body is already kept readable. */
    public static boolean lastPanelBetter() {
        return lastBetter;
    }

    /** A lighter surface inside a panel (rows, list items, key caps). */
    public static void card(float x, float y, float w, float h, float r, float emphasis) {
        card(x, y, w, h, r, emphasis, GuiPalette.GLASS);
    }

    public static void card(float x, float y, float w, float h, float r, float emphasis, GuiPalette p) {
        Draw.rect(x, y, w, h, r, p.wash(0.06f + 0.07f * emphasis));
        Draw.outline(x, y, w, h, r, 0.6f, p.wash(0.12f + 0.1f * emphasis), p.wash(0.04f));
    }

    /** Recessed input field; {@code focus} 0..1 lights the rim with the accent colour. */
    public static void field(float x, float y, float w, float h, float r, float focus) {
        field(x, y, w, h, r, focus, GuiPalette.GLASS);
    }

    public static void field(float x, float y, float w, float h, float r, float focus, GuiPalette p) {
        Draw.rect(x, y, w, h, r, p.field);
        Draw.outline(x, y, w, h, r, 0.75f, p.wash(0x10ffffff), p.wash(0x30ffffff));
        if (focus > 0.01f) {
            int accent = p.accentInk(accent());
            // Added light does not show on a light surface; a coloured shadow does.
            if (p.light) Draw.shadow(x, y, w, h, r, 5, Draw.alpha(accent, 0.3f * focus));
            else Draw.glow(x, y, w, h, r, 6, Draw.alpha(accent, 0.28f * focus));
            Draw.outline(x, y, w, h, r, 0.9f, Draw.alpha(accent, 0.9f * focus), Draw.alpha(accent, 0.55f * focus));
        }
    }

    /** Blur and darken the whole screen behind a dialog. {@code amount} 0..1. */
    public static void dim(float width, float height, float amount) {
        dim(0, 0, width, height, amount);
    }

    /** The click GUI's backdrop in one of its themes; opaque themes darken without blurring. */
    public static void dim(float width, float height, float amount, GuiPalette p) {
        if (amount <= 0) return;
        if (p.backdropBlur) {
            Draw.blurred(0, 0, width, height, 0, 0.72f * amount);
            backdrop = Math.max(backdrop, Draw.clamp01(amount));
        }
        Draw.gradientV(0, 0, width, height, 0, Draw.alpha(p.dimColor, p.dimTop * amount), Draw.alpha(p.dimColor, p.dimBottom * amount));
    }

    private static void dim(float x, float y, float width, float height, float amount) {
        if (amount <= 0) return;
        // Outside a world the menu scene is the point: blur it less and darken it lightly, so the
        // colours stay vivid behind the glass (text sits on its own panels there).
        boolean menu = Minecraft.getMinecraft().theWorld == null;
        backdrop = Math.max(backdrop, Draw.clamp01(amount));
        Draw.blurred(x, y, width, height, 0, (menu ? 0.6f : 0.72f) * amount);
        Draw.gradientV(x, y, width, height, 0, Draw.alpha(0x05070a, (menu ? 0.16f : 0.28f) * amount),
                Draw.alpha(0x05070a, (menu ? 0.36f : 0.52f) * amount));
    }

    /** Outside a world, paint the menu scene so glass has something to blur. */
    private static void scene(int width, int height) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null) return;
        myau.ui.glass.MenuBackdrop.draw(width, height);
    }

    /** Dialog or screen title. */
    public static void title(String text, float centerX, float y) {
        SmoothFont.bold(11.5f).drawCentered(text, centerX, y, TEXT);
    }

    /** Secondary line under a title; section-sign colour codes are honoured. */
    public static void caption(String text, float centerX, float y) {
        SmoothFont.regular(9).drawCentered(text, centerX, y, MUTED);
    }

    /** Small frosted capsule with a message, centred on {@code centerX}. */
    public static void toast(String text, float centerX, float y) {
        SmoothFont font = SmoothFont.regular(9);
        float w = font.width(text) + 18, h = 17;
        panel(centerX - w / 2, y, w, h, h / 2, 0.6f);
        font.drawCentered(text, centerX, font.centerY(y, h) - 0.5f, TEXT);
    }

    /**
     * Status capsule for in-game indicators (block counts, progress, timers), centred on
     * {@code centerX}. Section-sign colour codes in {@code text} are honoured.
     *
     * @param accent   colour of the leading dot and the progress bar
     * @param progress 0..1 draws a thin bar along the bottom; negative draws none
     * @return the capsule height, so callers can stack several
     */
    public static float pill(String text, float centerX, float y, int accent, float progress) {
        SmoothFont font = SmoothFont.regular(9);
        boolean bar = progress >= 0;
        float h = 17, dot = 4, w = Math.max(font.width(text) + 26, bar ? 64 : 0);
        float x = centerX - w / 2;
        panel(x, y, w, h, h / 2, 0.6f);
        float cy = y + h / 2;
        Draw.glow(x + 8 - dot / 2, cy - dot / 2, dot, dot, dot / 2, 5, Draw.alpha(accent, 0.6f));
        Draw.circle(x + 8, cy, dot / 2, accent);
        font.draw(text, x + 15, font.centerY(y, h) - 0.5f, TEXT);
        if (bar) {
            float bx = x + h / 2, bw = w - h, by = y + h - 3.2f;
            Draw.rect(bx, by, bw, 1.4f, 0.7f, 0x30ffffff);
            float fill = bw * Draw.clamp01(progress);
            if (fill > 0.5f) {
                Draw.glow(bx, by, fill, 1.4f, 0.7f, 4, Draw.alpha(accent, 0.45f));
                Draw.rect(bx, by, fill, 1.4f, 0.7f, accent);
            }
        }
        return h;
    }

    /** Background of a vanilla screen: the scene behind it, blurred and dimmed. */
    public static void screenBackdrop(int width, int height) {
        Draw.useGuiScale();
        if (ScreenTransition.inGroup()) {
            // A second backdrop inside a screen that is still scaling in: cover past the edges.
            dim(-width, -height, width * 3, height * 3, Draw.blurStrength());
            return;
        }
        float p = ScreenTransition.backdropBegin();
        if (ScreenTransition.overlayDimmed()) {
            // The previous screen's backdrop is still on screen under this one; reuse it.
            Draw.stageBlur(p);
        } else {
            scene(width, height);
            GlassBlur.invalidate();
            GlassBlur.prime();
            Draw.stageBlur(p);
            dim(width, height, p);
        }
        ScreenTransition.groupBegin(width, height);
    }

    /** Centered dialog card over a blurred screen (input prompts, login dialogs). */
    public static void backdrop(int width, int height) {
        screenBackdrop(width, height);
        int w = Math.min(340, Math.max(0, width - 24));
        int h = Math.min(180, Math.max(0, height - 24));
        if (w > 4 && h > 4) {
            panel((width - w) / 2f, (height - h) / 2f, w, h, R_PANEL, 1.4f);
            ScreenTransition.focus((width - w) / 2f, (height - h) / 2f, w, h, R_PANEL);
        }
    }

    /** Full-screen glass sheet for the account manager. */
    public static void accountBackdrop(int width, int height) {
        screenBackdrop(width, height);
        int margin = 12;
        if (width > margin * 2 && height > margin * 2) {
            panel(margin, margin, width - margin * 2, height - margin * 2, R_PANEL, 1.2f);
            ScreenTransition.focus(margin, margin, width - margin * 2, height - margin * 2, R_PANEL);
        }
    }
}
