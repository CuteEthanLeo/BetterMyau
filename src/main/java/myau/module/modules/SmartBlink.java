package myau.module.modules;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import myau.util.TargetUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;

/**
 * Holds outgoing packets while an enemy closes in, and releases them when the enemy gets close
 * to us or to the spot we started from, on a hit, on damage or after a time limit.
 */
public class SmartBlink extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String PREFIX = "&7[&cSmartBlink&7] ";

    public final FloatProperty startDistance = new FloatProperty("start-distance", 5.0F, 1.0F, 20.0F);
    public final FloatProperty stopDistance = new FloatProperty("stop-distance", 2.5F, 1.0F, 20.0F);
    public final FloatProperty maxBlinkTime = new FloatProperty("max-blink-time", 1.0F, 1.0F, 30.0F);
    public final FloatProperty cooldownTime = new FloatProperty("cooldown-time", 3.0F, 1.0F, 10.0F);
    public final FloatProperty markStopDistance = new FloatProperty("mark-stop-distance", 2.0F, 0.5F, 10.0F);
    public final BooleanProperty pulseOnDamage = new BooleanProperty("pulse-on-damage", true);
    public final BooleanProperty pulseOnHit = new BooleanProperty("pulse-on-hit", true);
    public final BooleanProperty teams = new BooleanProperty("teams", true);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);
    public final BooleanProperty markPosition = new BooleanProperty("mark-position", true);
    public final BooleanProperty markFilled = new BooleanProperty("mark-filled", true, this.markPosition::getValue);
    public final ColorProperty markColor = new ColorProperty("mark-color", 0xFF0000, this.markPosition::getValue);
    public final ColorProperty markOutlineColor = new ColorProperty("mark-outline-color", 0xFF0000, this.markPosition::getValue);
    public final BooleanProperty showProgressBar = new BooleanProperty("show-progress-bar", true);
    public final FloatProperty progressBarX = new FloatProperty("progress-bar-x", 50.0F, 0.0F, 100.0F, this.showProgressBar::getValue);
    public final FloatProperty progressBarY = new FloatProperty("progress-bar-y", 80.0F, 0.0F, 100.0F, this.showProgressBar::getValue);
    public final FloatProperty progressBarWidth = new FloatProperty("progress-bar-width", 150.0F, 50.0F, 300.0F, this.showProgressBar::getValue);
    public final FloatProperty progressBarHeight = new FloatProperty("progress-bar-height", 10.0F, 5.0F, 30.0F, this.showProgressBar::getValue);
    public final ColorProperty progressBarBackground = new ColorProperty("progress-bar-background", 0x000000, this.showProgressBar::getValue);
    public final ColorProperty progressBarFill = new ColorProperty("progress-bar-fill", 0x00C8FF, this.showProgressBar::getValue);
    public final ColorProperty progressBarBorder = new ColorProperty("progress-bar-border", 0xFFFFFF, this.showProgressBar::getValue);
    public final BooleanProperty progressBarText = new BooleanProperty("progress-bar-text", true, this.showProgressBar::getValue);
    public final ColorProperty progressBarTextColor = new ColorProperty(
            "progress-bar-text-color", 0xFFFFFF, () -> this.showProgressBar.getValue() && this.progressBarText.getValue()
    );
    public final BooleanProperty chatNotify = new BooleanProperty("chat-notify", true);
    public final BooleanProperty flagPause = new BooleanProperty("flag-pause", true);
    public final IntProperty flagPauseTime = new IntProperty("flag-pause-time", 5, 1, 60, this.flagPause::getValue);

    private final TimerUtil flagTimer = new TimerUtil();
    private boolean blinking = false;
    private AxisAlignedBB startPosition = null;
    private long blinkStartTime = 0L;
    private EntityLivingBase target = null;
    private boolean pausedByFlag = false;
    private boolean inCooldown = false;
    private long cooldownStartTime = 0L;
    private int lastHurtTime = 0;
    /** Set on the network thread when the server teleports us mid-blink; handled on the next update. */
    private volatile boolean flagPending = false;

    public SmartBlink() {
        super("SmartBlink", false);
    }

    private void info(String message) {
        if (this.chatNotify.getValue()) {
            ChatUtil.sendFormatted(Myau.clientName + PREFIX + message);
        }
    }

    /** Releases everything held back and clears the blink state. */
    private void stopBlink(boolean cooldown) {
        Myau.blinkManager.setBlinkState(false, BlinkModules.SMART_BLINK);
        this.blinking = false;
        this.startPosition = null;
        this.blinkStartTime = 0L;
        if (cooldown) {
            this.inCooldown = true;
            this.cooldownStartTime = System.currentTimeMillis();
        }
    }

    private boolean startBlink(EntityPlayerSP player) {
        BlinkModules owner = Myau.blinkManager.getBlinkingModule();
        if (owner != BlinkModules.NONE && owner != BlinkModules.SMART_BLINK && Myau.blinkManager.isBlinking()) {
            // Another module is holding packets right now.
            return false;
        }
        if (!Myau.blinkManager.setBlinkState(true, BlinkModules.SMART_BLINK)) {
            return false;
        }
        this.blinking = true;
        this.startPosition = new AxisAlignedBB(
                player.posX - 0.3, player.posY, player.posZ - 0.3,
                player.posX + 0.3, player.posY + 1.8, player.posZ + 0.3
        );
        this.blinkStartTime = System.currentTimeMillis();
        return true;
    }

    private EntityLivingBase findTarget() {
        EntityLivingBase nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!TargetUtil.isSelected(entity, true, false, false, this.teams.getValue(), this.botCheck.getValue())) {
                continue;
            }
            double distance = RotationUtil.distanceToEntity(entity);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = (EntityLivingBase) entity;
            }
        }
        return nearest;
    }

    @Override
    public void onEnabled() {
        this.flagPending = false;
        this.lastHurtTime = 0;
    }

    @Override
    public void onDisabled() {
        if (this.blinking) {
            this.stopBlink(false);
            this.info("&fStopped blinking");
        }
        this.startPosition = null;
        this.target = null;
        this.pausedByFlag = false;
        this.inCooldown = false;
        this.blinkStartTime = 0L;
        this.flagPending = false;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (this.blinking) {
            this.stopBlink(false);
        }
        this.target = null;
        this.pausedByFlag = false;
        this.inCooldown = false;
        this.flagPending = false;
        this.lastHurtTime = 0;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.theWorld == null) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;

        if (this.blinking && Myau.blinkManager.getBlinkingModule() != BlinkModules.SMART_BLINK) {
            // Another module took the blink over (and released our packets with its own).
            this.blinking = false;
            this.startPosition = null;
            this.blinkStartTime = 0L;
            this.inCooldown = true;
            this.cooldownStartTime = System.currentTimeMillis();
        }

        if (this.flagPending) {
            this.flagPending = false;
            if (this.blinking) {
                this.stopBlink(false);
                this.pausedByFlag = true;
                this.flagTimer.reset();
                this.info("&cFlag detected! Pausing for " + this.flagPauseTime.getValue() + "s");
            }
        }

        if (this.blinking && (player.isDead || player.ticksExisted <= 10)) {
            this.stopBlink(true);
            this.info("&fStopped blinking (player dead/respawn)");
        }

        if (this.pulseOnDamage.getValue() && this.blinking && player.hurtTime > 0 && this.lastHurtTime == 0) {
            this.stopBlink(true);
            this.info("&fPulse on damage");
        }
        this.lastHurtTime = player.hurtTime;

        if (this.pausedByFlag) {
            if (!this.flagTimer.hasTimeElapsed((long) this.flagPauseTime.getValue() * 1000L)) {
                return;
            }
            this.pausedByFlag = false;
            this.info("&fResumed after flag cooldown");
        }

        if (this.inCooldown) {
            if (System.currentTimeMillis() - this.cooldownStartTime < (long) (this.cooldownTime.getValue() * 1000.0F)) {
                return;
            }
            this.inCooldown = false;
            this.info("&aCooldown finished, ready to blink");
        }

        this.target = this.findTarget();
        EntityLivingBase currentTarget = this.target;
        if (currentTarget == null) {
            if (this.blinking) {
                this.stopBlink(false);
                this.info("&fStopped blinking (no target)");
            }
            return;
        }

        double distance = RotationUtil.distanceToEntity(currentTarget);

        if (this.blinking) {
            float elapsedSeconds = (float) (System.currentTimeMillis() - this.blinkStartTime) / 1000.0F;
            if (elapsedSeconds >= this.maxBlinkTime.getValue()) {
                this.stopBlink(true);
                this.info("&fStopped blinking (max time reached: " + this.maxBlinkTime.getValue() + "s)");
                return;
            }
            AxisAlignedBB box = this.startPosition;
            if (box != null) {
                double dx = currentTarget.posX - (box.minX + box.maxX) / 2.0;
                double dy = currentTarget.posY - (box.minY + box.maxY) / 2.0;
                double dz = currentTarget.posZ - (box.minZ + box.maxZ) / 2.0;
                double distanceToMark = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distanceToMark <= (double) this.markStopDistance.getValue()) {
                    this.stopBlink(true);
                    this.info("&fStopped blinking (enemy near mark: " + String.format("%.1f", distanceToMark) + "m)");
                    return;
                }
            }
        }

        if (!this.blinking && distance <= (double) this.startDistance.getValue() && !this.pausedByFlag && !this.inCooldown) {
            if (this.startBlink(player)) {
                this.info("&aStarted blinking");
            }
        }

        if (this.blinking && distance <= (double) this.stopDistance.getValue()) {
            this.stopBlink(true);
            this.info("&fStopped blinking (reached target)");
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || !this.flagPause.getValue()) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook && this.blinking) {
            this.flagPending = true;
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || !this.pulseOnHit.getValue() || !this.blinking) {
            return;
        }
        this.stopBlink(true);
        this.info("&fPulse on hit");
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || !this.markPosition.getValue() || !this.blinking) {
            return;
        }
        AxisAlignedBB box = this.startPosition;
        if (box == null) {
            return;
        }
        IAccessorRenderManager renderManager = (IAccessorRenderManager) mc.getRenderManager();
        AxisAlignedBB renderBox = box.offset(-renderManager.getRenderPosX(), -renderManager.getRenderPosY(), -renderManager.getRenderPosZ());
        int fill = this.markColor.getValue();
        int outline = this.markOutlineColor.getValue();
        RenderUtil.enableRenderState();
        if (this.markFilled.getValue()) {
            RenderUtil.drawFilledBox(renderBox, fill >> 16 & 0xFF, fill >> 8 & 0xFF, fill & 0xFF);
        }
        RenderUtil.drawBoundingBox(renderBox, outline >> 16 & 0xFF, outline >> 8 & 0xFF, outline & 0xFF, 255, 2.0F);
        RenderUtil.disableRenderState();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !this.showProgressBar.getValue() || !this.blinking || mc.thePlayer == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        float width = this.progressBarWidth.getValue();
        float height = this.progressBarHeight.getValue();
        float x = (float) sr.getScaledWidth() * (this.progressBarX.getValue() / 100.0F) - width / 2.0F;
        float y = (float) sr.getScaledHeight() * (this.progressBarY.getValue() / 100.0F);
        float elapsedSeconds = (float) (System.currentTimeMillis() - this.blinkStartTime) / 1000.0F;
        float progress = Math.max(0.0F, Math.min(1.0F, elapsedSeconds / this.maxBlinkTime.getValue()));

        RenderUtil.enableRenderState();
        RenderUtil.drawRect(x, y, x + width, y + height, 150 << 24 | this.progressBarBackground.getValue());
        if (progress > 0.0F) {
            RenderUtil.drawRect(x, y, x + width * progress, y + height, 200 << 24 | this.progressBarFill.getValue());
        }
        RenderUtil.drawOutlineRect(x, y, x + width, y + height, 1.0F, 0, 200 << 24 | this.progressBarBorder.getValue());
        RenderUtil.disableRenderState();

        if (this.progressBarText.getValue()) {
            String text = String.format("%.1fs / %.1fs", elapsedSeconds, this.maxBlinkTime.getValue());
            float textX = x + (width - (float) mc.fontRendererObj.getStringWidth(text)) / 2.0F;
            float textY = y + (height - (float) mc.fontRendererObj.FONT_HEIGHT) / 2.0F + 1.0F;
            mc.fontRendererObj.drawStringWithShadow(text, textX, textY, 0xFF000000 | this.progressBarTextColor.getValue());
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.pausedByFlag) {
            return new String[]{"Paused(" + this.flagPauseTime.getValue() + "s)"};
        }
        if (this.inCooldown) {
            float remaining = (this.cooldownTime.getValue() * 1000.0F - (float) (System.currentTimeMillis() - this.cooldownStartTime)) / 1000.0F;
            return new String[]{"Cooldown(" + String.format("%.1f", Math.max(0.0F, remaining)) + "s)"};
        }
        if (this.blinking) {
            return new String[]{String.format("%.1fs", (float) (System.currentTimeMillis() - this.blinkStartTime) / 1000.0F)};
        }
        return new String[]{"Waiting"};
    }
}
