package myau.ui.glass;

import myau.Myau;
import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;

/**
 * The one-time opening of the main menu. It starts as the last frame of {@link BootScreen} (black,
 * the name in the middle, a full progress bar), then the bar fades, the name travels up into the
 * menu title and the black lifts off the menu underneath while the startup chord plays.
 *
 * <p>Timed in drawn frames, not wall-clock time. The menu is created well before it is first
 * drawn, and its first frame compiles shaders and rasterises fonts and can take a second; on a
 * wall clock the opening would be over before anyone saw it.
 */
public final class Intro {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float HOLD = 0.35f, TRAVEL = 0.85f, HANDOVER = 0.28f, END = HOLD + TRAVEL + HANDOVER;
    /** The menu's own entrance starts this long after the opening does. */
    private static final float MENU_DELAY = 0.7f;

    private static final Anim.Clock clock = new Anim.Clock();
    private static boolean pending = true, running;
    private static float time;
    private static int frames;

    private Intro() {
    }

    /** Every frame the main menu draws; the first one starts the opening. */
    public static void frame() {
        if (running) {
            // The clock caps a stalled frame at 50 ms, so a slow start cannot skip ahead.
            float dt = clock.tick();
            if (++frames == 2) UiSound.BOOT.play();
            if (frames >= 2) time += dt;
            return;
        }
        if (!pending) return;
        pending = false;
        boolean wanted = !Anim.reduced();
        try {
            HUD hud = Myau.moduleManager == null ? null : (HUD) Myau.moduleManager.modules.get(HUD.class);
            if (hud != null && !hud.intro.getValue()) wanted = false;
        } catch (RuntimeException ignored) {
        }
        if (!wanted) return;
        running = true;
        time = 0;
        frames = 0;
        clock.reset();
    }

    public static boolean running() {
        return running;
    }

    private static float seconds() {
        return time;
    }

    /** Time on the menu's own entrance clock: negative until its turn comes. */
    public static float menuSeconds() {
        return seconds() - MENU_DELAY;
    }

    /** Opacity of the menu's real title: it takes over from the travelling name at the very end. */
    public static float titleAlpha() {
        return running ? Anim.clamp01((seconds() - HOLD - TRAVEL) / HANDOVER) : 1;
    }

    /**
     * Drawn after everything else on the menu.
     *
     * @param titleCenterY where the menu title's line is centred, in units
     * @param titleSize    the menu title's font size, in units
     */
    public static void overlay(float width, float height, float titleCenterY, float titleSize) {
        overlay(width, height, width / 2, titleCenterY, titleSize);
    }

    /**
     * Like {@link #overlay(float, float, float, float)} for a title that is not centred: the name
     * travels to ({@code titleCenterX}, {@code titleCenterY}), both in GUI units.
     */
    public static void overlay(float width, float height, float titleCenterX, float titleCenterY, float titleSize) {
        if (!running) return;
        float t = seconds();
        if (t >= END) {
            running = false;
            return;
        }
        Draw.useGuiScale();
        GlStateManager.disableDepth();
        float scale = Draw.scale();
        int windowHeight = mc.displayHeight;
        float travel = Anim.inOutCubic((t - HOLD) / TRAVEL);

        // The black lifts a little ahead of the name arriving.
        float black = 1 - Anim.outCubic((t - HOLD) / (TRAVEL * 0.8f));
        if (black > 0.004f) Draw.rect(-2, -2, width + 4, height + 4, 0, Draw.alpha(0x000000, black));

        // What is left of the loading bar.
        float bar = 1 - Anim.clamp01(t / 0.25f);
        if (bar > 0.01f) {
            float bw = BootScreen.barWidth(windowHeight) / scale, bh = BootScreen.barHeight(windowHeight) / scale;
            Draw.rect(width / 2 - bw / 2, BootScreen.barTop(windowHeight) / scale, bw, bh, bh / 2, Draw.alpha(0xffffff, bar));
        }

        // The name, at the size it was loaded at, shrinking into the title's place.
        float bootSize = BootScreen.logoPixels(windowHeight) / scale;
        SmoothFont font = SmoothFont.bold(bootSize);
        float cy = Anim.lerp(height * BootScreen.LOGO_Y, titleCenterY, travel);
        float size = Anim.lerp(1, titleSize / bootSize, travel);
        float fade = 1 - Anim.clamp01((t - HOLD - TRAVEL) / HANDOVER);
        float cx = Anim.lerp(width / 2, titleCenterX, travel);
        Draw.pushTransform(cx, cy, size, 0, 0);
        Draw.pushAlpha(fade);
        font.drawCentered(BootScreen.NAME, cx, cy - font.height() / 2, Draw.mix(0xffffffff, GlassTheme.TEXT, travel));
        Draw.popAlpha();
        Draw.popTransform();

        GlStateManager.enableDepth();
        GlStateManager.color(1, 1, 1, 1);
    }
}
