package myau.module.modules;

import myau.events.Render2DEvent;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.enums.DelayModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.MoveEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import myau.util.MoveUtil;
import myau.util.RandomUtil;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import myau.util.TargetUtil;
import myau.util.sim.SimulatedPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C12PacketUpdateSign;
import net.minecraft.network.play.client.C19PacketResourcePackStatus;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * Slows the game timer down and then speeds it up when a target comes into range, so the
 * player closes the last blocks faster than the target expects.
 */
public class TimerRange extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int NORMAL = 0;
    private static final int SMART = 1;
    private static final int MODERN = 2;
    private static final int SEARCH_GRID = 5;

    public final ModeProperty timerMode = new ModeProperty("timer-mode", MODERN, new String[]{"Normal", "Smart", "Modern"});
    public final IntProperty ticks = new IntProperty("ticks", 10, 1, 20);
    public final FloatProperty timerBoost = new FloatProperty("timer-boost", 1.5F, 0.01F, 35.0F);
    public final FloatProperty minBoostDelay = new FloatProperty("min-boost-delay", 0.5F, 0.1F, 1.0F);
    public final FloatProperty maxBoostDelay = new FloatProperty("max-boost-delay", 0.55F, 0.1F, 1.0F);
    public final FloatProperty timerCharged = new FloatProperty("timer-charged", 0.45F, 0.05F, 5.0F);
    public final FloatProperty minChargedDelay = new FloatProperty("min-charged-delay", 0.75F, 0.1F, 1.0F);
    public final FloatProperty maxChargedDelay = new FloatProperty("max-charged-delay", 0.9F, 0.1F, 1.0F);
    public final FloatProperty range = new FloatProperty("range", 3.5F, 1.0F, 5.0F, () -> this.timerMode.getValue() == NORMAL);
    public final IntProperty cooldownTick = new IntProperty("cooldown-tick", 10, 1, 50, () -> this.timerMode.getValue() == NORMAL);
    public final FloatProperty minRange = new FloatProperty("min-range", 2.5F, 2.0F, 8.0F, () -> this.timerMode.getValue() != NORMAL);
    public final FloatProperty maxRange = new FloatProperty("max-range", 3.0F, 2.0F, 8.0F, () -> this.timerMode.getValue() != NORMAL);
    public final FloatProperty scanRange = new FloatProperty("scan-range", 8.0F, 2.0F, 12.0F, () -> this.timerMode.getValue() != NORMAL);
    public final IntProperty minTickDelay = new IntProperty("min-tick-delay", 30, 1, 200, () -> this.timerMode.getValue() != NORMAL);
    public final IntProperty maxTickDelay = new IntProperty("max-tick-delay", 60, 1, 200, () -> this.timerMode.getValue() != NORMAL);
    public final BooleanProperty blink = new BooleanProperty("blink", false);
    public final IntProperty predictClientMovement = new IntProperty("predict-client-movement", 2, 0, 5);
    public final FloatProperty predictEnemyPosition = new FloatProperty("predict-enemy-position", 1.5F, -1.0F, 2.0F);
    public final FloatProperty maxAngleDifference = new FloatProperty("max-angle-difference", 5.0F, 5.0F, 90.0F, () -> this.timerMode.getValue() == MODERN);
    public final ModeProperty mark = new ModeProperty("mark", 0, new String[]{"Off", "Box", "Platform"}, () -> this.timerMode.getValue() == MODERN);
    public final BooleanProperty outline = new BooleanProperty("outline", false, () -> this.timerMode.getValue() == MODERN && this.mark.getValue() == 1);
    public final BooleanProperty onWeb = new BooleanProperty("on-web", false);
    public final BooleanProperty onLiquid = new BooleanProperty("on-liquid", false);
    public final BooleanProperty onForwardOnly = new BooleanProperty("on-forward-only", true);
    public final BooleanProperty resetOnLagback = new BooleanProperty("reset-on-lagback", false);
    public final BooleanProperty resetOnKnockback = new BooleanProperty("reset-on-knockback", false);
    public final BooleanProperty chatDebug = new BooleanProperty("chat-debug", true, () -> this.resetOnLagback.getValue() || this.resetOnKnockback.getValue());
    public final BooleanProperty notificationDebug = new BooleanProperty("notification-debug", false, () -> this.resetOnLagback.getValue() || this.resetOnKnockback.getValue());

    private int playerTicks = 0;
    private int smartTick = 0;
    private int cooldownTicks = 0;
    private float randomRange = 0.0F;
    private boolean blinked = false;
    private boolean shouldReset = false;
    private boolean confirmTick = false;
    private boolean confirmAttack = false;

    public TimerRange() {
        super("TimerRange", false);
    }

    @Override
    public void onDisabled() {
        this.resetAll();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        this.resetAll();
    }

    private void resetAll() {
        MoveUtil.setTimer(1.0F);
        this.releaseBlink();

        this.smartTick = 0;
        this.cooldownTicks = 0;
        this.playerTicks = 0;

        this.shouldReset = false;
        this.blinked = false;

        this.confirmTick = false;
        this.confirmAttack = false;
    }

    /** Attack event (Normal & Smart mode). */
    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }

        if (!(event.getTarget() instanceof EntityLivingBase) && this.playerTicks >= 1) {
            this.shouldResetTimer();
            return;
        } else {
            this.confirmAttack = true;
        }

        Entity targetEntity = event.getTarget();
        if (targetEntity == null) {
            return;
        }
        double entityDistance = RotationUtil.distanceToEntity(targetEntity);
        int randomTickDelay = this.randomTickDelay();

        if (!this.updateDistance(targetEntity) || this.isInWeb(player) && !this.onWeb.getValue() || MoveUtil.isInLiquid() && !this.onLiquid.getValue()) {
            return;
        }

        this.smartTick++;
        this.cooldownTicks++;

        boolean shouldSlowed;
        switch (this.timerMode.getValue()) {
            case NORMAL:
                shouldSlowed = this.cooldownTicks >= this.cooldownTick.getValue() && entityDistance <= this.range.getValue();
                break;
            case SMART:
                shouldSlowed = this.smartTick >= randomTickDelay && entityDistance <= this.randomRange;
                break;
            default:
                shouldSlowed = false;
        }

        if (shouldSlowed && this.confirmAttack) {
            if (this.updateDistance(targetEntity)) {
                this.confirmAttack = false;
                this.playerTicks = this.ticks.getValue();
                this.cooldownTicks = 0;
                this.smartTick = 0;
            }
        } else {
            this.shouldResetTimer();
        }
    }

    /** Move event (Modern mode). */
    @EventTarget
    public void onMove(MoveEvent event) {
        if (!this.isEnabled() || this.timerMode.getValue() != MODERN) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }

        EntityLivingBase nearbyEntity = this.getNearestEntityInRange();
        if (nearbyEntity == null) {
            return;
        }

        int randomTickDelay = this.randomTickDelay();

        if (!this.updateDistance(nearbyEntity) || this.isInWeb(player) && !this.onWeb.getValue() || MoveUtil.isInLiquid() && !this.onLiquid.getValue()) {
            return;
        }

        boolean moving = this.isPlayerMoving();
        if (moving) {
            this.smartTick++;

            if (this.smartTick >= randomTickDelay) {
                this.confirmTick = true;
                this.smartTick = 0;
            }
        } else {
            this.smartTick = 0;
        }

        if (moving) {
            if (this.isLookingOnEntity(nearbyEntity, this.maxAngleDifference.getValue())) {
                double entityDistance = RotationUtil.distanceToEntity(nearbyEntity);
                if (this.confirmTick && entityDistance >= this.randomRange && entityDistance <= this.upperRange()) {
                    if (this.updateDistance(nearbyEntity)) {
                        this.playerTicks = this.ticks.getValue();
                        this.confirmTick = false;
                    }
                }
            } else {
                this.shouldResetTimer();
            }
        } else {
            this.shouldResetTimer();
        }
    }

    /**
     * True when the target (led by its last movement) can be hit from where the player will be
     * after the predicted client ticks.
     */
    private boolean updateDistance(Entity entity) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return false;
        }

        double factor = 2.0 + this.predictEnemyPosition.getValue().doubleValue();
        float border = entity.getCollisionBorderSize();
        AxisAlignedBB boundingBox = entity.getEntityBoundingBox().expand(border, border, border).offset(
                (entity.posX - entity.prevPosX) * factor,
                (entity.posY - entity.prevPosY) * factor,
                (entity.posZ - entity.prevPosZ) * factor
        );

        SimulatedPlayer simPlayer = SimulatedPlayer.fromClientPlayer(player.movementInput);
        for (int i = 0; i < this.predictClientMovement.getValue() + 1; i++) {
            simPlayer.tick();
        }

        Vec3 eyes = new Vec3(simPlayer.posX, simPlayer.posY + (double) player.getEyeHeight(), simPlayer.posZ);
        float lookRange = this.timerMode.getValue() == NORMAL ? this.range.getValue() : this.randomRange;
        Reach reach = (Reach) Myau.moduleManager.modules.get(Reach.class);
        float attackRange = reach != null && reach.isEnabled() ? reach.range.getValue() : 3.0F;

        return this.canReach(boundingBox, eyes, Math.max(lookRange, attackRange));
    }

    /** Is any point of the box within {@code scanRange} of {@code eyes} and not behind blocks. */
    private boolean canReach(AxisAlignedBB box, Vec3 eyes, double scanRange) {
        if (box.isVecInside(eyes)) {
            return true;
        }
        if (this.isPointReachable(box, eyes, RotationUtil.clampVecToBox(eyes, box), scanRange)) {
            return true;
        }
        for (int xi = 0; xi < SEARCH_GRID; xi++) {
            double x = box.minX + (box.maxX - box.minX) * xi / (SEARCH_GRID - 1);
            for (int yi = 0; yi < SEARCH_GRID; yi++) {
                double y = box.minY + (box.maxY - box.minY) * yi / (SEARCH_GRID - 1);
                for (int zi = 0; zi < SEARCH_GRID; zi++) {
                    double z = box.minZ + (box.maxZ - box.minZ) * zi / (SEARCH_GRID - 1);
                    if (this.isPointReachable(box, eyes, new Vec3(x, y, z), scanRange)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isPointReachable(AxisAlignedBB box, Vec3 eyes, Vec3 point, double scanRange) {
        Vec3 direction = point.subtract(eyes);
        double length = direction.lengthVector();
        if (length < 1.0E-6) {
            return true;
        }
        // Where a ray towards the point first enters the box
        Vec3 end = eyes.addVector(direction.xCoord / length * scanRange, direction.yCoord / length * scanRange, direction.zCoord / length * scanRange);
        MovingObjectPosition intercept = box.calculateIntercept(eyes, end);
        if (intercept == null || intercept.hitVec == null) {
            return false;
        }
        return eyes.distanceTo(intercept.hitVec) <= scanRange && mc.theWorld.rayTraceBlocks(eyes, intercept.hitVec) == null;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        // Randomize the timer & charged delay a bit, to bypass some AntiCheat
        float boostDelay = this.randomBetween(this.minBoostDelay.getValue(), this.maxBoostDelay.getValue());
        float chargedDelay = this.randomBetween(this.minChargedDelay.getValue(), this.maxChargedDelay.getValue());

        if (mc.thePlayer != null && mc.theWorld != null) {
            this.randomRange = this.randomBetween(this.minRange.getValue(), this.maxRange.getValue());
        }

        if (this.playerTicks <= 0) {
            this.shouldResetTimer();

            if (this.blinked) {
                this.releaseBlink();
                this.blinked = false;
            }

            return;
        }

        if (this.blink.getValue() && !this.blinked) {
            this.startBlink();
            this.blinked = true;
        }

        double tickProgress = (double) this.playerTicks / (double) this.ticks.getValue();
        float playerSpeed;
        if (tickProgress < boostDelay) {
            playerSpeed = this.timerBoost.getValue();
        } else if (tickProgress < chargedDelay) {
            playerSpeed = this.timerCharged.getValue();
        } else {
            playerSpeed = 1.0F;
        }

        float speedAdjustment = playerSpeed >= 0.0F ? playerSpeed : 1.0F + this.ticks.getValue() - this.playerTicks;
        MoveUtil.setTimer(Math.max(speedAdjustment, 0.0F));

        this.playerTicks--;
    }

    /** Held inbound packets never reach PacketEvent, so the flush conditions are polled here. */
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (Myau.delayManager.getDelayModule() != DelayModules.TIMER_RANGE) {
            return;
        }
        for (Packet<?> packet : Myau.delayManager.delayedPacket) {
            if (this.isInboundFlush(packet)) {
                this.releaseBlink();
                return;
            }
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || this.timerMode.getValue() != MODERN || this.mark.getValue() == 0) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        EntityLivingBase nearbyEntity = this.getNearestEntityInRange();
        if (nearbyEntity == null || RotationUtil.distanceToEntity(nearbyEntity) > this.scanRange.getValue()) {
            return;
        }

        boolean looking = this.isLookingOnEntity(nearbyEntity, this.maxAngleDifference.getValue());
        int red = looking ? 37 : 210;
        int green = looking ? 126 : 60;
        int blue = looking ? 255 : 60;

        RenderUtil.enableRenderState();
        if (this.mark.getValue() == 1) {
            RenderUtil.drawEntityBox(nearbyEntity, red, green, blue);
            if (this.outline.getValue()) {
                RenderUtil.drawEntityBoundingBox(nearbyEntity, red, green, blue, 95, 1.0F, 0.1);
            }
        } else {
            float partialTicks = event.getPartialTicks();
            double x = RenderUtil.lerpDouble(nearbyEntity.posX, nearbyEntity.lastTickPosX, partialTicks) - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
            double y = RenderUtil.lerpDouble(nearbyEntity.posY, nearbyEntity.lastTickPosY, partialTicks) - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
            double z = RenderUtil.lerpDouble(nearbyEntity.posZ, nearbyEntity.lastTickPosZ, partialTicks) - ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
            AxisAlignedBB box = nearbyEntity.getEntityBoundingBox().offset(x - nearbyEntity.posX, y - nearbyEntity.posY, z - nearbyEntity.posZ);
            RenderUtil.drawFilledBox(new AxisAlignedBB(box.minX, box.maxY + 0.2, box.minZ, box.maxX, box.maxY + 0.26, box.maxZ), red, green, blue);
        }
        RenderUtil.disableRenderState();
    }

    private boolean isPlayerMoving() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return false;
        }
        if (!this.onForwardOnly.getValue()) {
            return player.moveForward != 0.0F || player.moveStrafing != 0.0F;
        }
        return player.moveForward != 0.0F && player.moveStrafing == 0.0F;
    }

    private boolean isInWeb(EntityPlayerSP player) {
        return ((IAccessorEntity) player).getIsInWeb();
    }

    /** Is the player's view within {@code maxAngleDifference} degrees of the entity's eyes. */
    private boolean isLookingOnEntity(Entity entity, double maxAngleDifference) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return false;
        }
        if (entity == player) {
            return true;
        }
        double yaw = Math.toRadians(player.rotationYawHead);
        double pitch = Math.toRadians(player.rotationPitch);
        Vec3 lookVec = new Vec3(-Math.sin(yaw), -Math.sin(pitch), Math.cos(yaw)).normalize();
        Vec3 playerPos = player.getPositionVector().addVector(0.0, player.getEyeHeight(), 0.0);
        Vec3 entityPos = entity.getPositionVector().addVector(0.0, entity.getEyeHeight(), 0.0);
        Vec3 directionToEntity = entityPos.subtract(playerPos).normalize();

        return lookVec.dotProduct(directionToEntity) > Math.cos(Math.toRadians(maxAngleDifference));
    }

    /** The nearest selected entity inside the mode's range. */
    private EntityLivingBase getNearestEntityInRange() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        double limit = this.timerMode.getValue() == NORMAL ? this.range.getValue() : this.scanRange.getValue() + this.randomRange;
        EntityLivingBase nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!TargetUtil.isSelected(entity)) {
                continue;
            }
            double distance = RotationUtil.distanceToEntity(entity);
            if (distance <= limit && distance < best) {
                best = distance;
                nearest = (EntityLivingBase) entity;
            }
        }
        return nearest;
    }

    /** Separate condition to make it cleaner. */
    private void shouldResetTimer() {
        EntityLivingBase nearestEntity = this.getNearestEntityInRange();

        if (nearestEntity == null || nearestEntity.isDead) {
            if (!this.shouldReset) {
                MoveUtil.setTimer(1.0F);
                this.shouldReset = true;
            }
        } else {
            if (!this.shouldReset && MoveUtil.getTimer() != 1.0F) {
                MoveUtil.setTimer(1.0F);
                this.shouldReset = true;
            } else {
                this.shouldReset = false;
            }
        }
    }

    private void startBlink() {
        if (Myau.blinkManager.getBlinkingModule() == BlinkModules.NONE) {
            Myau.blinkManager.setBlinkState(true, BlinkModules.TIMER_RANGE);
        }
        if (Myau.delayManager.getDelayModule() == DelayModules.NONE) {
            Myau.delayManager.setDelayState(true, DelayModules.TIMER_RANGE);
        }
    }

    /**
     * Sends the held outbound packets and lets the held inbound ones through. Must run on the
     * client thread. Lagback / knockback among the held packets is handled here because held
     * packets never reach PacketEvent.
     */
    private void releaseBlink() {
        if (Myau.blinkManager != null && Myau.blinkManager.getBlinkingModule() == BlinkModules.TIMER_RANGE) {
            Myau.blinkManager.setBlinkState(false, BlinkModules.TIMER_RANGE);
        }
        if (Myau.delayManager == null || Myau.delayManager.getDelayModule() != DelayModules.TIMER_RANGE) {
            return;
        }
        boolean lagback = false;
        boolean knockback = false;
        for (Packet<?> packet : Myau.delayManager.delayedPacket) {
            if (packet instanceof S08PacketPlayerPosLook) {
                lagback = true;
            } else if (packet instanceof S12PacketEntityVelocity && mc.thePlayer != null
                    && ((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId()) {
                knockback = true;
            }
        }
        try {
            Myau.delayManager.setDelayState(false, DelayModules.TIMER_RANGE);
        } catch (Exception e) {
            Myau.delayManager.delayedPacket.clear();
        }
        if (this.isEnabled()) {
            if (lagback && this.resetOnLagback.getValue()) {
                this.debugReset("Lagback");
            }
            if (knockback && this.resetOnKnockback.getValue()) {
                this.debugReset("Knockback");
            }
        }
    }

    private boolean isInboundFlush(Packet<?> packet) {
        if (packet instanceof S08PacketPlayerPosLook) {
            return true;
        }
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            return explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F || explosion.func_149147_e() != 0.0F;
        }
        if (packet instanceof S06PacketUpdateHealth) {
            return mc.thePlayer != null && ((S06PacketUpdateHealth) packet).getHealth() < mc.thePlayer.getHealth();
        }
        return false;
    }

    private void debugReset(String reason) {
        this.shouldResetTimer();

        if (this.shouldReset) {
            if (this.chatDebug.getValue()) {
                ChatUtil.sendFormatted(Myau.clientName + "&7" + reason + " Received | Timer Reset");
            }
            if (this.notificationDebug.getValue()) {
                HUD.notify("TimerRange", reason + " Received - Resetting Timer", true);
            }

            this.shouldReset = false;
        }
    }

    private void runOnClientThread(Runnable action) {
        if (mc.isCallingFromMinecraftThread()) {
            action.run();
        } else {
            mc.addScheduledTask(action);
        }
    }

    /** Lagback reset is inspired from Nextgen TimerRange. Resets the timer on lagback & knockback. */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || player.isDead) {
            return;
        }
        Packet<?> packet = event.getPacket();

        if (this.blinked) {
            // Flush on doing / getting an action
            if (packet instanceof C07PacketPlayerDigging || packet instanceof C12PacketUpdateSign
                    || packet instanceof C19PacketResourcePackStatus || this.isInboundFlush(packet)) {
                this.runOnClientThread(this::releaseBlink);
            }
        }

        if (event.getType() != EventType.RECEIVE) {
            return;
        }

        if (this.resetOnLagback.getValue() && packet instanceof S08PacketPlayerPosLook) {
            this.runOnClientThread(() -> this.debugReset("Lagback"));
        }

        if (this.resetOnKnockback.getValue() && packet instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) packet).getEntityID() == player.getEntityId()) {
            this.runOnClientThread(() -> this.debugReset("Knockback"));
        }
    }

    private float upperRange() {
        return Math.max(this.minRange.getValue(), this.maxRange.getValue());
    }

    private float randomBetween(float a, float b) {
        float min = Math.min(a, b);
        float max = Math.max(a, b);
        return min == max ? min : RandomUtil.nextFloat(min, max);
    }

    private int randomTickDelay() {
        int min = Math.min(this.minTickDelay.getValue(), this.maxTickDelay.getValue());
        int max = Math.max(this.minTickDelay.getValue(), this.maxTickDelay.getValue());
        return (int) RandomUtil.nextLong(min, max);
    }

    @Override
    public void verifyValue(String name) {
        switch (name) {
            case "min-boost-delay":
                if (this.minBoostDelay.getValue() > this.maxBoostDelay.getValue()) {
                    this.maxBoostDelay.setValue(this.minBoostDelay.getValue());
                }
                break;
            case "max-boost-delay":
                if (this.minBoostDelay.getValue() > this.maxBoostDelay.getValue()) {
                    this.minBoostDelay.setValue(this.maxBoostDelay.getValue());
                }
                break;
            case "min-charged-delay":
                if (this.minChargedDelay.getValue() > this.maxChargedDelay.getValue()) {
                    this.maxChargedDelay.setValue(this.minChargedDelay.getValue());
                }
                break;
            case "max-charged-delay":
                if (this.minChargedDelay.getValue() > this.maxChargedDelay.getValue()) {
                    this.minChargedDelay.setValue(this.maxChargedDelay.getValue());
                }
                break;
            case "min-range":
                if (this.minRange.getValue() > this.maxRange.getValue()) {
                    this.maxRange.setValue(this.minRange.getValue());
                }
                break;
            case "max-range":
                if (this.minRange.getValue() > this.maxRange.getValue()) {
                    this.minRange.setValue(this.maxRange.getValue());
                }
                if (this.scanRange.getValue() < this.maxRange.getValue()) {
                    this.scanRange.setValue(this.maxRange.getValue());
                }
                break;
            case "scan-range":
                if (this.scanRange.getValue() < this.maxRange.getValue()) {
                    this.scanRange.setValue(this.maxRange.getValue());
                }
                break;
            case "min-tick-delay":
                if (this.minTickDelay.getValue() > this.maxTickDelay.getValue()) {
                    this.maxTickDelay.setValue(this.minTickDelay.getValue());
                }
                break;
            case "max-tick-delay":
                if (this.minTickDelay.getValue() > this.maxTickDelay.getValue()) {
                    this.minTickDelay.setValue(this.maxTickDelay.getValue());
                }
                break;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.timerMode.getModeString()};
    }

    // ---------------------------------------------------------------- dynamic island


    /** While the timer boost runs: the timer speed and the ticks left, as a bar that runs down. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || this.playerTicks <= 0) return;
        float total = Math.max(1, this.ticks.getValue());
        myau.ui.glass.Island.live("timerrange", myau.ui.glass.Island.P_TIMER,
                "Timer §f" + String.format("%.2fx", myau.util.MoveUtil.getTimer()) + " §7" + this.playerTicks + "t",
                myau.ui.GlassTheme.accent(), Math.min(1.0F, this.playerTicks / total));
    }
}
