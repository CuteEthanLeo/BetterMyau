package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RandomUtil;
import myau.util.RotationUtil;
import myau.util.SmartThrowHelper;
import myau.util.TargetTracker;
import myau.util.TargetUtil;
import myau.util.ThrowSim;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

/**
 * Throws snowballs / eggs from the hotbar at enemies.
 * <p>
 * Classic: throws when the server-side aim ray points at an enemy and holds the projectile for switch-back-delay.
 * Smart: predicts the enemy's movement, aims silently, times each throw to land the moment the enemy can take
 * knockback again, prefers enemies at void edges, and switches back to the sword in the same tick.
 */
public class AutoProjectile extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int ROTATION_PRIORITY = 2;
    private static final int AIM_SILENT = 1;

    public final BooleanProperty smart = new BooleanProperty("smart", false);
    public final BooleanProperty facingEnemy = new BooleanProperty("facing-enemy", true, () -> !this.smart.getValue());
    public final FloatProperty range = new FloatProperty("range", 8.0F, 1.0F, 20.0F);
    public final IntProperty minThrowDelay = new IntProperty("min-throw-delay", 1000, 50, 2000, () -> !this.smart.getValue());
    public final IntProperty maxThrowDelay = new IntProperty("max-throw-delay", 1500, 50, 2000, () -> !this.smart.getValue());
    public final IntProperty switchBackDelay = new IntProperty("switch-back-delay", 500, 50, 2000, () -> !this.smart.getValue());
    public final IntProperty smartDelay = new IntProperty("smart-delay", 300, 50, 2000, this.smart::getValue);
    public final ModeProperty aim = new ModeProperty("aim", AIM_SILENT, new String[]{"None", "Silent"}, this.smart::getValue);
    public final FloatProperty maxTurn = new FloatProperty(
            "max-turn", 70.0F, 10.0F, 180.0F, () -> this.smart.getValue() && this.aim.getValue() == AIM_SILENT
    );
    public final FloatProperty fov = new FloatProperty("fov", 90.0F, 30.0F, 180.0F, this.smart::getValue);
    public final FloatProperty minDistance = new FloatProperty("min-distance", 3.0F, 0.0F, 6.0F, this.smart::getValue);
    public final BooleanProperty yieldToKillAura = new BooleanProperty("yield-to-killaura", true, this.smart::getValue);
    public final BooleanProperty approachOnly = new BooleanProperty("approach-only", true, this.smart::getValue);
    public final BooleanProperty hurtTimeSync = new BooleanProperty("hurt-time-sync", true, this.smart::getValue);
    public final BooleanProperty edgeKnock = new BooleanProperty("edge-knock", true, this.smart::getValue);
    public final BooleanProperty pingCompensation = new BooleanProperty("ping-compensation", true, this.smart::getValue);
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty mobs = new BooleanProperty("mobs", false);
    public final BooleanProperty animals = new BooleanProperty("animals", false);
    public final BooleanProperty teams = new BooleanProperty("teams", true);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);

    private final TargetTracker tracker = new TargetTracker();
    private final SmartThrowHelper.Settings settings = new SmartThrowHelper.Settings();
    private final TimerUtil throwTimer = new TimerUtil();
    private final TimerUtil holdTimer = new TimerUtil();
    /** Random classic delay, rolled once per throw. */
    private long nextDelay = 0L;
    private SmartThrowHelper.Plan pending;
    /** Classic: we switched to the projectile and hold it until switch-back-delay. */
    private boolean holding = false;
    private int switchBack = -1;
    private int thrownSlot = -1;

    public AutoProjectile() {
        super("AutoProjectile", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.smart.getValue() ? "Smart" : "Classic"};
    }

    private boolean isTarget(Entity entity) {
        return TargetUtil.isSelected(entity, this.players.getValue(), this.mobs.getValue(), this.animals.getValue(),
                this.teams.getValue(), this.botCheck.getValue());
    }

    private static boolean isProjectile(ItemStack stack) {
        return stack != null && (stack.getItem() == Items.snowball || stack.getItem() == Items.egg);
    }

    /** Hotbar slot (0-8) of the first snowball / egg stack, or -1. */
    private int findProjectile() {
        for (int i = 0; i < 9; i++) {
            if (isProjectile(mc.thePlayer.inventory.getStackInSlot(i))) {
                return i;
            }
        }
        return -1;
    }

    private void syncSlot() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
    }

    /** Never switch away from food, potions or a drawn bow; sword blocking is fine to interrupt. */
    private boolean canThrow() {
        EntityPlayerSP player = mc.thePlayer;
        if (mc.currentScreen instanceof GuiContainer || this.findProjectile() == -1) {
            return false;
        }
        Clutch clutch = (Clutch) myau.Myau.moduleManager.modules.get(Clutch.class);
        if (clutch != null && clutch.isBusy()) {
            return false;
        }
        if (player.isUsingItem()) {
            ItemStack inUse = player.getItemInUse();
            return inUse == null || inUse.getItem() instanceof ItemSword;
        }
        return true;
    }

    private SmartThrowHelper.Settings buildSettings() {
        SmartThrowHelper.Settings s = this.settings;
        s.kind = ThrowSim.Kind.THROWABLE;
        s.minDistance = this.minDistance.getValue();
        s.maxDistance = this.range.getValue();
        s.fov = this.fov.getValue();
        s.yieldToKillAura = this.yieldToKillAura.getValue();
        s.approachOnly = this.approachOnly.getValue();
        s.edgeKnock = this.edgeKnock.getValue();
        s.pingCompensation = this.pingCompensation.getValue();
        s.skipLowHealth = false;
        s.lowHealthThreshold = 0.0F;
        s.health = EntityLivingBase::getHealth;
        s.filter = this::isTarget;
        return s;
    }

    private void resetState() {
        this.throwTimer.reset();
        this.nextDelay = 0L;
        this.pending = null;
        this.holding = false;
        this.switchBack = -1;
        this.thrownSlot = -1;
    }

    @Override
    public void onDisabled() {
        if (this.holding && this.switchBack != -1 && mc.thePlayer != null && mc.playerController != null
                && mc.thePlayer.inventory.currentItem == this.thrownSlot) {
            mc.thePlayer.inventory.currentItem = this.switchBack;
            this.syncSlot();
        }
        this.resetState();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetState();
        this.tracker.clear();
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (event.getType() == EventType.PRE) {
            this.onPre(event);
        } else {
            this.onPost(event);
        }
    }

    private void onPre(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        this.pending = null;
        if (this.holding) {
            // Classic hold: give the previous item back after switch-back-delay, before KillAura attacks.
            if (player.inventory.currentItem != this.thrownSlot) {
                this.holding = false;
                this.switchBack = -1;
            } else if (this.holdTimer.hasTimeElapsed(this.switchBackDelay.getValue())) {
                if (this.switchBack != -1 && this.switchBack != this.thrownSlot) {
                    player.inventory.currentItem = this.switchBack;
                    this.syncSlot();
                }
                this.holding = false;
                this.switchBack = -1;
            }
        }
        if (!this.smart.getValue()) {
            return;
        }
        this.tracker.update(player, this.range.getValue() + 8.0, this::isTarget);
        if (!this.throwTimer.hasTimeElapsed(this.smartDelay.getValue()) || !this.canThrow()) {
            return;
        }
        SmartThrowHelper.Plan plan = SmartThrowHelper.plan(player, this.tracker, this.buildSettings());
        if (plan == null) {
            return;
        }
        this.pending = plan;
        if (this.aim.getValue() == AIM_SILENT) {
            float[] rotation = SmartThrowHelper.stepRotation(event.getYaw(), event.getPitch(), plan.yaw, plan.pitch, this.maxTurn.getValue());
            event.setRotation(rotation[0], rotation[1], ROTATION_PRIORITY);
        }
    }

    /** POST: this tick's rotation has reached the server, so a throw now flies along exactly that rotation. */
    private void onPost(UpdateEvent event) {
        SmartThrowHelper.Plan plan = this.pending;
        this.pending = null;
        if (this.holding || !this.canThrow()) {
            return;
        }
        float yaw = event.getYaw();
        float pitch = event.getPitch();
        if (this.smart.getValue()) {
            if (plan == null || !this.throwTimer.hasTimeElapsed(this.smartDelay.getValue())) {
                return;
            }
            int hitTick = SmartThrowHelper.checkHit(ThrowSim.Kind.THROWABLE, mc.thePlayer, this.tracker, plan.target, yaw, pitch, plan.lead);
            if (hitTick < 0) {
                return;
            }
            if (this.hurtTimeSync.getValue() && !SmartThrowHelper.hurtTimeReady(plan.target, hitTick, plan.lead)) {
                return;
            }
            this.throwInstant();
        } else {
            this.classicThrow(yaw, pitch);
        }
    }

    private void classicThrow(float yaw, float pitch) {
        if (this.facingEnemy.getValue()) {
            // Ray along the rotation the server knows, so it also works under silent rotations, and look past
            // teammates or armor stands in front of the crosshair.
            Entity facing = RotationUtil.raycastEntity(this.range.getValue(), yaw, pitch, this::isTarget);
            if (!this.isTarget(facing)) {
                return;
            }
        }
        if (this.nextDelay <= 0L) {
            long min = Math.min(this.minThrowDelay.getValue(), this.maxThrowDelay.getValue());
            long max = Math.max(this.minThrowDelay.getValue(), this.maxThrowDelay.getValue());
            this.nextDelay = RandomUtil.nextLong(min, max);
        }
        if (!this.throwTimer.hasTimeElapsed(this.nextDelay)) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        int slot = this.findProjectile();
        if (isProjectile(player.getHeldItem())) {
            slot = player.inventory.currentItem;
            this.switchBack = -1;
        } else {
            this.switchBack = player.inventory.currentItem;
            player.inventory.currentItem = slot;
            this.syncSlot();
        }
        // sendUseItem syncs the held slot itself before sending the use packet.
        mc.playerController.sendUseItem(player, mc.theWorld, player.inventory.getStackInSlot(slot));
        this.thrownSlot = slot;
        this.holding = this.switchBack != -1;
        this.holdTimer.reset();
        this.throwTimer.reset();
        this.nextDelay = 0L;
    }

    /** Smart: switch, throw and switch back within the same tick, so the sword is never out of hand. */
    private void throwInstant() {
        EntityPlayerSP player = mc.thePlayer;
        int slot = isProjectile(player.getHeldItem()) ? player.inventory.currentItem : this.findProjectile();
        if (slot == -1) {
            return;
        }
        ItemStack stack = player.inventory.getStackInSlot(slot);
        int previous = player.inventory.currentItem;
        player.inventory.currentItem = slot;
        // Syncs the held slot (C09), sends the use packet and applies the throw client-side.
        mc.playerController.sendUseItem(player, mc.theWorld, stack);
        if (previous != slot) {
            player.inventory.currentItem = previous;
            this.syncSlot();
        }
        this.throwTimer.reset();
    }
}
