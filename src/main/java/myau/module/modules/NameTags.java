package myau.module.modules;

import myau.Myau;
import myau.enums.ChatColors;
import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.util.ColorUtil;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import myau.util.BlurUtil;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.*;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.lwjgl.BufferUtils;
import org.lwjgl.util.glu.GLU;

public class NameTags extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat healthFormatter = new DecimalFormat("0.0", new DecimalFormatSymbols(Locale.US));

    private static final FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final BooleanProperty autoScale = new BooleanProperty("auto-scale", true);
    public final PercentProperty backgroundOpacity = new PercentProperty("background", 35); // 稍微调高点增强毛玻璃对比
    public final BooleanProperty shadow = new BooleanProperty("shadow", true);
    public final ModeProperty distanceMode = new ModeProperty("distance", 0, new String[]{"NONE", "DEFAULT", "VAPE"});
    public final ModeProperty healthMode = new ModeProperty("health", 2, new String[]{"NONE", "HP", "HEARTS", "TAB"});
    public final BooleanProperty armor = new BooleanProperty("armor", true);
    public final BooleanProperty effects = new BooleanProperty("effects", true);
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty friends = new BooleanProperty("friends", true);
    public final BooleanProperty enemies = new BooleanProperty("enemies", true);
    public final BooleanProperty bossees = new BooleanProperty("bosses", false);
    public final BooleanProperty mobs = new BooleanProperty("mobs", false);
    public final BooleanProperty creepers = new BooleanProperty("creepers", false);
    public final BooleanProperty endermans = new BooleanProperty("endermen", false);
    public final BooleanProperty blazes = new BooleanProperty("blazes", false);
    public final BooleanProperty animals = new BooleanProperty("animals", false);
    public final BooleanProperty self = new BooleanProperty("self", false);
    public final BooleanProperty bots = new BooleanProperty("bots", false);

    public NameTags() {
        super("NameTags", false);
    }

    public boolean shouldRenderTags(EntityLivingBase entityLivingBase) {
        if (entityLivingBase.deathTime > 0) {
            return false;
        } else if (mc.getRenderViewEntity().getDistanceToEntity(entityLivingBase) > 512.0F) {
            return false;
        } else if (entityLivingBase instanceof EntityPlayer) {
            if (entityLivingBase != mc.thePlayer && entityLivingBase != mc.getRenderViewEntity()) {
                if (TeamUtil.isBot((EntityPlayer) entityLivingBase)) {
                    return this.bots.getValue();
                } else if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                    return this.friends.getValue();
                } else {
                    return TeamUtil.isTarget((EntityPlayer) entityLivingBase) ? this.enemies.getValue() : this.players.getValue();
                }
            } else {
                return this.self.getValue() && mc.gameSettings.thirdPersonView != 0;
            }
        } else if (entityLivingBase instanceof EntityDragon || entityLivingBase instanceof EntityWither) {
            return !entityLivingBase.isInvisible() && this.bossees.getValue();
        } else if (!(entityLivingBase instanceof EntityMob) && !(entityLivingBase instanceof EntitySlime)) {
            // ==========================================
            // 【已修复】：将原本错误的 entityAnimal 改回 entityLivingBase
            // ==========================================
            return (entityLivingBase instanceof EntityAnimal
                    || entityLivingBase instanceof EntityBat
                    || entityLivingBase instanceof EntitySquid
                    || entityLivingBase instanceof EntityVillager) && this.animals.getValue();
        } else if (entityLivingBase instanceof EntityCreeper) {
            return this.creepers.getValue();
        } else if (entityLivingBase instanceof EntityEnderman) {
            return this.endermans.getValue();
        } else {
            return entityLivingBase instanceof EntityBlaze ? this.blazes.getValue() : this.mobs.getValue();
        }
    }

    /** One tag found in the 3D pass, drawn in the 2D pass. Reused between frames. */
    private static final class Tag {
        EntityLivingBase entity;
        float x, y;          // anchor in window pixels, origin top left
        float distance;
        String text;
        float percent;
    }

    private final ArrayList<Tag> tags = new ArrayList<>();
    private int tagCount;

    /**
     * The text of an entity's tag. Building it means resolving the display name (a chat
     * component), three regex passes and number formatting; at a few hundred frames a second
     * that was the most expensive part of a tag, and none of it changes within a game tick.
     */
    private static final class TagText {
        int nameTick = Integer.MIN_VALUE, seenTick;
        String name = "", text;
        boolean blank;
        int distanceKey, healthKey, absorptionKey;
    }

    private final java.util.IdentityHashMap<Entity, TagText> texts = new java.util.IdentityHashMap<>();
    private int lastSweepTick;
    private final ArrayList<PotionEffect> shownEffects = new ArrayList<>();
    private boolean drawFailed;

    private static final Color HEALTH_LOW_A = new Color(255, 80, 120), HEALTH_HIGH_A = new Color(45, 230, 150);
    private static final Color HEALTH_LOW_B = new Color(220, 40, 80), HEALTH_HIGH_B = new Color(0, 180, 200);

    /**
     * Only projects: the anchor above each head goes through the real camera matrices here,
     * and the tag itself is drawn flat in the 2D pass. Drawing in screen space keeps the size
     * bounded (in world space a tag next to the camera covered the whole screen) and lets every
     * tag share the HUD's one blur instead of blurring the half-drawn world.
     */
    @EventTarget
    public void onRender(Render3DEvent event) {
        this.tagCount = 0;
        if (!this.isEnabled() || mc.theWorld == null || mc.thePlayer == null || mc.getRenderViewEntity() == null) return;
        int tick = mc.thePlayer.ticksExisted;
        if (tick - this.lastSweepTick > 100 || tick < this.lastSweepTick) {
            // Forget entities that have not had a tag for a while (and everything on a world change).
            final int now = tick, last = this.lastSweepTick;
            this.texts.values().removeIf(cached -> now < last || now - cached.seenTick > 100);
            this.lastSweepTick = tick;
        }

        modelView.clear(); projection.clear(); viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
        float margin = 120.0F;

        // Sorted far to near, so nearer tags are drawn on top.
        for (Entity entity : TeamUtil.getRenderEntitiesSorted()) {
            if (!(entity instanceof EntityLivingBase)) continue;
            EntityLivingBase living = (EntityLivingBase) entity;
            if (!this.shouldRenderTags(living)) continue;
            if (!entity.ignoreFrustumCheck && !RenderUtil.isInViewFrustum(entity.getEntityBoundingBox(), 10.0)) continue;

            double x = RenderUtil.lerpDouble(entity.posX, entity.lastTickPosX, event.getPartialTicks()) - renderX;
            double y = RenderUtil.lerpDouble(entity.posY, entity.lastTickPosY, event.getPartialTicks()) - renderY
                    + entity.getEyeHeight() + (entity.isSneaking() ? 0.225 : 0.4);
            double z = RenderUtil.lerpDouble(entity.posZ, entity.lastTickPosZ, event.getPartialTicks()) - renderZ;
            screenCoords.clear();
            if (!GLU.gluProject((float) x, (float) y, (float) z, modelView, projection, viewport, screenCoords)) continue;
            float depth = screenCoords.get(2);
            if (!(depth >= 0.0F && depth < 1.0F)) continue;   // behind the camera
            float sx = screenCoords.get(0), sy = mc.displayHeight - screenCoords.get(1);
            if (sx < -margin || sy < -margin || sx > mc.displayWidth + margin || sy > mc.displayHeight + margin) continue;

            TagText cached = this.texts.get(entity);
            if (cached == null) {
                cached = new TagText();
                this.texts.put(entity, cached);
            }
            cached.seenTick = tick;
            if (cached.nameTick != tick) {
                cached.nameTick = tick;
                cached.name = TeamUtil.stripName(entity);
                cached.blank = StringUtils.isBlank(EnumChatFormatting.getTextWithoutFormattingCodes(cached.name));
                cached.text = null;
            }
            if (cached.blank) continue;

            float distance = mc.getRenderViewEntity().getDistanceToEntity(entity);
            if (this.tagCount == this.tags.size()) this.tags.add(new Tag());
            Tag tag = this.tags.get(this.tagCount++);
            tag.entity = living;
            tag.x = sx;
            tag.y = sy;
            tag.distance = distance;
            float health = living.getHealth(), absorption = living.getAbsorptionAmount();
            int distanceKey = (int) distance, healthKey = Math.round(health * 10.0F), absorptionKey = Math.round(absorption * 10.0F);
            if (cached.text == null || distanceKey != cached.distanceKey || healthKey != cached.healthKey || absorptionKey != cached.absorptionKey) {
                cached.text = this.buildText(living, cached.name, distance);
                cached.distanceKey = distanceKey;
                cached.healthKey = healthKey;
                cached.absorptionKey = absorptionKey;
            }
            tag.text = cached.text;
            tag.percent = Math.min(Math.max((health + absorption) / Math.max(1.0F, living.getMaxHealth()), 0.0F), 1.0F);
        }
    }

    private String buildText(EntityLivingBase entity, String teamName, float distance) {
        String distanceText = "";
        switch (this.distanceMode.getValue()) {
            case 1: distanceText = "&7" + (int) distance + "m&r "; break;
            case 2: distanceText = "&a[&f" + (int) distance + "&a]&r "; break;
        }
        float health = entity.getHealth();
        float absorption = entity.getAbsorptionAmount();
        String healText = "";
        switch (this.healthMode.getValue()) {
            case 1: healText = " " + (int) health + (absorption > 0.0F ? " &6" + (int) absorption + "&r" : "&r"); break;
            case 2: healText = " " + healthFormatter.format((double) health / 2.0) + (absorption > 0.0F ? " &6" + healthFormatter.format((double) absorption / 2.0) + "&r" : "&r"); break;
            case 3:
                if (entity instanceof EntityPlayer) {
                    Scoreboard scoreboard = mc.theWorld.getScoreboard();
                    if (scoreboard != null) {
                        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(2);
                        if (objective != null) {
                            Score score = scoreboard.getValueFromObjective(entity.getName(), objective);
                            if (score != null) healText = " &e" + score.getScorePoints() + "&r";
                        }
                    }
                }
                break;
        }
        return ChatColors.formatColor(distanceText + "&f" + teamName + "&r" + healText);
    }

    /**
     * On-screen size of a tag relative to its normal size: constant up close (capped, so a
     * player next to the camera never fills the screen), shrinking slowly with distance.
     */
    private float tagScale(float distance) {
        float base = this.scale.getValue();
        if (!this.autoScale.getValue()) return 1.25F * base;
        float d = Math.min(Math.max(distance, 8.0F), 160.0F);
        return 1.6F * base * (float) Math.pow(8.0F / d, 0.3);
    }

    @EventTarget(myau.event.types.Priority.HIGH)
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || this.tagCount == 0 || mc.theWorld == null) {
            this.tagCount = 0;
            return;
        }
        long now = System.currentTimeMillis();
        myau.ui.glass.Draw.useGuiScale();
        float gui = myau.ui.glass.Draw.scale();
        float toUnits = 1.0F / gui;
        // Tags are drawn up to ~2x the GUI size; rasterise glyphs at twice the GUI scale so
        // they stay sharp. Positions stay in GUI units (the GL matrix is the overlay one).
        myau.ui.glass.Draw.setScale(gui * 2.0F);
        myau.ui.glass.SmoothFont tagFont = myau.ui.glass.SmoothFont.regular(9);
        GlStateManager.disableDepth();
        try {
            for (int i = 0; i < this.tagCount; i++) {
                Tag tag = this.tags.get(i);
                // Fade out right next to the camera instead of covering the view.
                float fade = Math.max(0.0F, Math.min(1.0F, (tag.distance - 0.9F) / 1.2F));
                if (fade <= 0.01F) continue;
                myau.ui.glass.Draw.pushAlpha(fade);
                myau.ui.glass.Draw.pushTransform(0, 0, this.tagScale(tag.distance), tag.x * toUnits, tag.y * toUnits);
                try {
                    this.drawTag(tag, tagFont, now);
                } catch (RuntimeException e) {
                    if (!this.drawFailed) {
                        this.drawFailed = true;
                        Myau.LOGGER.error("[NameTags] tag drawing failed (logged once)", e);
                    }
                } finally {
                    myau.ui.glass.Draw.popTransform();
                    myau.ui.glass.Draw.popAlpha();
                }
            }
        } finally {
            this.tagCount = 0;
            // Entity references must not outlive the frame (world changes, unloads).
            for (Tag tag : this.tags) tag.entity = null;
            GlStateManager.enableDepth();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            myau.ui.glass.Draw.useGuiScale();
        }
    }

    /** One tag in local units: centred on x = 0, its bottom edge just above y = 0. */
    private void drawTag(Tag tag, myau.ui.glass.SmoothFont tagFont, long now) {
        EntityLivingBase entity = tag.entity;
        String color = tag.text;
        float width = tagFont.width(color);
        float x1 = -width / 2.0F - 5.0F;
        float y1 = (float) (-mc.fontRendererObj.FONT_HEIGHT) - 4.0F;
        float x2 = width / 2.0F + 5.0F;
        float y2 = 3.0F;
        float cornerRadius = 4.0F;

        if (this.backgroundOpacity.getValue() > 0) {
            myau.ui.glass.Draw.blurred(x1, y1, x2 - x1, y2 - y1, cornerRadius, 1.0F);
            int bg = myau.ui.glass.Draw.alpha(0x0f0f17, this.backgroundOpacity.getValue() / 100.0F);
            drawRoundedRect(x1, y1, x2, y2, cornerRadius, bg);
            drawGradientRoundedRect(x1, y1, x2, y2 - (y2 - y1) * 0.4F, cornerRadius, 0x12ffffff, 0x00ffffff);
            myau.ui.glass.Draw.outline(x1, y1, x2 - x1, y2 - y1, cornerRadius, 0.5F, 0x48ffffff, 0x10ffffff);
        }

        float textY = tagFont.centerY(y1, y2 - y1 - 1.0F);
        int textColor = 0xffffffff;   // SmoothFont applies the current Draw alpha itself
        if (this.shadow.getValue()) tagFont.drawShadowed(color, -width / 2.0F, textY, textColor);
        else tagFont.draw(color, -width / 2.0F, textY, textColor);

        if (!(entity instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) entity;

        // Health bar along the bottom edge
        float barWidth = x2 - x1;
        float trackX = x1 + 3.0F, trackW = barWidth - 6.0F, trackY = y2 - 2.5F;
        myau.ui.glass.Draw.rect(trackX, trackY, trackW, 1.5F, 0.75F, 0x961e1e28);
        float percent = tag.percent;
        if (percent > 0) {
            Color hColor1 = ColorUtil.interpolate(percent, HEALTH_LOW_A, HEALTH_HIGH_A);
            Color hColor2 = ColorUtil.interpolate(percent, HEALTH_LOW_B, HEALTH_HIGH_B);
            myau.ui.glass.Draw.gradientH(trackX, trackY, Math.max(1.5F, trackW * percent), 1.5F, 0.75F, hColor1.getRGB(), hColor2.getRGB());
        }

        // Friends and targets: a slowly breathing rim
        boolean isFriend = TeamUtil.isFriend(player);
        boolean isTarget = !isFriend && TeamUtil.isTarget(player);
        if (isFriend || isTarget) {
            float index = (float) (Math.sin(now * 0.0025) + 1.0) / 2.0F;
            Color baseColor = isFriend ? Myau.friendManager.getColor() : Myau.targetManager.getColor();
            Color waveColor = ColorUtil.interpolate(index, baseColor, baseColor.brighter());
            drawRoundedOutline(x1, y1, x2, y2, cornerRadius, 1.2F, waveColor.getRGB());
        }

        // Armour, held item and potion icons above the tag
        int itemsHeight = mc.fontRendererObj.FONT_HEIGHT + 5;
        if (this.armor.getValue()) {
            int count = 0;
            for (int i = 4; i >= 0; i--) {
                if (((i == 0) ? player.getHeldItem() : player.inventory.armorInventory[i - 1]) != null) count++;
            }
            if (count > 0) {
                int offset = count * -8, slot = 0;
                GlStateManager.enableDepth();   // block items need it
                for (int i = 4; i >= 0; i--) {
                    ItemStack itemStack = (i == 0) ? player.getHeldItem() : player.inventory.armorInventory[i - 1];
                    if (itemStack == null) continue;
                    // One depth clear for the row, not one whole-screen clear per icon.
                    RenderUtil.renderItemInGUI(itemStack, offset + slot * 16, -itemsHeight - 16, slot == 0);
                    slot++;
                }
                itemsHeight += 16;
                GlStateManager.disableDepth();
            }
        }
        if (this.effects.getValue()) {
            List<PotionEffect> activeEffects = this.shownEffects;
            activeEffects.clear();
            for (PotionEffect effect : player.getActivePotionEffects()) {
                int id = effect.getPotionID();
                // Servers behind ViaVersion can send effects that 1.8 does not know about.
                if (id >= 0 && id < Potion.potionTypes.length && Potion.potionTypes[id] != null && Potion.potionTypes[id].hasStatusIcon()) {
                    activeEffects.add(effect);
                }
            }
            if (!activeEffects.isEmpty()) {
                GlStateManager.pushMatrix();
                GlStateManager.scale(0.5F, 0.5F, 1.0F);
                int offset = activeEffects.size() * -9;
                for (int i = 0; i < activeEffects.size(); i++) {
                    RenderUtil.renderPotionEffect(activeEffects.get(i), offset + i * 18, -(itemsHeight * 2) - 18, i == 0);
                }
                GlStateManager.popMatrix();
                GlStateManager.disableDepth();
            }
        }
    }

    // ==========================================
    // 渲染辅助工具函数集
    // ==========================================

    private void drawRoundedRect(float x, float y, float x2, float y2, float radius, int color) {
        myau.ui.glass.Draw.rect(x, y, x2 - x, y2 - y, radius, color);
    }

    private void drawRoundedOutline(float x, float y, float x2, float y2, float radius, float lineWidth, int color) {
        myau.ui.glass.Draw.outline(x, y, x2 - x, y2 - y, radius, Math.max(0.6f, lineWidth * 0.6f), color, color);
    }

    /**
     * 新增：支持垂直颜色渐变的圆角矩形绘制（用于实现玻璃反光切面）
     */
    private void drawGradientRoundedRect(float x, float y, float x2, float y2, float radius, int startColor, int endColor) {
        myau.ui.glass.Draw.gradientV(x, y, x2 - x, y2 - y, radius, startColor, endColor);
    }
}
