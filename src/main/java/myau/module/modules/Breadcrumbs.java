package myau.module.modules;

import myau.event.EventTarget;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.util.FxRenderUtil;
import myau.util.RenderUtil;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;

/**
 * Leaves a ribbon along the path the player walked. The ribbon is sampled once per frame,
 * stands {@code line-height} tall and is visible through walls.
 */
public class Breadcrumbs extends Module {
    public final ColorProperty color = new ColorProperty("color", 0x8466FF);
    public final FloatProperty lineHeight = new FloatProperty("line-height", 0.25F, 0.25F, 2.0F);
    public final BooleanProperty temporary = new BooleanProperty("temporary", true);
    public final BooleanProperty fade = new BooleanProperty("fade", true, () -> this.temporary.getValue());
    public final FloatProperty lifeTime = new FloatProperty("life-time", 1.0F, 0.0F, 10.0F, () -> this.temporary.getValue());

    private static final int MAX_ALPHA = 150;
    /** A permanent trail is cut at this many samples so it cannot grow without bound. */
    private static final int MAX_POINTS = 16384;
    /** Squared distance the player has to move before a new sample is taken. */
    private static final double MIN_STEP_SQ = 1.0E-6;

    private static final class Point {
        final double x, y, z;
        final long time;

        Point(double x, double y, double z, long time) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
        }
    }

    private final ArrayDeque<Point> points = new ArrayDeque<>();

    public Breadcrumbs() {
        super("Breadcrumbs", false);
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        long now = System.currentTimeMillis();
        boolean expires = this.temporary.getValue();
        long life = (long) (this.lifeTime.getValue() * 1000.0F);

        if (expires) {
            while (!this.points.isEmpty() && now - this.points.peekFirst().time > life) this.points.pollFirst();
        } else {
            while (this.points.size() >= MAX_POINTS) this.points.pollFirst();
        }

        float partial = event.getPartialTicks();
        double x = RenderUtil.lerpDouble(mc.thePlayer.posX, mc.thePlayer.lastTickPosX, partial);
        double y = RenderUtil.lerpDouble(mc.thePlayer.posY, mc.thePlayer.lastTickPosY, partial);
        double z = RenderUtil.lerpDouble(mc.thePlayer.posZ, mc.thePlayer.lastTickPosZ, partial);
        Point last = this.points.peekLast();
        if (last == null || sq(x - last.x) + sq(y - last.y) + sq(z - last.z) > MIN_STEP_SQ) {
            this.points.addLast(new Point(x, y, z, now));
        }
        if (this.points.size() < 2) return;

        // Subtracted here in doubles: far from the origin a float cannot hold the position.
        double renderX = FxRenderUtil.renderX(), renderY = FxRenderUtil.renderY(), renderZ = FxRenderUtil.renderZ();
        double height = this.lineHeight.getValue();
        boolean fading = expires && this.fade.getValue();
        int rgb = this.color.getValue();
        int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;

        RenderUtil.enableRenderState();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        Point from = null;
        for (Point to : this.points) {
            if (from != null) {
                int alpha = MAX_ALPHA;
                if (fading) {
                    float age = life <= 0L ? 1.0F : Math.min((float) (now - from.time) / (float) life, 1.0F);
                    alpha = (int) (MAX_ALPHA * (1.0F - age));
                }
                if (alpha > 0) {
                    double x1 = from.x - renderX, y1 = from.y - renderY, z1 = from.z - renderZ;
                    double x2 = to.x - renderX, y2 = to.y - renderY, z2 = to.z - renderZ;
                    buffer.pos(x1, y1, z1).color(red, green, blue, alpha).endVertex();
                    buffer.pos(x1, y1 + height, z1).color(red, green, blue, alpha).endVertex();
                    buffer.pos(x2, y2 + height, z2).color(red, green, blue, alpha).endVertex();
                    buffer.pos(x2, y2, z2).color(red, green, blue, alpha).endVertex();
                }
            }
            from = to;
        }
        tessellator.draw();
        GlStateManager.resetColor();
        RenderUtil.disableRenderState();
    }

    private static double sq(double value) {
        return value * value;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.points.clear();
    }

    @Override
    public void onDisabled() {
        this.points.clear();
    }
}
