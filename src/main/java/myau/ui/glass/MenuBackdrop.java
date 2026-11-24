package myau.ui.glass;

import myau.Myau;
import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import java.awt.Color;

/**
 * The scene behind every screen outside a world (main menu, options, world and server lists),
 * chosen with HUD's {@code menu-background}:
 * <ul>
 *   <li>AURORA: soft lights in the accent colours flowing into each other (a shader);</li>
 *   <li>NIGHT: an original blocky night landscape with stars, clouds and hills (a shader);</li>
 *   <li>PANORAMA: the game's title panorama, slowly turning, blurred and graded
 *       ({@link PanoramaBackdrop});</li>
 *   <li>MINIMAL: near black with two faint drifting lights, like the boot screen it follows;</li>
 *   <li>PLASMA (default): large soft fields of indigo, purple, magenta and orange flowing slowly
 *       (a shader with its own fixed palette).</li>
 * </ul>
 * Changing the style, from wherever (the main menu's switcher, the guide, a command), cross-fades
 * from the old scene to the new one.
 * The shader scenes are full-screen passes in clip space, so a screen transition's scaling never
 * moves them. Anything that cannot be drawn (no shaders) falls back to MINIMAL.
 */
public final class MenuBackdrop {
    public static final int AURORA = 0, NIGHT = 1, PANORAMA = 2, MINIMAL = 3, PLASMA = 4;
    public static final int COUNT = 5;
    private static final String[] LABELS = {"Aurora", "Night", "Panorama", "Minimal", "Plasma"};
    private static final float FADE_SECONDS = 0.6f;
    // LWJGL 2 only has these in ARBImaging; the values are core since OpenGL 1.4.
    private static final int GL_CONSTANT_ALPHA = 0x8003, GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004;
    private static final int TOP = 0xff0b0d11, BOTTOM = 0xff050608;
    private static final long START = System.nanoTime();
    /** Palette used while the accent is (nearly) grey, as with the default white HUD colour. */
    private static final int[] DEFAULT_PALETTE = {0x3f7bff, 0x8a5cff, 0x18c4b4};

    private static final Anim.Smooth mouseX = new Anim.Smooth(0, 2.5f), mouseY = new Anim.Smooth(0, 2.5f);
    private static long lastNanos = System.nanoTime();

    // Cross-fade between styles: the one shown before the last change, and when it changed.
    private static int shown = -1, from = -1;
    private static long changedAt;

    private MenuBackdrop() {
    }

    private static int style() {
        try {
            HUD hud = Myau.moduleManager == null ? null : (HUD) Myau.moduleManager.modules.get(HUD.class);
            int value = hud == null ? PLASMA : hud.menuBackground.getValue();
            return value >= 0 && value < COUNT ? value : PLASMA;
        } catch (RuntimeException e) {
            return PLASMA;
        }
    }

    /** Short English name of a style, for the main menu's switcher. */
    public static String label(int style) {
        return style >= 0 && style < COUNT ? LABELS[style] : LABELS[PLASMA];
    }

    /** The style currently chosen. */
    public static int current() {
        return style();
    }

    /** Steps HUD's menu background forwards or backwards; the scene cross-fades by itself. */
    public static void step(int direction) {
        try {
            HUD hud = Myau.moduleManager == null ? null : (HUD) Myau.moduleManager.modules.get(HUD.class);
            if (hud == null) return;
            int next = ((style() + direction) % COUNT + COUNT) % COUNT;
            hud.menuBackground.setValue(next);
        } catch (RuntimeException e) {
            Myau.LOGGER.warn("[Glass] cannot change the menu background", e);
        }
    }

    /**
     * Once per installation: configs from before PLASMA existed still hold the old default AURORA;
     * move those to PLASMA so the new look shows up. A marker file keeps it from happening again,
     * so choosing AURORA afterwards sticks. Call after the config has loaded.
     */
    public static void migrateDefault() {
        java.io.File marker = new java.io.File("./config/Myau/", "plasma-migrated");
        if (marker.exists()) return;
        try {
            HUD hud = Myau.moduleManager == null ? null : (HUD) Myau.moduleManager.modules.get(HUD.class);
            if (hud == null) return;
            if (hud.menuBackground.getValue() == AURORA) hud.menuBackground.setValue(PLASMA);
            java.io.File dir = marker.getParentFile();
            if (dir != null) dir.mkdirs();
            marker.createNewFile();
        } catch (Exception e) {
            Myau.LOGGER.warn("[Glass] menu background migration skipped", e);
        }
    }

    /** Seconds of animation: wall clock, so the motion is continuous at any frame rate; still when animations are reduced. */
    static double time() {
        return Anim.reduced() ? 40 : (System.nanoTime() - START) / 1_000_000_000.0;
    }

    /** The pointer, -1..1 from the centre, eased so the parallax glides. */
    static float[] pointer() {
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float tx = 0, ty = 0;
        if (mc.displayWidth > 0 && mc.displayHeight > 0 && Mouse.isInsideWindow()) {
            tx = Draw.clamp01(Mouse.getX() / (float) mc.displayWidth) * 2 - 1;
            ty = Draw.clamp01(Mouse.getY() / (float) mc.displayHeight) * 2 - 1;
        }
        return new float[]{mouseX.update(tx, dt), mouseY.update(ty, dt)};
    }

    /** @param width  screen width in units of the current scale
     *  @param height screen height in the same units */
    public static void draw(float width, float height) {
        int style = style();
        long now = System.nanoTime();
        if (shown < 0) {
            shown = style;
        } else if (style != shown) {
            from = shown;
            shown = style;
            changedAt = now;
        }
        float fade = 1;
        if (from >= 0) {
            fade = Anim.reduced() ? 1 : Anim.clamp01((now - changedAt) / 1e9f * Anim.speed() / FADE_SECONDS);
            if (fade >= 1) from = -1;
        }
        if (from < 0) {
            scene(style, width, height, 1);
            return;
        }
        // The panorama ends in an unblended copy, so it can only ever be the layer underneath.
        float p = Anim.inOutCubic(fade);
        if (style == PANORAMA) {
            scene(style, width, height, 1);
            scene(from, width, height, 1 - p);
        } else {
            scene(from, width, height, 1);
            scene(style, width, height, p);
        }
    }

    /** One scene at the given opacity (1 = opaque, replacing whatever is there). */
    private static void scene(int style, float width, float height, float opacity) {
        if (opacity <= 0.003f) return;
        boolean drawn = false;
        try {
            if (style == AURORA) drawn = shader(GlassShaders.aurora(), opacity);
            else if (style == NIGHT) drawn = shader(GlassShaders.night(), opacity);
            else if (style == PLASMA) drawn = shader(GlassShaders.plasma(), opacity);
            else if (style == PANORAMA) drawn = opacity >= 0.999f && PanoramaBackdrop.draw(width, height);
        } catch (RuntimeException e) {
            Myau.LOGGER.error("[Glass] menu background failed", e);
        }
        if (drawn) return;
        if (opacity >= 0.999f) {
            minimal(width, height);
        } else {
            Draw.pushAlpha(opacity);
            minimal(width, height);
            Draw.popAlpha();
        }
    }

    private static void minimal(float width, float height) {
        Draw.gradientV(0, 0, width, height, 0, TOP, BOTTOM);
        double t = Anim.reduced() ? 0 : (System.nanoTime() - START) / 1_000_000_000.0;
        float a = (float) Math.sin(t * Math.PI * 2 / 47), b = (float) Math.cos(t * Math.PI * 2 / 61);
        float reach = Math.max(width, height);
        Draw.spot(0, 0, width, height, 0,
                width * (0.30f + 0.05f * a), height * (0.24f + 0.04f * b),
                reach * 0.62f, Draw.alpha(GlassTheme.accent(), 0.13f));
        Draw.spot(0, 0, width, height, 0,
                width * (0.76f - 0.05f * b), height * (0.82f + 0.04f * a),
                reach * 0.55f, Draw.alpha(0xdfe8ff, 0.05f));
    }

    /** Three colours for the scenes: the accent and its neighbours on the colour wheel. */
    static float[][] palette() {
        int accent = GlassTheme.accent() & 0xffffff;
        float[] hsb = Color.RGBtoHSB(accent >> 16 & 255, accent >> 8 & 255, accent & 255, null);
        int[] rgb = new int[3];
        if (hsb[1] < 0.15f || hsb[2] < 0.15f) {
            rgb = DEFAULT_PALETTE;
        } else {
            float s = Math.max(0.5f, Math.min(0.85f, hsb[1])), v = 0.95f;
            rgb[0] = Color.HSBtoRGB(hsb[0], s, v);
            rgb[1] = Color.HSBtoRGB(hsb[0] + 35 / 360f, s, v);
            rgb[2] = Color.HSBtoRGB(hsb[0] - 35 / 360f, s * 0.9f, v);
        }
        float[][] out = new float[3][3];
        for (int i = 0; i < 3; i++) {
            out[i][0] = (rgb[i] >> 16 & 255) / 255f;
            out[i][1] = (rgb[i] >> 8 & 255) / 255f;
            out[i][2] = (rgb[i] & 255) / 255f;
        }
        return out;
    }

    /**
     * One full-screen pass of a backdrop program over the whole viewport. Below full opacity it is
     * blended with a constant alpha (the programs write alpha 1), which needs no shader changes and
     * is restored with the rest of the colour-buffer state.
     */
    private static boolean shader(GlassShaders.Program program, float opacity) {
        if (program == null || !program.ok) return false;
        Minecraft mc = Minecraft.getMinecraft();
        float[][] pal = palette();
        float[] m = pointer();
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_TEXTURE_BIT);
        try {
            if (opacity >= 0.999f) {
                GL11.glDisable(GL11.GL_BLEND);
            } else {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendColor(0, 0, 0, opacity);
                GL11.glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
            }
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthMask(false);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL20.glUseProgram(program.id);
            GL20.glUniform2f(program.loc("res"), Math.max(1, mc.displayWidth), Math.max(1, mc.displayHeight));
            GL20.glUniform1f(program.loc("time"), (float) (time() % 3600.0));
            GL20.glUniform3f(program.loc("c1"), pal[0][0], pal[0][1], pal[0][2]);
            GL20.glUniform3f(program.loc("c2"), pal[1][0], pal[1][1], pal[1][2]);
            GL20.glUniform3f(program.loc("c3"), pal[2][0], pal[2][1], pal[2][2]);
            GL20.glUniform2f(program.loc("mouse"), m[0], m[1]);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1, -1);
            GL11.glVertex2f(1, -1);
            GL11.glVertex2f(1, 1);
            GL11.glVertex2f(-1, 1);
            GL11.glEnd();
        } finally {
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
        }
        return true;
    }
}
