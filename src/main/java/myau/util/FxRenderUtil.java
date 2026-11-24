package myau.util;

import myau.Myau;
import myau.mixin.IAccessorRenderManager;
import myau.module.modules.HUD;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/**
 * Drawing helpers shared by the visual effect modules (Breadcrumbs, DamageParticle,
 * HitBubbles, AttackEffect, BlockOverlay). Nothing here keeps GL state changed:
 * the callers set up and restore their own state.
 */
public final class FxRenderUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static Frustum frustum;

    private FxRenderUtil() {
    }

    // ---- camera -------------------------------------------------------------------------

    public static double renderX() {
        return ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
    }

    public static double renderY() {
        return ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
    }

    public static double renderZ() {
        return ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
    }

    /** 1 normally, -1 in the front third person view where the camera pitch is mirrored. */
    public static float viewFlip() {
        return mc.gameSettings.thirdPersonView == 2 ? -1.0F : 1.0F;
    }

    /** Turns the current matrix so that the XY plane faces the camera. */
    public static void billboard() {
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(mc.getRenderManager().playerViewX, viewFlip(), 0.0F, 0.0F);
    }

    /**
     * World-space axes of a quad that went through rotate(-yaw, Y) and rotate(pitch, X):
     * out[0..2] is the direction of local +X, out[3..5] the direction of local +Y.
     * Lets many camera-facing quads share one draw call instead of one matrix each.
     */
    public static void quadAxes(float yaw, float pitch, double[] out) {
        double phi = Math.toRadians(-yaw), theta = Math.toRadians(pitch);
        double sinPhi = Math.sin(phi), cosPhi = Math.cos(phi);
        double sinTheta = Math.sin(theta), cosTheta = Math.cos(theta);
        out[0] = cosPhi;
        out[1] = 0.0;
        out[2] = -sinPhi;
        out[3] = sinTheta * sinPhi;
        out[4] = cosTheta;
        out[5] = sinTheta * cosPhi;
    }

    /** Axes of a quad facing the camera right now, see {@link #quadAxes}. */
    public static void cameraAxes(double[] out) {
        quadAxes(mc.getRenderManager().playerViewY, mc.getRenderManager().playerViewX * viewFlip(), out);
    }

    /** Call once per frame before {@link #inFrustum}. Render thread only. */
    public static void beginFrustum() {
        if (frustum == null) frustum = new Frustum();
        frustum.setPosition(renderX(), renderY(), renderZ());
    }

    /** Whether a cube of half size {@code r} around a world position can be on screen. */
    public static boolean inFrustum(double x, double y, double z, double r) {
        return frustum.isBoxInFrustum(x - r, y - r, z - r, x + r, y + r, z + r);
    }

    // ---- colour -------------------------------------------------------------------------

    /** The HUD theme colour as 0xRRGGBB. */
    public static int themeColor() {
        try {
            HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
            if (hud != null) return hud.getColor(System.currentTimeMillis()).getRGB() & 0xFFFFFF;
        } catch (Exception ignored) {
        }
        return 0xFFFFFF;
    }

    /** A hue that goes once around in ten seconds, 0xRRGGBB. */
    public static int rainbow() {
        return Color.HSBtoRGB((System.nanoTime() + 400000L) / 1.0E10F % 1.0F, 1.0F, 1.0F) & 0xFFFFFF;
    }

    /** The pink-blue half of the hue circle, back and forth, 0xRRGGBB. */
    public static int skyRainbow(float saturation, float brightness) {
        double cycle = Math.ceil((double) System.currentTimeMillis()) / 5.0 % 360.0;
        float hue = (float) (cycle / 360.0);
        return Color.HSBtoRGB(hue < 0.5F ? -hue : hue, saturation, brightness) & 0xFFFFFF;
    }

    /** The given colour pulsing between half and full brightness every two seconds. */
    public static int fade(int rgb) {
        float[] hsb = Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, null);
        float wave = Math.abs((float) (System.currentTimeMillis() % 2000L) / 1000.0F - 1.0F);
        return Color.HSBtoRGB(hsb[0], hsb[1], 0.5F + 0.5F * wave) & 0xFFFFFF;
    }

    /** Dark red at no health, orange at half, green at full; 0xRRGGBB. */
    public static int healthColor(float health, float maxHealth) {
        float progress = maxHealth <= 0.0F ? 0.0F : Math.min(Math.max(health / maxHealth, 0.0F), 1.0F);
        Color low = new Color(108, 0, 0), mid = new Color(255, 51, 0);
        Color blend = progress < 0.5F
                ? ColorUtil.interpolate(progress / 0.5F, low, mid)
                : ColorUtil.interpolate((progress - 0.5F) / 0.5F, mid, Color.GREEN);
        return blend.brighter().getRGB() & 0xFFFFFF;
    }

    /** Sets the current colour from 0xRRGGBB and an alpha of 0..255. */
    public static void color(int rgb, int alpha) {
        GlStateManager.color((rgb >> 16 & 255) / 255.0F, (rgb >> 8 & 255) / 255.0F, (rgb & 255) / 255.0F, alpha / 255.0F);
    }

    // ---- shapes -------------------------------------------------------------------------

    /** The six faces of a box in the current colour. Expects face culling to be off. */
    public static void filledBox(AxisAlignedBB box) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        // bottom
        buffer.pos(box.minX, box.minY, box.minZ).endVertex();
        buffer.pos(box.maxX, box.minY, box.minZ).endVertex();
        buffer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        buffer.pos(box.minX, box.minY, box.maxZ).endVertex();
        // top
        buffer.pos(box.minX, box.maxY, box.minZ).endVertex();
        buffer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        // north
        buffer.pos(box.minX, box.minY, box.minZ).endVertex();
        buffer.pos(box.minX, box.maxY, box.minZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        buffer.pos(box.maxX, box.minY, box.minZ).endVertex();
        // south
        buffer.pos(box.minX, box.minY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        buffer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        // west
        buffer.pos(box.minX, box.minY, box.minZ).endVertex();
        buffer.pos(box.minX, box.minY, box.maxZ).endVertex();
        buffer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        buffer.pos(box.minX, box.maxY, box.minZ).endVertex();
        // east
        buffer.pos(box.maxX, box.minY, box.minZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        tessellator.draw();
    }

    /** The twelve edges of a box in the current colour and line width. */
    public static void outlinedBox(AxisAlignedBB box) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION);
        buffer.pos(box.minX, box.minY, box.minZ).endVertex();
        buffer.pos(box.minX, box.minY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.minY, box.minZ).endVertex();
        buffer.pos(box.minX, box.minY, box.minZ).endVertex();
        buffer.pos(box.minX, box.maxY, box.minZ).endVertex();
        buffer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        buffer.pos(box.minX, box.maxY, box.minZ).endVertex();
        tessellator.draw();
        // The three vertical edges the strip above did not cover.
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
        buffer.pos(box.minX, box.minY, box.maxZ).endVertex();
        buffer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        buffer.pos(box.maxX, box.minY, box.minZ).endVertex();
        buffer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        tessellator.draw();
    }

    /**
     * A filled rectangle with an outline, both ARGB. Expects blending on and texturing off
     * (see {@link RenderUtil#enableRenderState()}); the line width is left at 2.
     */
    public static void borderedRect(float x1, float y1, float x2, float y2, float lineWidth, int borderColor, int fillColor) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        RenderUtil.setColor(fillColor);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        buffer.pos(x1, y2, 0.0).endVertex();
        buffer.pos(x2, y2, 0.0).endVertex();
        buffer.pos(x2, y1, 0.0).endVertex();
        buffer.pos(x1, y1, 0.0).endVertex();
        tessellator.draw();
        RenderUtil.setColor(borderColor);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(lineWidth);
        buffer.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION);
        buffer.pos(x2, y1, 0.0).endVertex();
        buffer.pos(x1, y1, 0.0).endVertex();
        buffer.pos(x1, y2, 0.0).endVertex();
        buffer.pos(x2, y2, 0.0).endVertex();
        tessellator.draw();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(2.0F);
        GlStateManager.resetColor();
    }

    /** The bound texture stretched over a rectangle in the XY plane, tinted. */
    public static void texturedQuad(double x, double y, double width, double height, int rgb, float alpha) {
        float red = (rgb >> 16 & 255) / 255.0F, green = (rgb >> 8 & 255) / 255.0F, blue = (rgb & 255) / 255.0F;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        buffer.pos(x, y, 0.0).tex(0.0, 0.0).color(red, green, blue, alpha).endVertex();
        buffer.pos(x, y + height, 0.0).tex(0.0, 1.0).color(red, green, blue, alpha).endVertex();
        buffer.pos(x + width, y + height, 0.0).tex(1.0, 1.0).color(red, green, blue, alpha).endVertex();
        buffer.pos(x + width, y, 0.0).tex(1.0, 0.0).color(red, green, blue, alpha).endVertex();
        tessellator.draw();
    }
}
