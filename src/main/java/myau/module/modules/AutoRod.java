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
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

/**
 * Casts the fishing rod at an enemy and reels it back in.
 * <p>
 * Classic: casts when the server-side aim ray points at an enemy. Smart: simulates the hook's flight against the
 * enemy's predicted movement, aims silently, waits until the knockback can land (hurt time) and only rods enemies
 * that are coming at you or stand at a void edge, then switches straight back to the sword once the hook hits.
 */
public class AutoRod extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] HEALTH_SUBSTRINGS = new String[]{"hp", "health", "❤", "lives"};
    private static final int ROTATION_PRIORITY = 2;
    private static final int AIM_SILENT = 1;

    public final BooleanProperty smart = new BooleanProperty("smart", false);
    public final BooleanProperty facingEnemy = new BooleanProperty("facing-enemy", true, () -> !this.smart.getValue());
    public final BooleanProperty ignoreOnEnemyLowHealth = new BooleanProperty(
            "ignore-on-enemy-low-health", true, () -> this.smart.getValue() || this.facingEnemy.getValue()
    );
    public final BooleanProperty healthFromScoreboard = new BooleanProperty(
            "health-from-scoreboard", false, () -> (this.smart.getValue() || this.facingEnemy.getValue()) && this.ignoreOnEnemyLowHealth.getValue()
    );
    public final BooleanProperty absorption = new BooleanProperty(
            "absorption", false, () -> (this.smart.getValue() || this.facingEnemy.getValue()) && this.ignoreOnEnemyLowHealth.getValue()
    );
    public final FloatProperty activationDistance = new FloatProperty("activation-distance", 8.0F, 1.0F, 20.0F);
    public final IntProperty enemiesNearby = new IntProperty("enemies-nearby", 1, 1, 5);
    public final IntProperty playerHealthThreshold = new IntProperty("player-health-threshold", 5, 1, 20, () -> !this.smart.getValue());
    public final IntProperty enemyHealthThreshold = new IntProperty(
            "enemy-health-threshold", 5, 1, 20, () -> (this.smart.getValue() || this.facingEnemy.getValue()) && this.ignoreOnEnemyLowHealth.getValue()
    );
    public final IntProperty escapeHealthThreshold = new IntProperty("escape-health-threshold", 10, 1, 20, () -> !this.smart.getValue());
    public final IntProperty pushDelay = new IntProperty("push-delay", 100, 50, 1000);
    public final IntProperty pullbackDelay = new IntProperty("pullback-delay", 500, 50, 1000);
    public final BooleanProperty reelOnHit = new BooleanProperty("reel-on-hit", true);
    public final BooleanProperty onUsingItem = new BooleanProperty("on-using-item", false);
    public final ModeProperty aim = new ModeProperty("aim", AIM_SILENT, new String[]{"None", "Silent"}, this.smart::getValue);
    public final FloatProperty maxTurn = new FloatProperty(
            "max-turn", 70.0F, 10.0F, 180.0F, () -> this.smart.getValue() && this.aim.getValue() == AIM_SILENT
    );
    public final FloatProperty fov = new FloatProperty("fov", 90.0F, 30.0F, 180.0F, this.smart::getValue);
    public final FloatProperty minDistance = new FloatProperty("min-distance", 3.2F, 0.0F, 6.0F, this.smart::getValue);
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
    /** Time since the last cast (pullback fallback). */
    private final TimerUtil castTimer = new TimerUtil();
    /** Time since the last reel (push delay). */
    private final TimerUtil cooldown = new TimerUtil();
    /** Smart plan made in PRE, fired in POST once the rotation has been sent. */
    private SmartThrowHelper.Plan pending;

    /** A cast of ours is out and has not been reeled in yet. */
    private boolean active = false;
    private boolean hookSeen = false;
    private int ticksSinceCast = 0;
    private int expectedTicks = 0;
    private int leadTicks = 0;
    private EntityLivingBase castTarget;
    private int lastTargetHurtTime = 0;
    /** Slot to return to after reeling in, or -1 when the rod was already in hand. */
    private int switchBack = -1;
    private int rodSlot = -1;

    public AutoRod() {
        super("AutoRod", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.smart.getValue() ? "Smart" : "Classic"};
    }

    /** True while one of our casts is out: KillAura and AutoWeapon must not switch away from the rod. */
    public boolean isBusy() {
        return this.isEnabled() && this.active;
    }

    private boolean isTarget(Entity entity) {
        return TargetUtil.isSelected(entity, this.players.getValue(), this.mobs.getValue(), this.animals.getValue(),
                this.teams.getValue(), this.botCheck.getValue());
    }

    private float getHealth(EntityLivingBase entity) {
        if (this.healthFromScoreboard.getValue() && entity instanceof EntityPlayer) {
            Scoreboard scoreboard = ((EntityPlayer) entity).getWorldScoreboard();
            ScoreObjective objective = scoreboard == null ? null : scoreboard.getObjectiveInDisplaySlot(2);
            if (objective != null && objective.getDisplayName() != null && scoreboard.entityHasObjective(entity.getName(), objective)) {
                String displayName = objective.getDisplayName().toLowerCase();
                for (String substring : HEALTH_SUBSTRINGS) {
                    if (displayName.contains(substring)) {
                        Score score = scoreboard.getValueFromObjective(entity.getName(), objective);
                        if (score.getScorePoints() > 0) {
                            return (float) score.getScorePoints();
                        }
                        break;
                    }
                }
            }
        }
        float health = entity.getHealth();
        if (this.absorption.getValue()) {
            health += entity.getAbsorptionAmount();
        }
        return health >= 0.0F ? health : 20.0F;
    }

    private boolean isHoldingRod() {
        ItemStack held = mc.thePlayer.getHeldItem();
        return held != null && held.getItem() == Items.fishing_rod;
    }

    /** Hotbar slot (0-8) of the first fishing rod, or -1. */
    private int findRod() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == Items.fishing_rod) {
                return i;
            }
        }
        return -1;
    }

    /** The client's view of the player's hook: alive from its spawn packet until its destroy packet. */
    private boolean isHookOut() {
        EntityFishHook hook = mc.thePlayer.fishEntity;
        return hook != null && !hook.isDead;
    }

    private int countNearbyEnemies() {
        int count = 0;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (this.isTarget(entity) && RotationUtil.distanceToEntity(entity) < (double) this.activationDistance.getValue()) {
                count++;
            }
        }
        return count;
    }

    private void syncSlot() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
    }

    /**
     * Whether a cast may start now. Sword blocking does not prevent it: switching to the rod releases the block on
     * the server. Eating, drinking and drawing a bow do, unless on-using-item allows interrupting them.
     */
    private boolean canCast() {
        EntityPlayerSP player = mc.thePlayer;
        if (mc.currentScreen instanceof GuiContainer || !this.cooldown.hasTimeElapsed(this.pushDelay.getValue())) {
            return false;
        }
        Clutch clutch = (Clutch) myau.Myau.moduleManager.modules.get(Clutch.class);
        if (clutch != null && clutch.isBusy()) {
            return false;
        }
        // A hook still out (cast by hand, or ours not destroyed yet) would be reeled in, not cast.
        if (this.isHookOut() || this.findRod() == -1) {
            return false;
        }
        if (!this.onUsingItem.getValue() && player.isUsingItem()) {
            ItemStack inUse = player.getItemInUse();
            if (inUse != null && !(inUse.getItem() instanceof ItemSword) && inUse.getItem() != Items.fishing_rod) {
                return false;
            }
        }
        return this.countNearbyEnemies() <= this.enemiesNearby.getValue();
    }

    private SmartThrowHelper.Settings buildSettings() {
        SmartThrowHelper.Settings s = this.settings;
        s.kind = ThrowSim.Kind.ROD;
        s.minDistance = this.minDistance.getValue();
        s.maxDistance = this.activationDistance.getValue();
        s.fov = this.fov.getValue();
        s.yieldToKillAura = this.yieldToKillAura.getValue();
        s.approachOnly = this.approachOnly.getValue();
        s.edgeKnock = this.edgeKnock.getValue();
        s.pingCompensation = this.pingCompensation.getValue();
        s.skipLowHealth = this.ignoreOnEnemyLowHealth.getValue();
        s.lowHealthThreshold = (float) this.enemyHealthThreshold.getValue();
        s.health = this::getHealth;
        s.filter = this::isTarget;
        return s;
    }

    private void resetState() {
        this.active = false;
        this.hookSeen = false;
        this.ticksSinceCast = 0;
        this.castTarget = null;
        this.switchBack = -1;
        this.rodSlot = -1;
        this.pending = null;
    }

    @Override
    public void onDisabled() {
        // Can be called at any time (also with no world loaded): put the original item back if we took it.
        if (this.active && this.switchBack != -1 && mc.thePlayer != null && mc.playerController != null
                && mc.thePlayer.inventory.currentItem == this.rodSlot) {
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
        if (this.smart.getValue()) {
            this.tracker.update(player, this.activationDistance.getValue() + 8.0, this::isTarget);
        }
        if (this.active) {
            // Runs before KillAura (LOW priority), so the sword is back in hand for its attack this tick.
            this.updateCast();
            return;
        }
        if (!this.smart.getValue() || !this.canCast()) {
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

    /** POST: this tick's rotation has reached the server, so a cast now flies along exactly that rotation. */
    private void onPost(UpdateEvent event) {
        if (this.active || !this.canCast()) {
            this.pending = null;
            return;
        }
        float yaw = event.getYaw();
        float pitch = event.getPitch();
        if (this.smart.getValue()) {
            SmartThrowHelper.Plan plan = this.pending;
            this.pending = null;
            if (plan == null) {
                return;
            }
            int hitTick = SmartThrowHelper.checkHit(ThrowSim.Kind.ROD, mc.thePlayer, this.tracker, plan.target, yaw, pitch, plan.lead);
            if (hitTick < 0) {
                return;
            }
            if (this.hurtTimeSync.getValue() && !SmartThrowHelper.hurtTimeReady(plan.target, hitTick, plan.lead)) {
                return;
            }
            this.cast(plan.target, hitTick, plan.lead);
        } else {
            this.classicDecide(yaw, pitch);
        }
    }

    private void classicDecide(float yaw, float pitch) {
        EntityPlayerSP player = mc.thePlayer;
        float ownHealth = this.getHealth(player);
        EntityLivingBase target = null;
        boolean rod = false;
        if (this.facingEnemy.getValue() && ownHealth >= (float) this.playerHealthThreshold.getValue()) {
            target = this.facingTarget(yaw, pitch);
            if (target != null) {
                rod = !this.ignoreOnEnemyLowHealth.getValue()
                        || this.getHealth(target) >= (float) this.enemyHealthThreshold.getValue();
            }
        } else if (ownHealth <= (float) this.escapeHealthThreshold.getValue()) {
            // Low health: rod the enemy in front of you to keep them off. Never cast at nothing.
            target = this.facingTarget(yaw, pitch);
            rod = target != null;
        } else if (!this.facingEnemy.getValue()) {
            target = this.facingTarget(yaw, pitch);
            rod = true;
        }
        if (rod) {
            double distance = target == null ? 6.0 : RotationUtil.distanceToEntity(target);
            this.cast(target, (int) Math.ceil(distance / 1.2) + 1, SmartThrowHelper.leadTicks(true));
        }
    }

    /** The enemy along the rotation the server knows, even when the crosshair first hits a teammate or armor stand. */
    private EntityLivingBase facingTarget(float yaw, float pitch) {
        Entity entity = RotationUtil.raycastEntity(this.activationDistance.getValue(), yaw, pitch, this::isTarget);
        return entity instanceof EntityLivingBase ? (EntityLivingBase) entity : null;
    }

    private void cast(EntityLivingBase target, int expected, int lead) {
        EntityPlayerSP player = mc.thePlayer;
        int slot = this.findRod();
        if (slot == -1) {
            return;
        }
        if (this.isHoldingRod()) {
            slot = player.inventory.currentItem;
            this.switchBack = -1;
        } else {
            this.switchBack = player.inventory.currentItem;
            player.inventory.currentItem = slot;
            this.syncSlot();
        }
        // sendUseItem syncs the held slot itself before sending the use packet.
        mc.playerController.sendUseItem(player, mc.theWorld, player.inventory.getStackInSlot(slot));
        this.rodSlot = slot;
        this.active = true;
        this.hookSeen = false;
        this.ticksSinceCast = 0;
        this.expectedTicks = Math.max(1, expected);
        this.leadTicks = lead;
        this.castTarget = target;
        this.lastTargetHurtTime = target == null ? 0 : target.hurtTime;
        this.castTimer.reset();
    }

    /** Watches our hook every tick and reels it in as soon as there is nothing more to gain from it. */
    private void updateCast() {
        EntityPlayerSP player = mc.thePlayer;
        this.ticksSinceCast++;
        boolean hookAlive = this.isHookOut();
        if (hookAlive) {
            this.hookSeen = true;
        }
        if (player.inventory.currentItem != this.rodSlot) {
            // Switched away by hand or by another module: the server already removed the hook.
            this.switchBack = -1;
            this.finish(false);
            return;
        }
        if (!this.isHoldingRod()) {
            // The rod broke: just go back to the previous item.
            this.finish(false);
            return;
        }
        if (this.hookSeen && !hookAlive) {
            // The server removed the hook (it landed, broke, or the item changed).
            this.finish(false);
            return;
        }
        if (!this.hookSeen && this.ticksSinceCast > this.leadTicks + 6) {
            // The cast never produced a hook.
            this.finish(false);
            return;
        }
        boolean reel = false;
        if (this.castTarget != null) {
            int hurtTime = this.castTarget.hurtTime;
            if (this.reelOnHit.getValue() && hurtTime > this.lastTargetHurtTime) {
                // The target just took a hit: the knockback has landed, get the sword back.
                reel = true;
            }
            this.lastTargetHurtTime = hurtTime;
            if (this.smart.getValue() && hookAlive && !this.castTarget.isDead) {
                EntityFishHook hook = player.fishEntity;
                if (horizontalDistance(player, hook) > horizontalDistance(player, this.castTarget) + 2.0) {
                    // Flew past the target.
                    reel = true;
                }
            }
        }
        if (this.smart.getValue() && this.ticksSinceCast > this.expectedTicks + this.leadTicks + 3) {
            // Should have hit by now: it missed.
            reel = true;
        }
        if (this.castTimer.hasTimeElapsed(this.pullbackDelay.getValue())) {
            reel = true;
        }
        if (reel) {
            this.finish(hookAlive);
        }
    }

    /** Ends the cast: switch back to the previous item (the server then drops the hook) or really reel it in. */
    private void finish(boolean reelHook) {
        EntityPlayerSP player = mc.thePlayer;
        if (this.switchBack != -1) {
            if (player.inventory.currentItem == this.rodSlot && this.switchBack != this.rodSlot) {
                player.inventory.currentItem = this.switchBack;
                this.syncSlot();
            }
        } else if (reelHook && this.isHoldingRod()) {
            // The rod was in hand from the start: a second use is what actually reels the line in.
            mc.playerController.sendUseItem(player, mc.theWorld, player.getHeldItem());
        }
        this.active = false;
        this.hookSeen = false;
        this.castTarget = null;
        this.switchBack = -1;
        this.rodSlot = -1;
        this.cooldown.reset();
    }

    private static double horizontalDistance(Entity a, Entity b) {
        double dx = a.posX - b.posX;
        double dz = a.posZ - b.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
