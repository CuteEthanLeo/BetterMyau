package myau.module.modules;

import myau.Myau;
import myau.enums.ChatColors;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.util.ColorUtil;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import myau.util.TimerUtil;
import myau.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.lwjgl.BufferUtils;
import org.lwjgl.util.glu.GLU;

public class TargetHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat healthFormat = new DecimalFormat("0.0", new DecimalFormatSymbols(Locale.US));

    private static final FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    private final TimerUtil lastAttackTimer = new TimerUtil();
    private EntityLivingBase lastTarget = null;
    private EntityLivingBase target = null;
    private ResourceLocation headTexture = null;

    private float animatedHealth = 0.0F;
    private float animatedWhiteHealth = 0.0F;
    private final myau.ui.glass.Anim.Spring appear = myau.ui.glass.Anim.spring(0.0F, 0.34F, 0.68F);
    private EntityLivingBase shown = null;

    // Where the target is on screen this frame, from the 3D pass: its box in window pixels, y down.
    private EntityLivingBase projected = null;
    private int projectedFrame = -1;
    private float boxX0, boxY0, boxX1, boxY1;
    // Where the panel is drawn (GUI units, scale applied), eased towards where it belongs.
    private float drawX, drawY;
    private boolean leftSide = false, wasOnTarget = false;
    private EntityLivingBase followed = null;
    // 0 right after the panel was sent somewhere new (another target, on or off it), 1 once it follows closely.
    private float glide = 1.0F;
    // The health line is formatted again only when the numbers change.
    private float textHealth = Float.NaN, textMax = Float.NaN;
    private String healthText = "";

    public final ModeProperty color = new ModeProperty("color", 0, new String[]{"DEFAULT", "HUD"});
    // Sit beside the target on screen and follow it; the position settings are where it goes otherwise.
    public final BooleanProperty attach = new BooleanProperty("attach-target", true);
    public final ModeProperty posX = new ModeProperty("position-x", 1, new String[]{"LEFT", "MIDDLE", "RIGHT"});
    public final ModeProperty posY = new ModeProperty("position-y", 1, new String[]{"TOP", "MIDDLE", "BOTTOM"});
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 1.5F);
    public final IntProperty offX = new IntProperty("offset-x", 0, -255, 255);
    public final IntProperty offY = new IntProperty("offset-y", 60, -255, 255);
    public final PercentProperty background = new PercentProperty("background", 38);
    public final BooleanProperty head = new BooleanProperty("head", true);
    public final BooleanProperty animations = new BooleanProperty("animations", true);
    public final BooleanProperty kaOnly = new BooleanProperty("ka-only", true);
    public final BooleanProperty chatPreview = new BooleanProperty("chat-preview", false);

    public TargetHUD() {
        super("TargetHUD", false, true);
    }

    private EntityLivingBase resolveTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura.isEnabled() && killAura.isAttackAllowed() && TeamUtil.isEntityLoaded(killAura.getTarget())) {
            return killAura.getTarget();
        } else if (!(Boolean) this.kaOnly.getValue()
                && !this.lastAttackTimer.hasTimeElapsed(1500L)
                && TeamUtil.isEntityLoaded(this.lastTarget)) {
            return this.lastTarget;
        } else {
            return this.chatPreview.getValue() && mc.currentScreen instanceof GuiChat ? mc.thePlayer : null;
        }
    }

    private ResourceLocation getSkin(EntityLivingBase entityLivingBase) {
        if (entityLivingBase instanceof EntityPlayer) {
            NetworkPlayerInfo playerInfo = mc.getNetHandler().getPlayerInfo(entityLivingBase.getName());
            if (playerInfo != null) return playerInfo.getLocationSkin();
        }
        return null;
    }

    private Color getTargetColor(EntityLivingBase entityLivingBase) {
        if (entityLivingBase instanceof EntityPlayer) {
            if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) return Myau.friendManager.getColor();
            if (TeamUtil.isTarget((EntityPlayer) entityLivingBase)) return Myau.targetManager.getColor();
        }
        switch (this.color.getValue()) {
            case 0:
                if (!(entityLivingBase instanceof EntityPlayer)) return new Color(-1);
                return TeamUtil.getTeamColor((EntityPlayer) entityLivingBase, 1.0F);
            case 1:
                return ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
            default:
                return new Color(-1);
        }
    }

    private int getMysticColor(long offset, float speed) {
        // 生成高雅克制的幽暗霓虹色系流动（暗紫 -> 魅蓝 -> 深青）
        float h = ((float) (System.currentTimeMillis() + offset) * speed) % 360.0F / 360.0F;
        return Color.getHSBColor(h, 0.75F, 0.85F).getRGB();
    }

    /**
     * Finds the target on screen with the camera of this frame: its interpolated box, every corner
     * projected. A box with a corner behind the camera, off screen or filling most of it counts
     * as not visible, and the panel goes back to its own position.
     */
    @EventTarget
    public void onRender3D(Render3DEvent event) {
        this.projected = null;
        if (!this.isEnabled() || !this.attach.getValue() || mc.theWorld == null || mc.thePlayer == null) return;
        EntityLivingBase entity = this.resolveTarget();
        if (entity == null) entity = this.shown;
        if (entity == null) return;

        float partial = event.getPartialTicks();
        IAccessorRenderManager renderManager = (IAccessorRenderManager) mc.getRenderManager();
        double ox = RenderUtil.lerpDouble(entity.posX, entity.lastTickPosX, partial) - entity.posX - renderManager.getRenderPosX();
        double oy = RenderUtil.lerpDouble(entity.posY, entity.lastTickPosY, partial) - entity.posY - renderManager.getRenderPosY();
        double oz = RenderUtil.lerpDouble(entity.posZ, entity.lastTickPosZ, partial) - entity.posZ - renderManager.getRenderPosZ();
        AxisAlignedBB bb = entity.getEntityBoundingBox();
        double minX = bb.minX + ox - 0.1, minY = bb.minY + oy - 0.1, minZ = bb.minZ + oz - 0.1;
        double maxX = bb.maxX + ox + 0.1, maxY = bb.maxY + oy + 0.1, maxZ = bb.maxZ + oz + 0.1;

        modelView.clear(); projection.clear(); viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        float screenW = mc.displayWidth, screenH = mc.displayHeight;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            screenCoords.clear();
            if (!GLU.gluProject((float) ((corner & 1) == 0 ? minX : maxX), (float) ((corner & 2) == 0 ? minY : maxY),
                    (float) ((corner & 4) == 0 ? minZ : maxZ), modelView, projection, viewport, screenCoords)) return;
            float depth = screenCoords.get(2);
            // gluProject mirrors points behind the camera onto the screen.
            if (!(depth >= 0.0F && depth < 1.0F)) return;
            float sx = screenCoords.get(0), sy = screenH - screenCoords.get(1);
            x0 = Math.min(x0, sx); y0 = Math.min(y0, sy);
            x1 = Math.max(x1, sx); y1 = Math.max(y1, sy);
        }
        if (x1 < 0.0F || y1 < 0.0F || x0 > screenW || y0 > screenH) return;
        if ((x1 - x0) * (y1 - y0) > screenW * screenH * 0.6F) return;
        this.boxX0 = x0; this.boxY0 = y0; this.boxX1 = x1; this.boxY1 = y1;
        this.projected = entity;
        this.projectedFrame = myau.ui.GlassTheme.frame();
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;

        EntityLivingBase entityLivingBase = this.target;
        this.target = this.resolveTarget();

        // The panel pops in when a target appears and shrinks away after it is gone; while it
        // leaves, the last target keeps being drawn.
        float dt = myau.ui.glass.Anim.dt();
        float appear = this.animations.getValue()
                ? this.appear.update(this.target != null ? 1.0F : 0.0F, dt)
                : (this.appear.value = this.target != null ? 1.0F : 0.0F);
        EntityLivingBase previous = this.shown;
        if (this.target != null) this.shown = this.target;
        else if (appear < 0.02F) this.shown = null;
        EntityLivingBase shown = this.shown;

        if (shown != null) {
            float abs = shown.getAbsorptionAmount();
            float actualHealth = shown.getHealth() + abs;
            float maxHealth = Math.max(1.0F, shown.getMaxHealth() + abs);

            if (this.target != null && this.target != entityLivingBase) {
                this.headTexture = null;
                this.animatedHealth = actualHealth;
                this.animatedWhiteHealth = actualHealth;
            }

            if (this.animations.getValue()) {
                // Time based, so the bar moves at the same speed at any frame rate.
                this.animatedHealth += (actualHealth - this.animatedHealth) * (1.0F - (float) Math.exp(-12.0F * dt));
                this.animatedWhiteHealth += (this.animatedHealth - this.animatedWhiteHealth) * (1.0F - (float) Math.exp(-5.0F * dt));
            } else {
                this.animatedHealth = actualHealth;
                this.animatedWhiteHealth = actualHealth;
            }

            ResourceLocation resourceLocation = this.getSkin(shown);
            if (resourceLocation != null) this.headTexture = resourceLocation;

            Color targetColor = this.getTargetColor(shown);
            myau.ui.glass.Draw.useGuiScale();
            float gui = myau.ui.glass.Draw.scale();
            // The same numbers ScaledResolution gives, without making one every frame.
            float guiW = (float) Math.ceil(mc.displayWidth / (double) gui);
            float guiH = (float) Math.ceil(mc.displayHeight / (double) gui);

            myau.ui.glass.SmoothFont nameFont = myau.ui.glass.SmoothFont.bold(10);
            myau.ui.glass.SmoothFont healthFont = myau.ui.glass.SmoothFont.regular(8.5f);
            String targetNameText = TeamUtil.stripName(shown);
            float targetNameWidth = nameFont.width(targetNameText);
            if (actualHealth != this.textHealth || maxHealth != this.textMax) {
                this.textHealth = actualHealth;
                this.textMax = maxHealth;
                this.healthText = ChatColors.formatColor(String.format("&7HP &f%s &7/ &f%s", healthFormat.format(actualHealth), healthFormat.format(maxHealth)));
            }
            String healthText = this.healthText;
            float healthTextWidth = healthFont.width(healthText);

            float headSize = this.head.getValue() && this.headTexture != null ? 30.0F : 0.0F;
            float contentWidth = Math.max(targetNameWidth, healthTextWidth) + 15.0F;
            float barTotalWidth = headSize > 0 ? headSize + contentWidth + 14.0F : contentWidth + 18.0F;
            float barTotalHeight = 38.0F;

            float posX = this.offX.getValue().floatValue() / this.scale.getValue();
            switch (this.posX.getValue()) {
                case 1: posX += guiW / this.scale.getValue() / 2.0F - barTotalWidth / 2.0F; break;
                case 2: posX = guiW / this.scale.getValue() - barTotalWidth - posX; break;
            }
            float posY = this.offY.getValue().floatValue() / this.scale.getValue();
            switch (this.posY.getValue()) {
                case 1: posY += guiH / this.scale.getValue() / 2.0F - barTotalHeight / 2.0F; break;
                case 2: posY = guiH / this.scale.getValue() - barTotalHeight - posY; break;
            }

            // Everything below is drawn in panel-local units, scaled by the module's scale.
            float s = this.scale.getValue();
            this.place(shown, previous == null, posX * s, posY * s, barTotalWidth * s, barTotalHeight * s, gui, guiW, guiH, dt);
            GlStateManager.disableDepth();
            myau.ui.glass.Draw.pushTransform(0, 0, s, this.drawX, this.drawY);
            float visible = Math.max(0.0F, Math.min(1.0F, appear));
            myau.ui.glass.Draw.pushTransform(barTotalWidth / 2.0F, barTotalHeight / 2.0F, 0.88F + 0.12F * appear, 0, (1.0F - visible) * 4.0F);
            myau.ui.glass.Draw.pushAlpha(visible);

            if (this.background.getValue() > 0) {
                myau.ui.GlassTheme.panel(0.0F, 0.0F, barTotalWidth, barTotalHeight, myau.ui.GlassTheme.R_CARD, 0.9F);
                // "background" deepens the glass tint on top of the standard panel
                myau.ui.glass.Draw.rect(0.0F, 0.0F, barTotalWidth, barTotalHeight, myau.ui.GlassTheme.R_CARD,
                        myau.ui.glass.Draw.alpha(0x0d0d10, this.background.getValue() / 100.0F * 0.45F));
            }

            // Health bar: track, delayed white ghost, accent fill
            float barX = headSize > 0 ? headSize + 9.0F : 9.0F;
            float barY = barTotalHeight - 11.0F;
            float barW = barTotalWidth - 9.0F - barX;
            float barH = 4.5F, barRadius = barH / 2.0F;
            float hpRatio = Math.max(0, Math.min(this.animatedHealth / maxHealth, 1.0F));
            float whiteHpRatio = Math.max(0, Math.min(this.animatedWhiteHealth / maxHealth, 1.0F));
            int accent = 0xff000000 | targetColor.getRGB();

            myau.ui.glass.Draw.rect(barX, barY, barW, barH, barRadius, 0x66000000);
            if (whiteHpRatio > 0) {
                myau.ui.glass.Draw.rect(barX, barY, Math.max(barH, barW * whiteHpRatio), barH, barRadius, 0xd2ffffff);
            }
            if (hpRatio > 0) {
                float fill = Math.max(barH, barW * hpRatio);
                myau.ui.glass.Draw.glow(barX, barY, fill, barH, barRadius, 5.0F, myau.ui.glass.Draw.alpha(accent, 0.4F));
                myau.ui.glass.Draw.gradientH(barX, barY, fill, barH, barRadius, accent, 0xff000000 | targetColor.brighter().getRGB());
            }

            float textX = headSize > 0 ? headSize + 9.0F : 9.0F;
            nameFont.drawShadowed(targetNameText, textX, 4.5F, myau.ui.GlassTheme.TEXT);
            healthFont.draw(healthText, textX, 16.5F, myau.ui.GlassTheme.MUTED);

            if (this.head.getValue() && this.headTexture != null) {
                GlStateManager.enableBlend();
                GlStateManager.color(1.0F, 1.0F, 1.0F, myau.ui.glass.Draw.alpha());
                mc.getTextureManager().bindTexture(this.headTexture);
                Gui.drawScaledCustomSizeModalRect(5, 4, 8.0F, 8.0F, 8, 8, 30, 30, 64.0F, 64.0F);
                Gui.drawScaledCustomSizeModalRect(5, 4, 40.0F, 8.0F, 8, 8, 30, 30, 64.0F, 64.0F);
                // Rounded rim over the square head texture
                myau.ui.glass.Draw.outline(5.0F, 4.0F, 30.0F, 30.0F, 3.0F, 0.75F, 0x50ffffff, 0x18ffffff);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }

            myau.ui.glass.Draw.popAlpha();
            myau.ui.glass.Draw.popTransform();
            myau.ui.glass.Draw.popTransform();
            GlStateManager.enableDepth();
        }
    }

    /**
     * Moves the panel towards where it belongs this frame: beside the target's box when it is on
     * screen, at the position settings otherwise. It follows one target closely, glides when it
     * is sent somewhere new, and stays where it is while it shrinks away.
     *
     * @param fresh  the panel was not on screen last frame: it appears in place
     * @param fixedX where the position settings put it, GUI units
     */
    private void place(EntityLivingBase shown, boolean fresh, float fixedX, float fixedY, float panelW, float panelH,
                       float gui, float guiW, float guiH, float dt) {
        float goalX = fixedX, goalY = fixedY;
        boolean onTarget = this.attach.getValue() && this.projected == shown && this.projectedFrame == myau.ui.GlassTheme.frame();
        if (onTarget) {
            float margin = 4.0F, gap = 6.0F;
            float bx0 = this.boxX0 / gui, by0 = this.boxY0 / gui, bx1 = this.boxX1 / gui, by1 = this.boxY1 / gui;
            float right = bx1 + gap, left = bx0 - gap - panelW;
            boolean fitsRight = right + panelW <= guiW - margin, fitsLeft = left >= margin;
            if (fresh || shown != this.followed) {
                // A new target starts on the right whenever that fits.
                this.leftSide = !fitsRight && fitsLeft;
            } else if (this.leftSide ? !fitsLeft && fitsRight : !fitsRight && fitsLeft) {
                // Otherwise it changes sides only when its side no longer fits: no flicker at the edge.
                this.leftSide = !this.leftSide;
            }
            // Head and shoulders height, kept inside the screen.
            goalX = clamp(this.leftSide ? left : right, margin, guiW - margin - panelW);
            goalY = clamp(by0 + (by1 - by0) * 0.12F, margin, guiH - margin - panelH);
        }
        boolean moving = this.target != null;
        if (fresh || !this.animations.getValue() || myau.ui.glass.Anim.reduced()) {
            this.drawX = goalX;
            this.drawY = goalY;
            this.glide = 1.0F;
        } else if (moving) {
            if (shown != this.followed || onTarget != this.wasOnTarget) this.glide = 0.0F;
            // Gliding starts slow and tightens into close following, so it never snaps at the end.
            float rate = 9.0F + 19.0F * this.glide * this.glide;
            float k = 1.0F - (float) Math.exp(-rate * dt);
            this.drawX += (goalX - this.drawX) * k;
            this.drawY += (goalY - this.drawY) * k;
            this.glide = Math.min(1.0F, this.glide + dt / 0.4F);
        }
        if (moving || fresh) {
            this.followed = shown;
            this.wasOnTarget = onTarget;
        }
    }

    private static float clamp(float value, float min, float max) {
        if (max < min) return min;
        return value < min ? min : Math.min(value, max);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.SEND && event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            if (packet.getAction() != Action.ATTACK) return;
            Entity entity = packet.getEntityFromWorld(mc.theWorld);
            if (entity instanceof EntityLivingBase) {
                if (entity instanceof EntityArmorStand) return;
                this.lastAttackTimer.reset();
                this.lastTarget = (EntityLivingBase) entity;
            }
        }
    }
}
