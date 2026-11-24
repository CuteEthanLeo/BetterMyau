package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ColorProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.FxRenderUtil;
import myau.util.TeamUtil;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Floating numbers where an entity lost or gained health. The health of every living entity
 * is compared with what it was a tick earlier; nothing is read from packets.
 */
public class DamageParticle extends Module {
    public final IntProperty aliveTicks = new IntProperty("alive-ticks", 50, 10, 50);
    public final IntProperty size = new IntProperty("size", 3, 1, 7);
    public final ModeProperty colourType = new ModeProperty("colour-type", 0, new String[]{"DAMAGE", "CUSTOM", "HUD"});
    public final ColorProperty color = new ColorProperty("color", 0xFFFFFF, () -> this.colourType.getValue() == 1);
    public final ModeProperty shadow = new ModeProperty(
            "shadow", 3, new String[]{"NORMAL", "DEFAULT", "VANILLA", "OUTLINE", "NONE"}, () -> this.colourType.getValue() != 0
    );

    private static final int PRUNE_TICKS = 200;

    private static final class Particle {
        final String text;
        final double x, y, z;
        int ticks;

        Particle(String text, double x, double y, double z) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Map<Integer, Float> healthData = new HashMap<>();
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private int pruneTimer;

    public DamageParticle() {
        super("DamageParticle", false);
    }

    private boolean isTracked(Entity entity) {
        if (!(entity instanceof EntityLivingBase) || entity == mc.thePlayer || entity instanceof EntityArmorStand) {
            return false;
        }
        return !(entity instanceof EntityPlayer) || !TeamUtil.isBot((EntityPlayer) entity);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        int mode = this.colourType.getValue();
        List<Entity> entities = mc.theWorld.loadedEntityList;
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            if (!this.isTracked(entity)) continue;
            EntityLivingBase living = (EntityLivingBase) entity;
            float health = living.getHealth();
            Float stored = this.healthData.put(living.getEntityId(), health);
            float lastHealth = stored != null ? stored : living.getMaxHealth();
            if (lastHealth == health || Float.isNaN(health) || Float.isNaN(lastHealth)) continue;

            boolean lost = lastHealth > health;
            String prefix = mode == 0 ? (lost ? "§c" : "§a") : mode == 2 ? (lost ? "-" : "+") : "";
            double amount = new BigDecimal(Math.abs((double) (lastHealth - health))).setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue();
            AxisAlignedBB box = living.getEntityBoundingBox();
            this.particles.add(new Particle(
                    prefix + amount,
                    living.posX - 0.5 + this.random.nextInt(5) * 0.1,
                    box.minY + (box.maxY - box.minY) / 2.0,
                    living.posZ - 0.5 + this.random.nextInt(5) * 0.1
            ));
        }

        int limit = this.aliveTicks.getValue();
        for (Iterator<Particle> iterator = this.particles.iterator(); iterator.hasNext(); ) {
            if (++iterator.next().ticks > limit) iterator.remove();
        }

        // Entities that left the world would otherwise stay in the map until the next world.
        if (++this.pruneTimer >= PRUNE_TICKS) {
            this.pruneTimer = 0;
            for (Iterator<Integer> iterator = this.healthData.keySet().iterator(); iterator.hasNext(); ) {
                if (mc.theWorld.getEntityByID(iterator.next()) == null) iterator.remove();
            }
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null || this.particles.isEmpty()) return;

        FontRenderer font = mc.fontRendererObj;
        double renderX = FxRenderUtil.renderX(), renderY = FxRenderUtil.renderY(), renderZ = FxRenderUtil.renderZ();
        float scale = this.size.getValue() * 0.01F;
        int mode = this.colourType.getValue();
        int shadowMode = this.shadow.getValue();
        int textColor = mode == 2 ? 0xFF000000 | FxRenderUtil.themeColor()
                : mode == 1 ? 0xFF000000 | this.color.getValue()
                : 0xFFFFFFFF;
        int textHeight = font.FONT_HEIGHT - 1;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.depthMask(false);
        for (int i = 0; i < this.particles.size(); i++) {
            Particle particle = this.particles.get(i);
            GlStateManager.pushMatrix();
            GlStateManager.translate((float) (particle.x - renderX), (float) (particle.y - renderY), (float) (particle.z - renderZ));
            FxRenderUtil.billboard();
            GlStateManager.scale(-scale, -scale, scale);

            int half = font.getStringWidth(particle.text) / 2;
            if (mode != 0) {
                switch (shadowMode) {
                    case 0: // NORMAL
                        font.drawString(particle.text, -half + 1, -textHeight + 1, 0x96000000);
                        break;
                    case 1: // DEFAULT
                        font.drawString(particle.text, -half + 0.5F, -textHeight + 0.5F, 0x82000000, false);
                        break;
                    case 2: // VANILLA
                        font.drawString(particle.text, -half + 1, -textHeight + 1, 0x96141414);
                        break;
                    case 3: // OUTLINE
                        font.drawString(particle.text, -half + 0.5F, -textHeight + 0.5F, 0x82000000, false);
                        font.drawString(particle.text, -half - 0.5F, -textHeight - 0.5F, 0x82000000, false);
                        font.drawString(particle.text, -half + 0.5F, -textHeight - 0.5F, 0x82000000, false);
                        font.drawString(particle.text, -half - 0.5F, -textHeight + 0.5F, 0x82000000, false);
                        break;
                    default:
                        break;
                }
            }
            font.drawString(particle.text, (float) -half, (float) -textHeight, textColor, false);
            GlStateManager.popMatrix();
        }
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.resetColor();
    }

    private void reset() {
        this.particles.clear();
        this.healthData.clear();
        this.pruneTimer = 0;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.reset();
    }

    @Override
    public void onEnabled() {
        // Health remembered from before the module was off would show up as fake damage.
        this.reset();
    }

    @Override
    public void onDisabled() {
        this.reset();
    }
}
