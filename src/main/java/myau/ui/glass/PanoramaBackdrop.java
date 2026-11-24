package myau.ui.glass;

import myau.ui.GlassTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

/**
 * The game's title panorama as a backdrop: the six cube faces seen from the centre, turning
 * slowly and tilting a little with the pointer, then blurred and graded darker so the glass on
 * top stays readable. The faces are loaded through the resource manager, so a resource pack's
 * panorama shows here too.
 */
final class PanoramaBackdrop {
    private static final ResourceLocation[] FACES = new ResourceLocation[6];

    static {
        for (int i = 0; i < 6; i++) FACES[i] = new ResourceLocation("textures/gui/title/background/panorama_" + i + ".png");
    }

    private PanoramaBackdrop() {
    }

    /** Draws the whole scene over the screen. False when it could not (then nothing was drawn). */
    static boolean draw(float width, float height) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.displayWidth <= 0 || mc.displayHeight <= 0 || !GlassBlur.available()) return false;
        double t = MenuBackdrop.time();
        float[] m = MenuBackdrop.pointer();

        cube(mc, (float) (t * 2.2 + m[0] * 7), (float) (14 + Math.sin(t / 22.0) * 8 - m[1] * 4));

        // Blur what was just drawn (the panorama alone) and lay it back over itself.
        GlassBlur.invalidate();
        GlassBlur.drawFullscreen(0.55f);
        GlassBlur.invalidate();

        // Grade: darker towards the edges and the bottom, a breath of the accent colour.
        Draw.rect(0, 0, width, height, 0, 0x38060810);
        Draw.gradientV(0, 0, width, height, 0, 0x30000000, 0x9a05070a);
        float reach = Math.max(width, height);
        Draw.spot(0, 0, width, height, 0, width * 0.32f, height * 0.25f, reach * 0.65f, Draw.alpha(GlassTheme.accent(), 0.08f));
        return true;
    }

    /** The six faces around the camera with the given yaw and pitch (degrees). */
    private static void cube(Minecraft mc, float yaw, float pitch) {
        // Put back exactly what was on before (this runs under different screens).
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        Project.gluPerspective(80.0F, mc.displayWidth / (float) mc.displayHeight, 0.05F, 10.0F);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.disableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
        // Look along the cube's faces: the same orientation the title screen uses.
        GlStateManager.rotate(180.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(90.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(pitch, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(-yaw, 0.0F, 1.0F, 0.0F);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        for (int face = 0; face < 6; face++) {
            GlStateManager.pushMatrix();
            switch (face) {
                case 1:
                    GlStateManager.rotate(90.0F, 0.0F, 1.0F, 0.0F);
                    break;
                case 2:
                    GlStateManager.rotate(180.0F, 0.0F, 1.0F, 0.0F);
                    break;
                case 3:
                    GlStateManager.rotate(-90.0F, 0.0F, 1.0F, 0.0F);
                    break;
                case 4:
                    GlStateManager.rotate(90.0F, 1.0F, 0.0F, 0.0F);
                    break;
                case 5:
                    GlStateManager.rotate(-90.0F, 1.0F, 0.0F, 0.0F);
                    break;
                default:
            }
            mc.getTextureManager().bindTexture(FACES[face]);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            buffer.pos(-1.0D, -1.0D, 1.0D).tex(0.0D, 0.0D).endVertex();
            buffer.pos(1.0D, -1.0D, 1.0D).tex(1.0D, 0.0D).endVertex();
            buffer.pos(1.0D, 1.0D, 1.0D).tex(1.0D, 1.0D).endVertex();
            buffer.pos(-1.0D, 1.0D, 1.0D).tex(0.0D, 1.0D).endVertex();
            tessellator.draw();
            GlStateManager.popMatrix();
        }

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        GlStateManager.depthMask(depthWrite);
        if (depth) GlStateManager.enableDepth();
        if (cull) GlStateManager.enableCull();
        if (alpha) GlStateManager.enableAlpha();
        if (blend) GlStateManager.enableBlend();
    }
}
