package myau.ui.glass;

import net.minecraftforge.fml.common.ProgressManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Iterator;

/**
 * The loading screen: the name in white on black with one thin progress bar, drawn over
 * Forge's own splash from inside its render thread.
 *
 * <p>Everything here is raw OpenGL 1.1 and Java2D. It runs on Forge's splash thread, where
 * {@code GlStateManager}'s cache belongs to another thread and neither Minecraft's fonts nor
 * the glass shaders exist yet, so none of {@link Draw}, {@link SmoothFont} or
 * {@code GlStateManager} may be used. Every GL state it touches is read first and put back.
 * Any failure switches it off for the session and Forge's splash shows as usual.
 */
public final class BootScreen {
    public static final String NAME = "BetterMyau";
    /** Centre of the name, as a fraction of the window height. */
    public static final float LOGO_Y = 0.46f;
    // The same order SmoothFont uses for its bold face, so the main menu can pick the name up exactly.
    private static final String[] SEMIBOLD = {"Segoe UI Semibold", "SF Pro Text Semibold", "Inter SemiBold"};
    private static final String[] REGULAR = {"Segoe UI", "SF Pro Text", "Helvetica Neue", "Inter", "Noto Sans"};
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private static final File OFF_MARKER = new File("./config/Myau/", "boot-screen-off");

    private static volatile boolean failed;
    private static Boolean enabled;
    private static boolean remembered = true, rememberedKnown;

    private static int logoTexture = -1, capTexture = -1, builtHeight = -1;
    private static int logoWidth, logoHeight, logoSize, capSize, capTextureSize, logoTextureWidth, logoTextureHeight;
    private static long firstFrame, lastFrame;
    private static float shown;

    private BootScreen() {
    }

    /** False when switched off with {@code -Dmyau.bootscreen=false}, the HUD setting, or after a failure. */
    public static boolean enabled() {
        if (failed) return false;
        if (enabled == null) {
            boolean on = !"false".equalsIgnoreCase(System.getProperty("myau.bootscreen"));
            try {
                if (OFF_MARKER.exists()) on = false;
            } catch (RuntimeException ignored) {
            }
            enabled = on;
        }
        return enabled;
    }

    /**
     * The HUD's "boot-screen" setting, called every frame once the client is running. The loading
     * screen runs before the configuration is read, so the choice is kept as a marker file.
     */
    public static void remember(boolean on) {
        if (rememberedKnown && on == remembered) return;
        rememberedKnown = true;
        remembered = on;
        try {
            if (on) {
                if (OFF_MARKER.exists()) OFF_MARKER.delete();
            } else if (!OFF_MARKER.exists()) {
                File parent = OFF_MARKER.getParentFile();
                if (parent.isDirectory() || parent.mkdirs()) OFF_MARKER.createNewFile();
            }
        } catch (Exception ignored) {
            // Not being able to store the choice only means the loading screen shows next time.
        }
    }

    // ---------------------------------------------------------------- layout, shared with Intro

    /** Font size of the name in pixels for a window this tall. */
    public static int logoPixels(int windowHeight) {
        return Math.max(18, Math.round(windowHeight * 0.058f));
    }

    public static int barWidth(int windowHeight) {
        return Math.max(120, Math.round(windowHeight * 0.26f));
    }

    public static int barHeight(int windowHeight) {
        return Math.max(3, Math.round(windowHeight * 0.0052f));
    }

    /** Top of the progress bar in pixels. */
    public static int barTop(int windowHeight) {
        return Math.round(windowHeight * (LOGO_Y + 0.105f));
    }

    // ---------------------------------------------------------------- frames

    /** One frame over Forge's splash, from its render thread, right before it swaps buffers. */
    public static void splashFrame() {
        if (!enabled()) return;
        try {
            frame(Display.getWidth(), Display.getHeight(), progress());
        } catch (Throwable e) {
            failed = true;
        }
    }

    /**
     * The single frame vanilla shows when Forge's animated splash is off: the same screen
     * without a bar, since nothing will redraw it while the game loads.
     *
     * @return true when it was drawn and the Mojang logo must be skipped
     */
    public static boolean staticFrame() {
        if (!enabled()) return false;
        try {
            frame(Display.getWidth(), Display.getHeight(), -1);
            Display.update();
            return true;
        } catch (Throwable e) {
            failed = true;
            return false;
        }
    }

    /** Overall loading progress from Forge's nested progress bars; it only ever moves forward. */
    private static float progress() {
        float done = 0, span = 1;
        Iterator<ProgressManager.ProgressBar> bars = ProgressManager.barIterator();
        while (bars.hasNext()) {
            ProgressManager.ProgressBar bar = bars.next();
            int steps = Math.max(1, bar.getSteps());
            // Step n is the one in progress: n - 1 are finished, the nested bars divide the n-th.
            done += span * Math.max(0, Math.min(steps, bar.getStep()) - 1) / steps;
            span /= steps;
        }
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        float target = Math.max(shown, Math.min(1, done));
        shown += (target - shown) * (1 - (float) Math.exp(-5 * dt));
        return shown;
    }

    private static void frame(int width, int height, float progress) {
        if (width <= 0 || height <= 0) return;

        // What is about to change, so it can be put back exactly.
        int boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D), blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int blendSource = GL11.glGetInteger(GL11.GL_BLEND_SRC), blendTarget = GL11.glGetInteger(GL11.GL_BLEND_DST);
        IntBuffer viewport = VIEWPORT;
        viewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);

        if (height != builtHeight) build(height);
        if (firstFrame == 0) firstFrame = System.nanoTime();
        float fade = Math.min(1, (System.nanoTime() - firstFrame) / 4.5e8f);
        fade = 1 - (1 - fade) * (1 - fade);

        GL11.glViewport(0, 0, width, height);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, width, height, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(0, 0, 0, 1);
        quad(0, 0, width, height);

        // The name.
        float logoX = Math.round((width - logoWidth) / 2f), logoY = Math.round(height * LOGO_Y - logoHeight / 2f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, logoTexture);
        GL11.glColor4f(1, 1, 1, fade);
        texturedQuad(logoX, logoY, logoWidth, logoHeight, 0, 0, logoWidth / (float) logoTextureWidth, logoHeight / (float) logoTextureHeight);

        if (progress >= 0) {
            int barWidth = barWidth(height), barHeight = barHeight(height);
            float barX = Math.round((width - barWidth) / 2f), barY = barTop(height);
            pill(barX, barY, barWidth, barHeight, 0.2f * fade);
            float filled = Math.max(barHeight, barWidth * Math.min(1, progress));
            pill(barX, barY, filled, barHeight, fade);
        }
        if (VisualCheck.ON) VisualCheck.bootFrame(width, height, progress);

        GL11.glColor4f(1, 1, 1, 1);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        GL11.glBlendFunc(blendSource, blendTarget);
        set(GL11.GL_BLEND, blend);
        set(GL11.GL_DEPTH_TEST, depth);
        set(GL11.GL_ALPHA_TEST, alpha);
        set(GL11.GL_LIGHTING, lighting);
        set(GL11.GL_CULL_FACE, cull);
        set(GL11.GL_TEXTURE_2D, texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundTexture);
    }

    private static void set(int capability, boolean on) {
        if (on) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    /** A white bar with round ends: two halves of a disc texture and a plain middle. */
    private static void pill(float x, float y, float w, float h, float opacity) {
        GL11.glColor4f(1, 1, 1, opacity);
        float cap = h / 2f, u = capSize / (float) capTextureSize;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, capTexture);
        texturedQuad(x, y, cap, h, 0, 0, u / 2, u);
        texturedQuad(x + w - cap, y, cap, h, u / 2, 0, u, u);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        if (w > h) quad(x + cap, y, w - h, h);
    }

    private static void quad(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }

    private static void texturedQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u0, v1);
        GL11.glVertex2f(x, y + h);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(u1, v0);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }

    // ---------------------------------------------------------------- textures

    /** Rasterise the name and the bar's round end for this window height. */
    private static void build(int height) {
        if (logoTexture != -1) GL11.glDeleteTextures(logoTexture);
        if (capTexture != -1) GL11.glDeleteTextures(capTexture);
        builtHeight = height;
        logoSize = logoPixels(height);

        Font font = pick(SEMIBOLD, Font.PLAIN, logoSize);
        if (font == null) font = pick(REGULAR, Font.BOLD, logoSize);
        if (font == null) font = new Font(Font.SANS_SERIF, Font.BOLD, logoSize);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D probeGraphics = hints(probe.createGraphics());
        FontMetrics metrics = probeGraphics.getFontMetrics(font);
        logoWidth = (int) Math.ceil(metrics.getStringBounds(NAME, probeGraphics).getWidth()) + 2;
        logoHeight = metrics.getAscent() + metrics.getDescent();
        int ascent = metrics.getAscent();
        probeGraphics.dispose();

        logoTextureWidth = powerOfTwo(logoWidth);
        logoTextureHeight = powerOfTwo(logoHeight);
        BufferedImage image = new BufferedImage(logoTextureWidth, logoTextureHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hints(image.createGraphics());
        g.setFont(font);
        g.setColor(Color.WHITE);
        g.drawString(NAME, 1, ascent);
        g.dispose();
        logoTexture = upload(image);

        capSize = barHeight(height);
        capTextureSize = powerOfTwo(capSize);
        BufferedImage cap = new BufferedImage(capTextureSize, capTextureSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D c = cap.createGraphics();
        c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        c.setColor(Color.WHITE);
        c.fillOval(0, 0, capSize, capSize);
        c.dispose();
        capTexture = upload(cap);
    }

    private static Font pick(String[] names, int style, int size) {
        for (String name : names) {
            Font candidate = new Font(name, style, size);
            // An unknown family silently becomes the default "Dialog" font.
            if (candidate.getFamily().equalsIgnoreCase(name)) return candidate;
        }
        return null;
    }

    private static Graphics2D hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static int powerOfTwo(int value) {
        int size = 2;
        while (size < value) size <<= 1;
        return size;
    }

    /** White with the image's alpha, so the edges of the glyphs stay white while they fade. */
    private static int upload(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) (pixel >>> 24));
        buffer.flip();
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        return texture;
    }
}
