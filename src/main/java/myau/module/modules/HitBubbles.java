package myau.module.modules;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.FxRenderUtil;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A glowing ring in the theme colour that swells and shrinks where an attack landed. The
 * ring keeps the orientation the camera had at the moment of the hit.
 */
public class HitBubbles extends Module {
    public final BooleanProperty followHit = new BooleanProperty("follow-hit", true);
    public final BooleanProperty dynamicRotation = new BooleanProperty("dynamic-rotation", false);

    private static final ResourceLocation ICON = new ResourceLocation("bettermyau/bubble.png");
    private static final float MAX_LIFETIME = 1000.0F;
    /** How far in front of the eyes a bubble sits when it follows the hit. */
    private static final double HIT_DISTANCE = 3.0;

    private static final class Bubble {
        final float viewX, viewY;
        final Vec3 position;
        final long created = System.currentTimeMillis();

        Bubble(float viewX, float viewY, Vec3 position) {
            this.viewX = viewX;
            this.viewY = viewY;
            this.position = position;
        }

        /** 0 when created, 1 when it is over. */
        float delta() {
            return (float) (System.currentTimeMillis() - this.created) / MAX_LIFETIME;
        }
    }

    private final List<Bubble> bubbles = new ArrayList<>();

    public HitBubbles() {
        super("HitBubbles", false);
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.isCancelled() || !(event.getTarget() instanceof EntityLivingBase)) return;
        EntityLivingBase target = (EntityLivingBase) event.getTarget();

        Vec3 position;
        if (this.followHit.getValue()) {
            Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
            Vec3 look = mc.thePlayer.getLook(1.0F);
            position = eyes.addVector(look.xCoord * HIT_DISTANCE, look.yCoord * HIT_DISTANCE, look.zCoord * HIT_DISTANCE);
        } else {
            position = target.getPositionVector().addVector(0.0, target.height / 1.6, 0.0);
        }
        this.bubbles.add(new Bubble(mc.getRenderManager().playerViewX, -mc.getRenderManager().playerViewY, position));
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null || this.bubbles.isEmpty()) return;

        for (Iterator<Bubble> iterator = this.bubbles.iterator(); iterator.hasNext(); ) {
            if (iterator.next().delta() >= 1.0F) iterator.remove();
        }
        if (this.bubbles.isEmpty()) return;

        double renderX = FxRenderUtil.renderX(), renderY = FxRenderUtil.renderY(), renderZ = FxRenderUtil.renderZ();
        int color = FxRenderUtil.themeColor();
        float flip = FxRenderUtil.viewFlip();
        boolean spin = this.dynamicRotation.getValue();

        GlStateManager.enableBlend();
        // Additive: the ring adds light and never darkens what is behind it.
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, 1, 0);
        GlStateManager.disableAlpha();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.enableTexture2D();
        mc.getTextureManager().bindTexture(ICON);

        for (int i = 0; i < this.bubbles.size(); i++) {
            Bubble bubble = this.bubbles.get(i);
            float delta = bubble.delta();
            if (delta >= 1.0F) continue;

            GlStateManager.pushMatrix();
            GlStateManager.translate(
                    (float) (bubble.position.xCoord - renderX), (float) (bubble.position.yCoord - renderY), (float) (bubble.position.zCoord - renderZ)
            );
            // The bubble drifts as it grows.
            GlStateManager.translate(
                    -Math.sin(Math.toRadians(bubble.viewY)) * delta / 3.0,
                    Math.sin(Math.toRadians(bubble.viewX)) * delta / 2.0,
                    -Math.cos(Math.toRadians(bubble.viewY)) * delta / 3.0
            );
            GlStateManager.rotate(bubble.viewY, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(bubble.viewX, flip, 0.0F, 0.0F);
            GlStateManager.scale(-0.1F, -0.1F, 0.1F);

            float radius = 50.0F * delta * (1.0F - delta);
            if (spin) GlStateManager.rotate(this.rotationOf(bubble), 0.0F, 0.0F, 1.0F);
            FxRenderUtil.texturedQuad(-radius / 2.0F, -radius / 2.0F, radius, radius, color, 1.0F);
            GlStateManager.popMatrix();
        }

        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.enableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableBlend();
        GlStateManager.resetColor();
    }

    /** Angle of the bubble as seen from the player, minus where the player looks. */
    private float rotationOf(Bubble bubble) {
        double deltaX = bubble.position.xCoord - mc.thePlayer.posX;
        double deltaZ = bubble.position.zCoord - mc.thePlayer.posZ;
        if (deltaX == 0.0 && deltaZ == 0.0) return 0.0F;
        return (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - mc.thePlayer.rotationYaw);
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.bubbles.clear();
    }

    @Override
    public void onDisabled() {
        this.bubbles.clear();
    }
}
