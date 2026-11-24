package myau.ui.glass;

import myau.Myau;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * A soft halo around things in the world (chests, players), following their real shape.
 * <p>
 * During the 3D pass a module draws flat silhouettes between {@link #begin()} and
 * {@link #end()}; they land in a half-resolution mask instead of on screen. Once every module
 * has had its turn, {@link #flush()} blurs the mask (quarter and eighth resolution, three
 * small passes) and lays it over the frame as light outside the silhouettes. Every module
 * shares the one mask, so the cost per frame is fixed however many things glow, and a frame
 * in which nothing glows costs nothing.
 * <p>
 * This replaces the frosted plates ESP used to put behind its boxes: those blurred the whole
 * half-drawn frame a second time and still only produced a blurry rectangle.
 */
public final class WorldGlow {
    private WorldGlow() { }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float OFFSET = 1.5f;

    private static Framebuffer mask, quarter, eighth;
    private static int builtWidth, builtHeight;
    private static boolean dirty, drawing, failed;

    public static boolean available() {
        return !failed && OpenGlHelper.shadersSupported && OpenGlHelper.isFramebufferEnabled() && mc.getFramebuffer() != null
                && GlassShaders.downAlpha().ok && GlassShaders.upAlpha().ok && GlassShaders.glow().ok;
    }

    /**
     * Redirect drawing into the silhouette mask, keeping the current matrices. Draw with
     * blending off, depth test off and premultiplied colours (rgb * a, a): what is written is
     * exactly what glows. Always pair with {@link #end()}.
     *
     * @return false when the glow cannot be used; nothing was changed and nothing should be drawn
     */
    public static boolean begin() {
        if (drawing || !available()) return false;
        try {
            Framebuffer main = mc.getFramebuffer();
            int width = main.framebufferTextureWidth, height = main.framebufferTextureHeight;
            if (width <= 0 || height <= 0) return false;
            if (mask == null || width != builtWidth || height != builtHeight) build(width, height);
            mask.bindFramebuffer(true);
            if (!dirty) {
                GlStateManager.clearColor(0, 0, 0, 0);
                GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
                dirty = true;
            }
            drawing = true;
            return true;
        } catch (RuntimeException e) {
            fail(e);
            mc.getFramebuffer().bindFramebuffer(true);
            return false;
        }
    }

    /** Back to the screen. */
    public static void end() {
        if (!drawing) return;
        drawing = false;
        mc.getFramebuffer().bindFramebuffer(true);
    }

    private static void build(int width, int height) {
        if (mask != null) mask.deleteFramebuffer();
        if (quarter != null) quarter.deleteFramebuffer();
        if (eighth != null) eighth.deleteFramebuffer();
        mask = level(width >> 1, height >> 1);
        quarter = level(width >> 2, height >> 2);
        eighth = level(width >> 3, height >> 3);
        builtWidth = width;
        builtHeight = height;
        dirty = false;
    }

    private static Framebuffer level(int width, int height) {
        Framebuffer level = new Framebuffer(Math.max(1, width), Math.max(1, height), false);
        level.setFramebufferFilter(GL11.GL_LINEAR);
        GlStateManager.bindTexture(level.framebufferTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.bindTexture(0);
        return level;
    }

    private static void fail(RuntimeException e) {
        failed = true;
        drawing = false;
        dirty = false;
        Myau.LOGGER.error("[Glass] world glow disabled", e);
    }

    /**
     * Blur what was drawn into the mask this frame and put it on screen. Called once, right
     * after the 3D render event. Only raw GL is used in here and all of it is restored.
     */
    public static void flush() {
        if (drawing) end();
        if (!dirty) return;
        dirty = false;
        if (!available() || mask == null) return;

        Framebuffer main = mc.getFramebuffer();
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            GlassShaders.Program down = GlassShaders.downAlpha();
            GL20.glUseProgram(down.id);
            GL20.glUniform1i(down.loc("tex"), 0);
            GL20.glUniform1f(down.loc("offset"), OFFSET);
            pass(down, mask, quarter);
            pass(down, quarter, eighth);
            GlassShaders.Program up = GlassShaders.upAlpha();
            GL20.glUseProgram(up.id);
            GL20.glUniform1i(up.loc("tex"), 0);
            GL20.glUniform1f(up.loc("offset"), OFFSET);
            pass(up, eighth, quarter);

            // Onto the frame: halo outside the silhouettes, a faint tint inside.
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, main.framebufferObject);
            GL11.glViewport(0, 0, main.framebufferWidth, main.framebufferHeight);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlassShaders.Program glow = GlassShaders.glow();
            GL20.glUseProgram(glow.id);
            GL20.glUniform1i(glow.loc("blurTex"), 0);
            GL20.glUniform1i(glow.loc("maskTex"), 1);
            GL20.glUniform1f(glow.loc("strength"), 2.6f);
            GL20.glUniform1f(glow.loc("fill"), 0.1f);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mask.framebufferTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, quarter.framebufferTexture);
            quad();
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            GL20.glUseProgram(previousProgram);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, main.framebufferObject);
            GL11.glPopAttrib();
            GL11.glViewport(0, 0, main.framebufferWidth, main.framebufferHeight);
            GL13.glActiveTexture(previousUnit);
        }
    }

    private static void pass(GlassShaders.Program program, Framebuffer source, Framebuffer target) {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, target.framebufferObject);
        GL11.glViewport(0, 0, target.framebufferTextureWidth, target.framebufferTextureHeight);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, source.framebufferTexture);
        GL20.glUniform2f(program.loc("halfpixel"), 0.5f / source.framebufferTextureWidth, 0.5f / source.framebufferTextureHeight);
        quad();
    }

    private static void quad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
        GL11.glEnd();
    }
}
