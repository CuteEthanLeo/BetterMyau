package myau.util;

import myau.mixin.IAccessorMinecraft;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import java.io.File;

/**
 * Fullscreen as a borderless window the size of the monitor (HUD "borderless-fullscreen"),
 * instead of LWJGL's exclusive mode: switching windows is instant, the input method's
 * candidate list shows on top of the game, and the desktop resolution is never changed.
 *
 * <p>LWJGL 2 reads {@code org.lwjgl.opengl.Window.undecorated} whenever it creates the window,
 * and it creates the window again on every display mode change, so the style is switched by
 * setting the property and changing the mode. The game may start fullscreen before the
 * configuration is read, so the choice is also kept as a marker file (like the boot screen).
 */
public final class BorderlessWindow {
    private static final Logger LOGGER = LogManager.getLogger("BetterMyau/Window");
    private static final String UNDECORATED = "org.lwjgl.opengl.Window.undecorated";
    private static final File OFF_MARKER = new File("./config/Myau/", "borderless-off");

    private static Boolean rememberedOn;
    private static boolean active, reapply;
    /** Where the window was before it covered the monitor; -1: centre it. */
    private static int savedX = -1, savedY = -1;

    private BorderlessWindow() {
    }

    /** The setting as stored for the next start (marker file, or {@code -Dmyau.borderless=false}). */
    public static boolean rememberedOn() {
        if (rememberedOn == null) {
            boolean on = !"false".equalsIgnoreCase(System.getProperty("myau.borderless"));
            try {
                if (OFF_MARKER.exists()) on = false;
            } catch (RuntimeException ignored) {
            }
            rememberedOn = on;
        }
        return rememberedOn;
    }

    /** True while the window is the borderless fullscreen one. */
    public static boolean active() {
        return active;
    }

    /**
     * The HUD setting, every frame: kept for the next start, and when it changes while the game
     * is fullscreen the next tick switches to the other kind of fullscreen.
     */
    public static void remember(boolean on) {
        if (rememberedOn != null && rememberedOn == on) return;
        boolean known = rememberedOn != null;
        rememberedOn = on;
        try {
            if (on) {
                if (OFF_MARKER.exists()) OFF_MARKER.delete();
            } else if (!OFF_MARKER.exists()) {
                File parent = OFF_MARKER.getParentFile();
                if (parent.isDirectory() || parent.mkdirs()) OFF_MARKER.createNewFile();
            }
        } catch (Exception ignored) {
            // Only means the previous choice is used at the next start.
        }
        if (known) reapply = true;
    }

    /** Start of a client tick: carry out a switch the setting asked for. Not during rendering. */
    public static void tick(Minecraft mc) {
        if (!reapply) return;
        reapply = false;
        if (!mc.isFullScreen() || active == rememberedOn()) return;
        // Out of the current kind of fullscreen and into the other one.
        mc.toggleFullscreen();
        mc.toggleFullscreen();
    }

    /**
     * The game starts fullscreen and the setting is on: open the window borderless on the
     * primary monitor (there is no window yet to ask which monitor it is on). Replaces vanilla
     * {@code setInitialDisplayMode}.
     */
    public static void startFullscreen(Minecraft mc) throws LWJGLException {
        DisplayMode desktop = Display.getDesktopDisplayMode();
        int w = Math.max(1, desktop.getWidth()), h = Math.max(1, desktop.getHeight());
        System.setProperty(UNDECORATED, "true");
        Display.setDisplayMode(new DisplayMode(w, h));
        Display.setLocation(0, 0);
        mc.displayWidth = w;
        mc.displayHeight = h;
        savedX = savedY = -1;
        active = true;
        LOGGER.info("Starting in borderless fullscreen, {}x{}", w, h);
    }

    /** Replaces vanilla {@code toggleFullscreen} while this kind of fullscreen is involved. */
    public static void toggle(Minecraft mc, boolean enter) {
        IAccessorMinecraft access = (IAccessorMinecraft) mc;
        try {
            int w, h;
            if (enter) {
                int[] bounds = GameWindow.monitorBounds();
                if (bounds == null) {
                    DisplayMode desktop = Display.getDesktopDisplayMode();
                    bounds = new int[]{0, 0, desktop.getWidth(), desktop.getHeight()};
                }
                w = Math.max(1, bounds[2] - bounds[0]);
                h = Math.max(1, bounds[3] - bounds[1]);
                savedX = Display.getX();
                savedY = Display.getY();
                System.setProperty(UNDECORATED, "true");
                // While still decorated: a popup window made resizable would grow a frame again.
                Display.setResizable(false);
                Display.setLocation(bounds[0], bounds[1]);
                setMode(w, h);
                Display.setLocation(bounds[0], bounds[1]);
                active = true;
            } else {
                w = Math.max(1, access.getTempDisplayWidth());
                h = Math.max(1, access.getTempDisplayHeight());
                System.setProperty(UNDECORATED, "false");
                Display.setLocation(savedX, savedY);
                setMode(w, h);
                Display.setResizable(true);
                if (savedX != -1 || savedY != -1) Display.setLocation(savedX, savedY);
                active = false;
            }
            access.setFullscreenFlag(enter);
            mc.gameSettings.fullScreen = enter;
            mc.displayWidth = w;
            mc.displayHeight = h;
            if (mc.currentScreen != null) mc.resize(w, h);
            else access.invokeUpdateFramebufferSize();
            Display.setVSyncEnabled(mc.gameSettings.enableVsync);
            GameWindow.invalidate();
            mc.updateDisplay();
            LOGGER.info("Borderless fullscreen {} ({}x{})", enter ? "on" : "off", w, h);
        } catch (Exception e) {
            LOGGER.error("Couldn't toggle borderless fullscreen", e);
        }
    }

    /**
     * Set the window size, making sure LWJGL creates the window again (it only does so when the
     * mode differs from the current one, and the new style needs a new window).
     */
    private static void setMode(int w, int h) throws LWJGLException {
        DisplayMode current = Display.getDisplayMode();
        if (current.getWidth() == w && current.getHeight() == h) {
            Display.setDisplayMode(new DisplayMode(w, Math.max(1, h - 1)));
        }
        Display.setDisplayMode(new DisplayMode(w, h));
    }
}
