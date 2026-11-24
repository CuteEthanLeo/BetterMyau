package myau.ui.glass;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Boxes in the world (chest markers, 3D player boxes) drawn as a batch: every box of a frame
 * goes out in a handful of draw calls instead of a state change and several draws per box.
 * <p>
 * A box is a tinted volume that is denser at the bottom and fades towards the top, with a
 * crisp outline. It is drawn twice: faintly through walls, then at full strength where it is
 * actually in view, so at a glance you can tell a chest behind a wall from one in the open.
 * Boxes that ask for it are also written into {@link WorldGlow}'s mask for a soft halo.
 * <p>
 * Call {@link #begin()}, {@link #add} any number of times, then {@link #draw} from the 3D
 * render event. Coordinates are relative to the render position, as everything in that pass.
 */
public final class WorldBoxes {
    private WorldBoxes() { }

    private static final int STRIDE = 6;
    private static double[] bounds = new double[STRIDE * 64];
    private static int[] colors = new int[64];
    private static float[] fades = new float[64];
    private static boolean[] glows = new boolean[64];
    private static int count;

    public static void begin() {
        count = 0;
    }

    /**
     * @param rgb  colour, 0xRRGGBB
     * @param fade 0..1 overall opacity of this box (distance fade, "already opened", ...)
     * @param glow whether this box gets a halo
     */
    public static void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int rgb, float fade, boolean glow) {
        if (fade <= 0.01f) return;
        if (count == colors.length) {
            int size = count * 2;
            bounds = java.util.Arrays.copyOf(bounds, size * STRIDE);
            colors = java.util.Arrays.copyOf(colors, size);
            fades = java.util.Arrays.copyOf(fades, size);
            glows = java.util.Arrays.copyOf(glows, size);
        }
        int at = count * STRIDE;
        bounds[at] = minX; bounds[at + 1] = minY; bounds[at + 2] = minZ;
        bounds[at + 3] = maxX; bounds[at + 4] = maxY; bounds[at + 5] = maxZ;
        colors[count] = rgb;
        fades[count] = Math.min(1f, fade);
        glows[count] = glow;
        count++;
    }

    public static int size() {
        return count;
    }

    /**
     * @param fill      0..1 opacity of the volume at its base
     * @param lineWidth outline width in pixels
     * @param glow      draw the halo (for the boxes that asked for one)
     */
    public static void draw(float fill, float lineWidth, boolean glow) {
        if (count == 0) return;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glLineWidth(lineWidth);
        try {
            // Through walls: faint.
            GlStateManager.disableDepth();
            if (fill > 0.005f) faces(fill * 0.4f, 0, false);
            lines(0.38f, 0);
            // In view: full strength. Grown a hair so it does not z-fight the block it wraps.
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            if (fill > 0.005f) faces(fill, 0.004, false);
            lines(1f, 0.004);

            if (glow && anyGlow() && WorldGlow.begin()) {
                // Silhouettes for the halo: flat, premultiplied, straight into the mask.
                GlStateManager.disableBlend();
                GlStateManager.disableDepth();
                faces(1f, 0, true);
                WorldGlow.end();
                GlStateManager.enableBlend();
            }
        } finally {
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1f);
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();
            GlStateManager.enableCull();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.resetColor();
            GlStateManager.color(1, 1, 1, 1);
        }
    }

    private static boolean anyGlow() {
        for (int i = 0; i < count; i++) if (glows[i]) return true;
        return false;
    }

    /**
     * @param opacity base opacity; the top of each box gets a quarter of it
     * @param grow    how far to push each face outwards
     * @param mask    write premultiplied silhouettes of the glowing boxes instead
     */
    private static void faces(float opacity, double grow, boolean mask) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            if (mask && !glows[i]) continue;
            int at = i * STRIDE;
            double x0 = bounds[at] - grow, y0 = bounds[at + 1] - grow, z0 = bounds[at + 2] - grow;
            double x1 = bounds[at + 3] + grow, y1 = bounds[at + 4] + grow, z1 = bounds[at + 5] + grow;
            float r = (colors[i] >> 16 & 255) / 255f, g = (colors[i] >> 8 & 255) / 255f, b = (colors[i] & 255) / 255f;
            float lo, hi;
            if (mask) {
                lo = hi = fades[i];
                r *= lo; g *= lo; b *= lo;
            } else {
                lo = opacity * fades[i];
                hi = lo * 0.25f;
            }
            // bottom, top
            wr.pos(x0, y0, z0).color(r, g, b, lo).endVertex(); wr.pos(x1, y0, z0).color(r, g, b, lo).endVertex();
            wr.pos(x1, y0, z1).color(r, g, b, lo).endVertex(); wr.pos(x0, y0, z1).color(r, g, b, lo).endVertex();
            wr.pos(x0, y1, z0).color(r, g, b, hi).endVertex(); wr.pos(x0, y1, z1).color(r, g, b, hi).endVertex();
            wr.pos(x1, y1, z1).color(r, g, b, hi).endVertex(); wr.pos(x1, y1, z0).color(r, g, b, hi).endVertex();
            // north, south
            wr.pos(x0, y0, z0).color(r, g, b, lo).endVertex(); wr.pos(x0, y1, z0).color(r, g, b, hi).endVertex();
            wr.pos(x1, y1, z0).color(r, g, b, hi).endVertex(); wr.pos(x1, y0, z0).color(r, g, b, lo).endVertex();
            wr.pos(x0, y0, z1).color(r, g, b, lo).endVertex(); wr.pos(x1, y0, z1).color(r, g, b, lo).endVertex();
            wr.pos(x1, y1, z1).color(r, g, b, hi).endVertex(); wr.pos(x0, y1, z1).color(r, g, b, hi).endVertex();
            // west, east
            wr.pos(x0, y0, z0).color(r, g, b, lo).endVertex(); wr.pos(x0, y0, z1).color(r, g, b, lo).endVertex();
            wr.pos(x0, y1, z1).color(r, g, b, hi).endVertex(); wr.pos(x0, y1, z0).color(r, g, b, hi).endVertex();
            wr.pos(x1, y0, z0).color(r, g, b, lo).endVertex(); wr.pos(x1, y1, z0).color(r, g, b, hi).endVertex();
            wr.pos(x1, y1, z1).color(r, g, b, hi).endVertex(); wr.pos(x1, y0, z1).color(r, g, b, lo).endVertex();
        }
        tessellator.draw();
    }

    private static void lines(float opacity, double grow) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            int at = i * STRIDE;
            double x0 = bounds[at] - grow, y0 = bounds[at + 1] - grow, z0 = bounds[at + 2] - grow;
            double x1 = bounds[at + 3] + grow, y1 = bounds[at + 4] + grow, z1 = bounds[at + 5] + grow;
            float r = (colors[i] >> 16 & 255) / 255f, g = (colors[i] >> 8 & 255) / 255f, b = (colors[i] & 255) / 255f;
            float a = opacity * fades[i];
            // bottom ring
            edge(wr, x0, y0, z0, x1, y0, z0, r, g, b, a); edge(wr, x1, y0, z0, x1, y0, z1, r, g, b, a);
            edge(wr, x1, y0, z1, x0, y0, z1, r, g, b, a); edge(wr, x0, y0, z1, x0, y0, z0, r, g, b, a);
            // top ring
            edge(wr, x0, y1, z0, x1, y1, z0, r, g, b, a); edge(wr, x1, y1, z0, x1, y1, z1, r, g, b, a);
            edge(wr, x1, y1, z1, x0, y1, z1, r, g, b, a); edge(wr, x0, y1, z1, x0, y1, z0, r, g, b, a);
            // uprights
            edge(wr, x0, y0, z0, x0, y1, z0, r, g, b, a); edge(wr, x1, y0, z0, x1, y1, z0, r, g, b, a);
            edge(wr, x1, y0, z1, x1, y1, z1, r, g, b, a); edge(wr, x0, y0, z1, x0, y1, z1, r, g, b, a);
        }
        tessellator.draw();
    }

    private static void edge(WorldRenderer wr, double ax, double ay, double az, double bx, double by, double bz, float r, float g, float b, float a) {
        wr.pos(ax, ay, az).color(r, g, b, a).endVertex();
        wr.pos(bx, by, bz).color(r, g, b, a).endVertex();
    }
}
