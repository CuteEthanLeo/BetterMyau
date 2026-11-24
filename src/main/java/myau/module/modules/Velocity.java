package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.DelayModules;
import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.mixin.IAccessorEntity;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.module.modules.velocity.XwVelocity;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.management.RotationState;
import myau.util.ChatUtil;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.RayCastUtil;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.potion.Potion;

public class Velocity extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private int chanceCounter = 0;
    private int delayChanceCounter = 0;
    private boolean pendingExplosion = false;
    private boolean allowNext = true;
    private boolean jumpFlag = false;
    private boolean reverseFlag = false;
    private boolean delayActive = false;

    // Legit Fast 特化控制变量
    private boolean packetHitReceived = false;
    private int legitTickCounter = 0;

    private static final int PREDICTION = 5;
    private static final int HYPIXEL = 6;
    private static final int HYPIXEL_AIR = 7;

    public final ModeProperty mode = new ModeProperty("mode", 0, XwVelocity.appendModes(new String[]{"VANILLA", "JUMP", "DELAY", "REVERSE", "LEGIT_FAST", "PREDICTION", "HYPIXEL", "HYPIXEL_AIR"}));

    // ── PREDICTION: ported from LeaderClient (GPLv3), tuned against KillAura HYPIXEL_LEADER / HYPIXEL_CUSTOM ──
    public final BooleanProperty reduce = new BooleanProperty("reduce", true, () -> this.mode.getValue() == PREDICTION);
    public final ModeProperty reduceMode = new ModeProperty("reduce-mode", 0, new String[]{"ATTACK", "RELEASE_WHEN_CAN_ATTACK", "RELEASE_BEFORE_CAN_ATTACK"},
            () -> this.mode.getValue() == PREDICTION && this.reduce.getValue());
    public final BooleanProperty extraAttack = new BooleanProperty("extra-attack", false,
            () -> this.mode.getValue() == PREDICTION && this.reduce.getValue() && this.reduceMode.getValue() != 0);
    public final BooleanProperty reduceWhenCanAttack = new BooleanProperty("reduce-when-can-attack", true,
            () -> this.mode.getValue() == PREDICTION && this.reduce.getValue() && this.reduceMode.getValue() == 0);
    public final BooleanProperty onlySprinting = new BooleanProperty("only-sprinting", true,
            () -> this.mode.getValue() == PREDICTION && this.reduceMode.getValue() == 0);
    public final IntProperty attackTimes = new IntProperty("attack-times", 1, 1, 5,
            () -> this.mode.getValue() == PREDICTION && this.reduce.getValue() && this.reduceMode.getValue() == 0);
    public final BooleanProperty jumpReset = new BooleanProperty("jump", true, () -> this.mode.getValue() == PREDICTION);
    public final BooleanProperty predDelay = new BooleanProperty("pred-delay", false, () -> this.mode.getValue() == PREDICTION);
    public final IntProperty predDelayTicks = new IntProperty("pred-delay-ticks", 1, 1, 5,
            () -> this.mode.getValue() == PREDICTION && this.predDelay.getValue() && !this.airBuffer.getValue());
    public final BooleanProperty airBuffer = new BooleanProperty("delay-till-on-ground", true, () -> this.mode.getValue() == PREDICTION && this.predDelay.getValue());
    public final BooleanProperty groundDelay = new BooleanProperty("ground-delay", false,
            () -> this.mode.getValue() == PREDICTION && this.predDelay.getValue() && !this.airBuffer.getValue());
    public final BooleanProperty rotate = new BooleanProperty("rotate", false, () -> this.mode.getValue() == PREDICTION);
    public final IntProperty rotateTick = new IntProperty("rotate-ticks", 3, 1, 12, () -> this.mode.getValue() == PREDICTION && this.rotate.getValue());
    public final BooleanProperty autoMove = new BooleanProperty("auto-move", false, () -> this.mode.getValue() == PREDICTION && this.rotate.getValue());

    /** Set when Velocity PREDICTION sent the hit KillAura would have sent this tick. */
    public static boolean extraAttacked = false;
    public static boolean velocityAttacked = false;
    public static boolean hasReceivedVelocity = false;
    private boolean predDelayFlag = false;
    private int ticksSinceVelocity = -1;
    private int reduceTick = -1;
    private boolean jumpWasPressed = false;
    private int rotateTickCounter = 0;
    private float[] targetRotation = null;
    private double knockbackX = 0.0;
    private double knockbackZ = 0.0;

    // ── HYPIXEL (LiquidBounce Nextgen VelocityHypixel) / HYPIXEL_AIR (LiquidBounce Legacy) ──
    // HYPIXEL_AIR: every own S12 is cancelled. The next PRE jumps only if the player is already on the ground, and
    // the flag is cleared either way, so a hit taken in the air gives no jump on landing. This is intentional and
    // matches LiquidBounce Legacy / xw; it is not a "jump when you land" mode.
    private boolean absorbedVelocity = false;
    private boolean airVelocity = false;
    public final IntProperty delayTicks = new IntProperty("delay-ticks", 3, 1, 20, () -> this.mode.getValue() == 2);
    public final PercentProperty delayChance = new PercentProperty("delay-chance", 100, () -> this.mode.getValue() == 2);
    public final PercentProperty chance = new PercentProperty("chance", 100, () -> !this.isXwMode());
    public final PercentProperty horizontal = new PercentProperty("horizontal", 0, () -> !this.isXwMode());
    public final PercentProperty vertical = new PercentProperty("vertical", 100, () -> !this.isXwMode());
    public final PercentProperty explosionHorizontal = new PercentProperty("explosions-horizontal", 100, () -> !this.isXwMode());
    public final PercentProperty explosionVertical = new PercentProperty("explosions-vertical", 100, () -> !this.isXwMode());
    public final BooleanProperty fakeCheck = new BooleanProperty("fake-check", true, () -> !this.isXwMode());
    public final BooleanProperty debugLog = new BooleanProperty("debug-log", false, () -> !this.isXwMode());

    // xw / Air Client Velocity (GPL-3.0): all source modes, with independent settings.
    public final FloatProperty xwHorizontal = new FloatProperty("xw-horizontal", 0f, -1f, 1f, () -> this.isXwMode() && (this.isXwMode("Simple", "AAC", "Legit")));
    public final FloatProperty xwVertical = new FloatProperty("xw-vertical", 0f, -1f, 1f, () -> this.isXwMode() && (this.isXwMode("Simple", "Legit")));
    public final FloatProperty xwReverseStrength = new FloatProperty("xw-reverse-strength", 1f, 0.1f, 1f, () -> this.isXwMode() && (this.isXwMode("Reverse")));
    public final FloatProperty xwReverse2Strength = new FloatProperty("xw-smooth-reverse-strength", 0.05f, 0.02f, 0.1f, () -> this.isXwMode() && (this.isXwMode("SmoothReverse")));
    public final BooleanProperty xwOnLook = new BooleanProperty("xw-on-look", false, () -> this.isXwMode() && (this.isXwMode("Reverse", "SmoothReverse")));
    public final FloatProperty xwRange = new FloatProperty("xw-range", 3.0f, 1f, 5.0f, () -> this.isXwMode() && (this.xwOnLook.getValue() && this.isXwMode("Reverse", "SmoothReverse")));
    public final FloatProperty xwMaxAngleDifference = new FloatProperty("xw-max-angle-difference", 45.0f, 5.0f, 90f, () -> this.isXwMode() && (this.xwOnLook.getValue() && this.isXwMode("Reverse", "SmoothReverse")));
    public final FloatProperty xwAacPushXZReducer = new FloatProperty("xw-aac-push-xz-reducer", 2f, 1f, 3f, () -> this.isXwMode() && (this.isXwMode("AACPush")));
    public final BooleanProperty xwAacPushYReducer = new BooleanProperty("xw-aac-push-y-reducer", true, () -> this.isXwMode() && (this.isXwMode("AACPush")));
    public final FloatProperty xwAacv4MotionReducer = new FloatProperty("xw-aa-cv4-motion-reducer", 0.62f, 0f, 1f, () -> this.isXwMode() && (this.isXwMode("AACv4")));
    public final BooleanProperty xwLegitDisableInAir = new BooleanProperty("xw-disable-in-air", true, () -> this.isXwMode() && (this.isXwMode("Legit")));
    public final IntProperty xwChance = new IntProperty("xw-chance", 100, 0, 100, () -> this.isXwMode() && (this.isXwMode("Legit")));
    public final IntProperty xwJumpResetChance = new IntProperty("xw-jr-chance", 100, 0, 100, () -> this.isXwMode() && (this.isXwMode("JumpReset")));
    public final BooleanProperty xwJumpResetCheckDirection = new BooleanProperty("xw-jr-check-direction", true, () -> this.isXwMode() && (this.isXwMode("JumpReset")));
    public final BooleanProperty xwJumpResetUseCooldown = new BooleanProperty("xw-jr-use-cooldown", false, () -> this.isXwMode() && (this.isXwMode("JumpReset")));
    public final ModeProperty xwJumpResetCooldownMode = new ModeProperty("xw-jr-cool-down-mode", 0, new String[]{"Ticks", "ReceivedHits"}, () -> this.isXwMode() && (this.xwJumpResetUseCooldown.getValue() && this.isXwMode("JumpReset")));
    public final IntProperty xwJumpResetTicksUntilJump = new IntProperty("xw-jr-ticks-until-jump", 4, 0, 20, () -> this.isXwMode() && (this.xwJumpResetUseCooldown.getValue() && "Ticks".equals(this.xwJumpResetCooldownMode.getModeString()) && this.isXwMode("JumpReset")));
    public final IntProperty xwJumpResetHitsUntilJump = new IntProperty("xw-jr-hits-until-jump", 2, 0, 5, () -> this.isXwMode() && (this.xwJumpResetUseCooldown.getValue() && "ReceivedHits".equals(this.xwJumpResetCooldownMode.getModeString()) && this.isXwMode("JumpReset")));
    public final IntProperty xwHurtTimeRangeMin = new IntProperty("xw-hurt-time-min", 1, 1, 10, () -> this.isXwMode() && (this.isXwMode("GhostBlock")));
    public final IntProperty xwHurtTimeRangeMax = new IntProperty("xw-hurt-time-max", 9, 1, 10, () -> this.isXwMode() && (this.isXwMode("GhostBlock")));
    public final IntProperty xwSpoofDelay = new IntProperty("xw-spoof-delay", 500, 0, 5000, () -> this.isXwMode() && (this.isXwMode("Delay")));
    public final FloatProperty xwReduceFactor = new FloatProperty("xw-factor", 0.6f, 0.6f, 1f, () -> this.isXwMode() && (this.isXwMode("IntaveReduce")));
    public final IntProperty xwHurtTime = new IntProperty("xw-hurt-time", 9, 1, 10, () -> this.isXwMode() && (this.isXwMode("IntaveReduce")));
    public final FloatProperty xwPolar2Factor = new FloatProperty("xw-polar2-factor", 0.6f, 0.6f, 1f, () -> this.isXwMode() && (this.isXwMode("Polar2")));
    public final IntProperty xwPolar2HurtTime = new IntProperty("xw-polar2-hurt-time", 9, 1, 10, () -> this.isXwMode() && (this.isXwMode("Polar2")));
    public final FloatProperty xwMatrixReduceFactor = new FloatProperty("xw-matrix-reduce-factor", 0.6f, 0.0f, 1f, () -> this.isXwMode() && (this.isXwMode("MatrixReduce")));
    public final BooleanProperty xwMatrixReduceDebug = new BooleanProperty("xw-matrix-reduce-debug", false, () -> this.isXwMode() && (this.isXwMode("MatrixReduce")));
    public final IntProperty xwIntave14TriggerTimes = new IntProperty("xw-intave14-trigger-times", 2, 1, 3, () -> this.isXwMode() && (this.isXwMode("Intave14")));
    public final IntProperty xwIntave14FirstReduce = new IntProperty("xw-intave14-first-reduce", 9, 1, 10, () -> this.isXwMode() && (this.isXwMode("Intave14") && this.xwIntave14TriggerTimes.getValue() >= 1));
    public final IntProperty xwIntave14SecondReduce = new IntProperty("xw-intave14-second-reduce", 8, 1, 10, () -> this.isXwMode() && (this.isXwMode("Intave14") && this.xwIntave14TriggerTimes.getValue() >= 2));
    public final IntProperty xwIntave14ThirdReduce = new IntProperty("xw-intave14-third-reduce", 6, 1, 10, () -> this.isXwMode() && (this.isXwMode("Intave14") && this.xwIntave14TriggerTimes.getValue() >= 3));
    public final BooleanProperty xwIntave14OnlyWhenBackward = new BooleanProperty("xw-intave14-only-when-backward", true, () -> this.isXwMode() && (this.isXwMode("Intave14")));
    public final BooleanProperty xwIntave14FinalReverse = new BooleanProperty("xw-intave14-final-reverse", false, () -> this.isXwMode() && (this.isXwMode("Intave14")));
    public final FloatProperty xwIntave14FinalReverseFactor = new FloatProperty("xw-intave14-final-reverse-factor", 1.0f, 0.0f, 5.0f, () -> this.isXwMode() && (this.isXwMode("Intave14") && this.xwIntave14FinalReverse.getValue()));
    public final BooleanProperty xwIntave14Debug = new BooleanProperty("xw-intave14-debug", false, () -> this.isXwMode() && (this.isXwMode("Intave14")));
    public final FloatProperty xwIntaveStrongFactor = new FloatProperty("xw-intave-strong-factor", 0.6f, 0.0f, 1.0f, () -> this.isXwMode() && (this.isXwMode("IntaveStrong")));
    public final FloatProperty xwAttackReduceFactor = new FloatProperty("xw-attack-reduce-factor", 0.6f, -1.0f, 1.0f, () -> this.isXwMode() && (this.isXwMode("AttackReduce")));
    public final FloatProperty xwAttackReduceSprintFactor = new FloatProperty("xw-attack-reduce-sprint-factor", 0.6f, -1.0f, 1.0f, () -> this.isXwMode() && (this.isXwMode("AttackReduce")));
    public final IntProperty xwAttackReduceHurtTimeMin = new IntProperty("xw-attack-reduce-hurt-time-min", 9, 1, 10, () -> this.isXwMode() && (this.isXwMode("AttackReduce")));
    public final IntProperty xwAttackReduceHurtTimeMax = new IntProperty("xw-attack-reduce-hurt-time-max", 9, 1, 10, () -> this.isXwMode() && (this.isXwMode("AttackReduce")));
    public final BooleanProperty xwPauseOnExplosion = new BooleanProperty("xw-pause-on-explosion", true, () -> this.isXwMode());
    public final IntProperty xwTicksToPause = new IntProperty("xw-ticks-to-pause", 20, 1, 50, () -> this.isXwMode() && (this.xwPauseOnExplosion.getValue()));
    public final BooleanProperty xwCheckDirection = new BooleanProperty("xw-check-direction", false, () -> this.isXwMode());
    public final BooleanProperty xwApplyOnFront = new BooleanProperty("xw-apply-on-front", true, () -> this.isXwMode() && (this.xwCheckDirection.getValue()));
    public final BooleanProperty xwApplyOnSide = new BooleanProperty("xw-apply-on-side", true, () -> this.isXwMode() && (this.xwCheckDirection.getValue()));
    public final BooleanProperty xwApplyOnBack = new BooleanProperty("xw-apply-on-back", true, () -> this.isXwMode() && (this.xwCheckDirection.getValue()));
    public final BooleanProperty xwLimitMaxMotionValue = new BooleanProperty("xw-limit-max-motion", false, () -> this.isXwMode() && (this.isXwMode("Simple")));
    public final FloatProperty xwMaxXZMotion = new FloatProperty("xw-max-xz-motion", 0.4f, 0f, 1.9f, () -> this.isXwMode() && (this.xwLimitMaxMotionValue.getValue()));
    public final FloatProperty xwMaxYMotion = new FloatProperty("xw-max-y-motion", 0.36f, 0f, 0.46f, () -> this.isXwMode() && (this.xwLimitMaxMotionValue.getValue()));
    public final IntProperty xwClicksMin = new IntProperty("xw-clicks-min", 3, 1, 20, () -> this.isXwMode() && (this.isXwMode("Click")));
    public final IntProperty xwClicksMax = new IntProperty("xw-clicks-max", 5, 1, 20, () -> this.isXwMode() && (this.isXwMode("Click")));
    public final IntProperty xwHurtTimeToClick = new IntProperty("xw-hurt-time-to-click", 10, 0, 10, () -> this.isXwMode() && (this.isXwMode("Click")));
    public final BooleanProperty xwWhenFacingEnemyOnly = new BooleanProperty("xw-when-facing-enemy-only", true, () -> this.isXwMode() && (this.isXwMode("Click")));
    public final BooleanProperty xwIgnoreBlocking = new BooleanProperty("xw-ignore-blocking", false, () -> this.isXwMode() && (this.isXwMode("Click")));
    public final FloatProperty xwClickRange = new FloatProperty("xw-click-range", 3f, 1f, 6f, () -> this.isXwMode() && (this.isXwMode("Click")));
    public final ModeProperty xwSwingMode = new ModeProperty("xw-swing-mode", 1, new String[]{"Off", "Normal", "Packet"}, () -> this.isXwMode() && (this.isXwMode("Click")));
    public final IntProperty xwPredictionClicksMin = new IntProperty("xw-prediction-clicks-min", 1, 1, 20, () -> this.isXwMode() && (this.isXwMode("Prediction")));
    public final IntProperty xwPredictionClicksMax = new IntProperty("xw-prediction-clicks-max", 2, 1, 20, () -> this.isXwMode() && (this.isXwMode("Prediction")));
    public final IntProperty xwBufferDelay = new IntProperty("xw-buffer-delay", 3, 1, 10, () -> this.isXwMode() && (this.isXwMode("Buffer")));
    public final IntProperty xwLegitSmartJumpLimit = new IntProperty("xw-legit-smart-jump-limit", 2, 1, 5, () -> this.isXwMode() && (this.isXwMode("LegitSmart")));
    public final BooleanProperty xwMatrixDebug = new BooleanProperty("xw-matrix-debug", false, () -> this.isXwMode() && (this.isXwMode("Matrix")));
    public final FloatProperty xwIntave13XZOnHit = new FloatProperty("xw-intave13-x-zon-hit", 0.6f, 0.0f, 1f, () -> this.isXwMode() && (this.isXwMode("Intave13")));
    public final FloatProperty xwIntave13XZOnSprintHit = new FloatProperty("xw-intave13-x-zon-sprint-hit", 0.6f, 0.0f, 1f, () -> this.isXwMode() && (this.isXwMode("Intave13")));
    public final BooleanProperty xwIntave13ReduceUnnecessarySlowdown = new BooleanProperty("xw-intave13-reduce-slowdown", false, () -> this.isXwMode() && (this.isXwMode("Intave13")));
    public final IntProperty xwIntave13Chance = new IntProperty("xw-intave13-chance", 100, 0, 100, () -> this.isXwMode() && (this.isXwMode("Intave13")));
    public final BooleanProperty xwIntave13Jump = new BooleanProperty("xw-intave13-jump", false, () -> this.isXwMode() && (this.isXwMode("Intave13")));
    public final BooleanProperty xwIntave13JumpInInv = new BooleanProperty("xw-intave13-jump-in-inv", false, () -> this.isXwMode() && (this.isXwMode("Intave13") && this.xwIntave13Jump.getValue()));
    public final IntProperty xwIntave13JumpChance = new IntProperty("xw-intave13-jump-chance", 80, 0, 100, () -> this.isXwMode() && (this.isXwMode("Intave13") && this.xwIntave13Jump.getValue()));
    public final BooleanProperty xwIntave13NotWhileSpeed = new BooleanProperty("xw-intave13-not-while-speed", false, () -> this.isXwMode() && (this.isXwMode("Intave13")));
    public final BooleanProperty xwIntave13NotWhileJumpBoost = new BooleanProperty("xw-intave13-not-while-jump-boost", false, () -> this.isXwMode() && (this.isXwMode("Intave13")));
    public final BooleanProperty xwIntave13Debug = new BooleanProperty("xw-intave13-debug", false, () -> this.isXwMode() && (this.isXwMode("Intave13")));
    public final IntProperty xwPredictionChance = new IntProperty("xw-pred-chance", 100, 0, 100, () -> this.isXwMode() && (this.isXwMode("HypixelPrediction")));
    public final FloatProperty xwPredictionHorizontal = new FloatProperty("xw-pred-horizontal", 0.0f, 0.0f, 1.0f, () -> this.isXwMode() && (this.isXwMode("HypixelPrediction")));
    public final FloatProperty xwPredictionVertical = new FloatProperty("xw-pred-vertical", 1.0f, 0.0f, 1.0f, () -> this.isXwMode() && (this.isXwMode("HypixelPrediction")));
    public final BooleanProperty xwPredictionFakeCheck = new BooleanProperty("xw-pred-fake-check", false, () -> this.isXwMode() && (this.isXwMode("HypixelPrediction")));
    public final BooleanProperty xwPredictionDebug = new BooleanProperty("xw-pred-debug", false, () -> this.isXwMode() && (this.isXwMode("HypixelPrediction")));
    public final FloatProperty xwGrimCombatRange = new FloatProperty("xw-grim-combat-range", 3.5f, 0.0f, 6.0f, () -> this.isXwMode() && (this.isXwMode("GrimCombat")));
    public final IntProperty xwGrimCombatAttacks = new IntProperty("xw-grim-combat-attacks", 12, 1, 16, () -> this.isXwMode() && (this.isXwMode("GrimCombat")));
    public final IntProperty xwGrimReduceMinHurtTime = new IntProperty("xw-grim-reduce-min-ht", 5, 0, 10, () -> this.isXwMode() && (this.isXwMode("GrimReduce")));
    public final IntProperty xwGrimReduceMaxHurtTime = new IntProperty("xw-grim-reduce-max-ht", 10, 0, 20, () -> this.isXwMode() && (this.isXwMode("GrimReduce")));
    public final FloatProperty xwGrimReduceFactor = new FloatProperty("xw-grim-reduce-factor", 0.6f, 0.0f, 1.0f, () -> this.isXwMode() && (this.isXwMode("GrimReduce")));
    public final BooleanProperty xwGrimReduceOnlyGround = new BooleanProperty("xw-grim-reduce-only-ground", false, () -> this.isXwMode() && (this.isXwMode("GrimReduce")));
    public final IntProperty xwReduceAttackCount = new IntProperty("xw-reduce-attack-count", 3, 0, 20, () -> this.isXwMode() && (this.isXwMode("Reduce")));
    public final BooleanProperty xwReduceRequireKillAura = new BooleanProperty("xw-reduce-require-kill-aura", false, () -> this.isXwMode() && (this.isXwMode("Reduce")));
    public final FloatProperty xwReduceAttackHorizontal = new FloatProperty("xw-reduce-horizontal", 0.6f, 0.0f, 1.0f, () -> this.isXwMode() && (this.isXwMode("Reduce")));
    public final FloatProperty xwReduceAttackVertical = new FloatProperty("xw-reduce-vertical", 1.0f, 0.0f, 1.0f, () -> this.isXwMode() && (this.isXwMode("Reduce")));
    public final IntProperty xwBounceTick = new IntProperty("xw-bounce-tick", 0, 0, 6, () -> this.isXwMode() && (this.isXwMode("Bounce")));
    public final BooleanProperty xwBounceVertical = new BooleanProperty("xw-bounce-vertical", false, () -> this.isXwMode() && (this.isXwMode("Bounce")));
    public final BooleanProperty xwBounceHorizontal = new BooleanProperty("xw-bounce-horizontal", false, () -> this.isXwMode() && (this.isXwMode("Bounce")));
    public final BooleanProperty xwGrim2FullRotationFix = new BooleanProperty("xw-grim2-full-rotation-fix", true, () -> this.isXwMode() && (this.isXwMode("Grim2")));
    public final BooleanProperty xwGrim2FakeS08 = new BooleanProperty("xw-grim2-fake-s08", true, () -> this.isXwMode() && (this.isXwMode("Grim2")));
    public final BooleanProperty xwGrim2CancelVelocity = new BooleanProperty("xw-grim2-cancel-velocity", true, () -> this.isXwMode() && (this.isXwMode("Grim2")));
    public final FloatProperty xwGrim2RotationNoise = new FloatProperty("xw-grim2-rotation-noise", 0.001f, 0f, 0.1f, () -> this.isXwMode() && (this.isXwMode("Grim2")));
    public final BooleanProperty xwGrim2DebugLog = new BooleanProperty("xw-grim2-debug-log", false, () -> this.isXwMode() && (this.isXwMode("Grim2")));
    public final ModeProperty xwGrimTestType = new ModeProperty("xw-grim-test-type", 0, new String[]{"Reduce", "Jump Reset", "Grim Full"}, () -> this.isXwMode() && (this.isXwMode("GrimTest")));
    public final IntProperty xwGrimTestMinAttacks = new IntProperty("xw-grim-test-min-attacks", 2, 1, 5, () -> this.isXwMode() && (this.isXwMode("GrimTest") && "Reduce".equals(this.xwGrimTestType.getModeString())));
    public final IntProperty xwGrimTestMaxAttacks = new IntProperty("xw-grim-test-max-attacks", 3, 1, 10, () -> this.isXwMode() && (this.isXwMode("GrimTest") && "Reduce".equals(this.xwGrimTestType.getModeString())));
    public final BooleanProperty xwGrimTestDelayRelease = new BooleanProperty("xw-grim-test-delay-release", false, () -> this.isXwMode() && (this.isXwMode("GrimTest") && "Reduce".equals(this.xwGrimTestType.getModeString())));
    public final FloatProperty xwGrimTestDelaySpeedThreshold = new FloatProperty("xw-grim-test-delay-speed-threshold", 0.6f, 0.1f, 2f, () -> this.isXwMode() && (this.isXwMode("GrimTest") && "Reduce".equals(this.xwGrimTestType.getModeString()) && this.xwGrimTestDelayRelease.getValue()));
    public final IntProperty xwGrimTestDelayTimeoutMs = new IntProperty("xw-grim-test-delay-timeout-ms", 5000, 50, 10000, () -> this.isXwMode() && (this.isXwMode("GrimTest") && "Reduce".equals(this.xwGrimTestType.getModeString()) && this.xwGrimTestDelayRelease.getValue()));
    public final BooleanProperty xwGrimTestDelayLogging = new BooleanProperty("xw-grim-test-delay-logging", false, () -> this.isXwMode() && (this.isXwMode("GrimTest") && "Reduce".equals(this.xwGrimTestType.getModeString()) && this.xwGrimTestDelayRelease.getValue()));
    public final IntProperty xwGrimTestJumpResetTick = new IntProperty("xw-grim-test-jump-reset-tick", 1, 0, 9, () -> this.isXwMode() && (this.isXwMode("GrimTest") && "Jump Reset".equals(this.xwGrimTestType.getModeString())));
    public final FloatProperty xwGrimTestGrimLevel = new FloatProperty("xw-grim-test-grim-level", 0.001f, 0.001f, 0.1f, () -> this.isXwMode() && (this.isXwMode("GrimTest") && "Grim Full".equals(this.xwGrimTestType.getModeString())));
    public final BooleanProperty xwGrimTestLogging = new BooleanProperty("xw-grim-test-logging", true, () -> this.isXwMode() && (this.isXwMode("GrimTest")));
    public final BooleanProperty xwWdStack = new BooleanProperty("xw-wd-stack", true, () -> this.isXwMode() && (this.isXwMode("Watchdog")));
    public final BooleanProperty xwWdPingSpoof = new BooleanProperty("xw-wd-ping-spoof", true, () -> this.isXwMode() && (this.isXwMode("Watchdog")));
    public final BooleanProperty xwWdAlwaysCancelVertical = new BooleanProperty("xw-wd-always-cancel-vertical", false, () -> this.isXwMode() && (this.isXwMode("Watchdog")));
    public final BooleanProperty xwWdBacktrack = new BooleanProperty("xw-wd-backtrack", false, () -> this.isXwMode() && (this.isXwMode("Watchdog")));
    public final BooleanProperty xwWdDamageBoost = new BooleanProperty("xw-wd-damage-boost", false, () -> this.isXwMode() && (this.isXwMode("Watchdog")));
    public final FloatProperty xwWdDamageBoostSpeed = new FloatProperty("xw-wd-damage-boost-speed", 1f, 1f, 10f, () -> this.isXwMode() && (this.isXwMode("Watchdog") && this.xwWdDamageBoost.getValue()));
    public final FloatProperty xwWdPredVelLetThrough = new FloatProperty("xw-wd-pred-vel-let-through", 0.3f, 0f, 0.6f, () -> this.isXwMode() && (this.isXwMode("WatchdogPrediction")));
    public final BooleanProperty xwWdPredPreventGhosting = new BooleanProperty("xw-wd-pred-prevent-ghosting", true, () -> this.isXwMode() && (this.isXwMode("WatchdogPrediction")));
    public final BooleanProperty xwWdReduceCancelOnGround = new BooleanProperty("xw-wd-reduce-cancel-on-ground", true, () -> this.isXwMode() && (this.isXwMode("WatchdogReduce")));
    public final BooleanProperty xwWdReduceCancelOnAttack = new BooleanProperty("xw-wd-reduce-cancel-on-attack", true, () -> this.isXwMode() && (this.isXwMode("WatchdogReduce")));
    public final BooleanProperty xwWdReduceCancelExplosions = new BooleanProperty("xw-wd-reduce-cancel-explosions", true, () -> this.isXwMode() && (this.isXwMode("WatchdogReduce")));
    public final IntProperty xwWdDmgHorizontal = new IntProperty("xw-wd-dmg-horizontal", 0, 0, 100, () -> this.isXwMode() && (this.isXwMode("WatchdogDamageBoost")));
    public final IntProperty xwWdDmgVertical = new IntProperty("xw-wd-dmg-vertical", 0, 0, 100, () -> this.isXwMode() && (this.isXwMode("WatchdogDamageBoost")));
    public final IntProperty xwTickVelocityValue = new IntProperty("xw-tick-velocity", 1, 1, 6, () -> this.isXwMode() && (this.isXwMode("Tick")));
    public final IntProperty xwGroundDelay = new IntProperty("xw-ground-delay", 1, 0, 20, () -> this.isXwMode() && (this.isXwMode("Ground")));
    private final XwVelocity xw = new XwVelocity(this);

    public boolean isXwMode(String... names) {
        int index = mode.getValue() - 8;
        if (index < 0 || index >= XwVelocity.MODES.length) return false;
        if (names.length == 0) return true;
        for (String name : names) if (XwVelocity.MODES[index].equals(name)) return true;
        return false;
    }

    @Override
    public void verifyValue(String name) {
        if ("mode".equals(name) && xw != null) {
            onDisabled();
            if (isEnabled()) onEnabled();
        }
    }

    @EventTarget
    public void onXwJump(JumpEvent event) {
        if (isEnabled() && isXwMode()) xw.onJump(event);
    }

    @EventTarget
    public void onXwBlockBB(BlockBBEvent event) {
        if (isEnabled() && isXwMode()) xw.onBlockBB(event);
    }

    @EventTarget
    public void onXwAttack(AttackEvent event) {
        if (isEnabled() && isXwMode() && !event.isCancelled()) xw.onAttack(event);
    }

    private boolean isInLiquidOrWeb() {
        return mc.thePlayer.isInWater() || mc.thePlayer.isInLava() || ((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    private boolean canDelay() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return mc.thePlayer.onGround && (!killAura.isEnabled() || !killAura.shouldAutoBlock());
    }

    public Velocity() {
        super("Velocity", false);
    }

    @EventTarget
    public void onKnockback(KnockbackEvent event) {
        if (this.isXwMode()) return;
        if (this.isEnabled() && !event.isCancelled() && this.mode.getValue() >= PREDICTION) {
            this.allowNext = true;
            if (this.pendingExplosion) {
                // explosions are left alone in these modes (Hypixel uses them as bait)
                this.pendingExplosion = false;
                return;
            }
            if (this.mode.getValue() == HYPIXEL) {
                if (Myau.moduleManager.modules.get(Fly.class).isEnabled()) {
                    // same exception as the packet layer: Nextgen skips the whole mode while flying
                    return;
                }
                // keep our own horizontal motion, take only the vertical part
                event.setX(mc.thePlayer.motionX);
                event.setZ(mc.thePlayer.motionZ);
            } else if (this.mode.getValue() == PREDICTION) {
                if (this.rotate.getValue() && event.getY() > 0.0) {
                    this.knockbackX = event.getX();
                    this.knockbackZ = event.getZ();
                    if (Math.abs(this.knockbackX) > 0.01 || Math.abs(this.knockbackZ) > 0.01) {
                        this.rotateTickCounter = 1;
                    }
                }
                if (!this.predDelay.getValue()) {
                    this.ticksSinceVelocity = 0;
                }
            }
            return;
        }
        if (!this.isEnabled() || event.isCancelled()) {
            this.pendingExplosion = false;
            this.allowNext = true;
        } else if (!this.allowNext || !(Boolean) this.fakeCheck.getValue()) {
            this.allowNext = true;
            if (this.pendingExplosion) {
                this.pendingExplosion = false;
                if (this.explosionHorizontal.getValue() > 0) {
                    event.setX(event.getX() * (double) this.explosionHorizontal.getValue() / 100.0);
                    event.setZ(event.getZ() * (double) this.explosionHorizontal.getValue() / 100.0);
                } else {
                    event.setX(mc.thePlayer.motionX);
                    event.setZ(mc.thePlayer.motionZ);
                }
                if (this.explosionVertical.getValue() > 0) {
                    event.setY(event.getY() * (double) this.explosionVertical.getValue() / 100.0);
                } else {
                    event.setY(mc.thePlayer.motionY);
                }
            } else {
                this.chanceCounter = this.chanceCounter % 100 + this.chance.getValue();
                if (this.chanceCounter >= 100) {
                    // 如果是 LEGIT_FAST 模式，我们【不修改】原生事件的数据包数据，让 100% 击退通过，以避开反作弊检查
                    if (this.mode.getValue() == 4) {
                        return;
                    }

                    this.jumpFlag = (this.mode.getValue() == 1 || this.mode.getValue() == 2) && event.getY() > 0.0;
                    this.delayActive = this.mode.getValue() == 3;
                    if (this.horizontal.getValue() > 0) {
                        event.setX(event.getX() * (double) this.horizontal.getValue() / 100.0);
                        event.setZ(event.getZ() * (double) this.horizontal.getValue() / 100.0);
                    } else {
                        event.setX(mc.thePlayer.motionX);
                        event.setZ(mc.thePlayer.motionZ);
                    }
                    if (this.vertical.getValue() > 0) {
                        event.setY(event.getY() * (double) this.vertical.getValue() / 100.0);
                    } else {
                        event.setY(mc.thePlayer.motionY);
                    }
                }
            }
        }
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (isXwMode()) {
            if (isEnabled()) xw.onUpdate(event);
            return;
        }
        if (event.getType() == EventType.POST) {
            if (this.reverseFlag
                    && (
                    this.canDelay()
                            || this.isInLiquidOrWeb()
                            || Myau.delayManager.getDelay() >= (long) this.delayTicks.getValue()
            )) {
                Myau.delayManager.setDelayState(false, DelayModules.VELOCITY);
                this.reverseFlag = false;
            }
            if (this.delayActive) {
                MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
                this.delayActive = false;
            }

            // ==========================================
            // 🌟 LEGIT_FAST (Legit Fast) 状态机接管
            // ==========================================
            // Never produce movement while the module is disabled
            if (this.isEnabled() && this.mode.getValue() == 4) {
                if (mc.thePlayer.hurtTime == 9 || (mc.thePlayer.hurtTime == 10 && this.packetHitReceived)) {
                    this.packetHitReceived = false;
                    this.legitTickCounter = 1; // 激活 Tick 计数器
                }

                if (this.legitTickCounter > 0) {
                    if (this.legitTickCounter == 1) {
                        // 1-Tick：在受到伤害、冲量刚加载的黄金第 1 个 Tick 立刻起跳
                        if (mc.thePlayer.onGround && !this.isInLiquidOrWeb()) {
                            mc.thePlayer.jump();

                            // 极速空气动力补偿：微幅放大第一帧向前的阻尼向量
                            if (MoveUtil.getSpeed() > 0.05) {
                                MoveUtil.setSpeed(MoveUtil.getSpeed() * 1.05, MoveUtil.getMoveYaw());
                            }
                        }
                    }
                    this.legitTickCounter++;
                    if (this.legitTickCounter > 3) {
                        this.legitTickCounter = 0; // 3个 Tick 后平滑释放控制权
                    }
                }
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        // ==========================================
        // 🌟 LEGIT_FAST：0-Tick 级智能肌肉断键 (W-Tap Overdrive)
        // ==========================================
        if (this.isEnabled() && this.mode.getValue() == 4) {
            // 当伤害包刚到达（hurtTime刚闪红）的极瞬之间
            if (mc.thePlayer.hurtTime == 10 || this.packetHitReceived) {
                // 强行将前进动力切断 1 个 Tick，并在侧向施加反向对冲力，利用原版地面摩擦力锁死位移
                mc.thePlayer.movementInput.moveForward = -0.15F;
                mc.thePlayer.movementInput.moveStrafe *= 0.5F;
            }
        }
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.jumpFlag) {
            this.jumpFlag = false;
            if (mc.thePlayer.onGround && mc.thePlayer.isSprinting() && !mc.thePlayer.isPotionActive(Potion.jump) && !this.isInLiquidOrWeb()) {
                mc.thePlayer.movementInput.jump = true;
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (isXwMode()) {
            if (isEnabled()) xw.onPacket(event);
            return;
        }
        if (this.isEnabled() && event.getType() == EventType.RECEIVE && !event.isCancelled()) {
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
                if (packet.getEntityID() == mc.thePlayer.getEntityId()) {
                    if (this.handleNewModesVelocity(event, packet)) {
                        return;
                    }

                    // 如果是 LEGIT_FAST 模式，在这里精确捕捉包到达的绝对 0-Tick 时机
                    if (this.mode.getValue() == 4) {
                        if (packet.getMotionY() > 0 && !this.isInLiquidOrWeb()) {
                            this.packetHitReceived = true;
                        }
                    }

                    LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
                    if (this.mode.getValue() == 2
                            && !this.reverseFlag
                            && !this.canDelay()
                            && !this.isInLiquidOrWeb()
                            && !this.pendingExplosion
                            && (!this.allowNext || !(Boolean) this.fakeCheck.getValue())
                            && (!longJump.isEnabled() || !longJump.canStartJump())) {
                        this.delayChanceCounter = this.delayChanceCounter % 100 + this.delayChance.getValue();
                        if (this.delayChanceCounter >= 100) {
                            Myau.delayManager.setDelayState(true, DelayModules.VELOCITY);
                            Myau.delayManager.delayedPacket.offer(packet);
                            event.setCancelled(true);
                            this.reverseFlag = true;
                            return;
                        }
                    }
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sVelocity (&otick: %d, x: %.2f, y: %.2f, z: %.2f&r)&r",
                                        Myau.clientName,
                                        mc.thePlayer.ticksExisted,
                                        (double) packet.getMotionX() / 8000.0,
                                        (double) packet.getMotionY() / 8000.0,
                                        (double) packet.getMotionZ() / 8000.0
                                )
                        );
                    }
                }
            } else if (!(event.getPacket() instanceof S27PacketExplosion)) {
                if (event.getPacket() instanceof S19PacketEntityStatus) {
                    S19PacketEntityStatus packet = (S19PacketEntityStatus) event.getPacket();
                    Entity entity = packet.getEntity(mc.theWorld);
                    if (entity != null && entity.equals(mc.thePlayer) && packet.getOpCode() == 2) {
                        this.allowNext = false;
                    }
                }
            } else if (this.mode.getValue() < PREDICTION) {
                S27PacketExplosion packet = (S27PacketExplosion) event.getPacket();
                if (packet.func_149149_c() != 0.0F || packet.func_149144_d() != 0.0F || packet.func_149147_e() != 0.0F) {
                    this.pendingExplosion = true;
                    if (this.explosionHorizontal.getValue() == 0 || this.explosionVertical.getValue() == 0) {
                        event.setCancelled(true);
                    }
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sExplosion (&otick: %d, x: %.2f, y: %.2f, z: %.2f&r)&r",
                                        Myau.clientName,
                                        mc.thePlayer.ticksExisted,
                                        mc.thePlayer.motionX + (double) packet.func_149149_c(),
                                        mc.thePlayer.motionY + (double) packet.func_149144_d(),
                                        mc.thePlayer.motionZ + (double) packet.func_149147_e()
                                )
                        );
                    }
                }
            }
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        xw.reset(false);
        this.onDisabled();
    }

    /** @return true when the S12 was fully handled by PREDICTION / HYPIXEL / HYPIXEL_AIR. */
    private boolean handleNewModesVelocity(PacketEvent event, S12PacketEntityVelocity packet) {
        switch (this.mode.getValue()) {
            case PREDICTION: {
                if (!this.predDelay.getValue()) {
                    hasReceivedVelocity = true;
                }
                LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
                if (!this.predDelayFlag
                        && !this.isInLiquidOrWeb()
                        && !this.pendingExplosion
                        && (!this.allowNext || !this.fakeCheck.getValue())
                        && (!longJump.isEnabled() || !longJump.canStartJump())) {
                    boolean airborne = !mc.thePlayer.onGround;
                    if (this.airBuffer.getValue() && airborne
                            || this.predDelay.getValue() && airborne
                            || this.predDelay.getValue() && this.groundDelay.getValue() && !this.airBuffer.getValue()) {
                        Myau.delayManager.setDelayState(true, DelayModules.VELOCITY);
                        this.dbg("Delay/Buffer active");
                        Myau.delayManager.delayedPacket.offer(packet);
                        event.setCancelled(true);
                        this.predDelayFlag = true;
                        return true;
                    }
                }
                return false;
            }
            case HYPIXEL: {
                if (Myau.moduleManager.modules.get(Fly.class).isEnabled()) {
                    return false;
                }
                if (!mc.thePlayer.onGround && !this.absorbedVelocity) {
                    event.setCancelled(true);
                    this.absorbedVelocity = true;
                    this.dbg("Hypixel: absorbed air velocity");
                    return true;
                }
                return false; // XZ is replaced in onKnockback
            }
            case HYPIXEL_AIR:
                event.setCancelled(true);
                this.airVelocity = true;
                return true;
            default:
                return false;
        }
    }

    private KillAura killAura() {
        return (KillAura) Myau.moduleManager.modules.get(KillAura.class);
    }

    private void dbg(String msg) {
        if (this.debugLog.getValue()) {
            ChatUtil.sendFormatted(Myau.clientName + "Velocity: " + msg);
        }
    }

    private void reduceAttack(Entity entity) {
        EventManager.call(new AttackEvent((EntityLivingBase) entity));
        mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
        mc.getNetHandler().addToSendQueue(new C02PacketUseEntity(entity, C02PacketUseEntity.Action.ATTACK));
        mc.thePlayer.motionX *= 0.6D;
        mc.thePlayer.motionZ *= 0.6D;
        mc.thePlayer.setSprinting(false);
    }

    @EventTarget
    public void onTickPrediction(TickEvent event) {
        if (isXwMode()) {
            if (isEnabled()) xw.onTick(event);
            return;
        }
        // LeaderClient counts on both PRE and POST tick events; kept as-is since its timings were tuned that way
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.mode.getValue() == HYPIXEL && mc.thePlayer.onGround) {
            this.absorbedVelocity = false;
        }
        if (this.mode.getValue() != PREDICTION) {
            return;
        }
        if (this.ticksSinceVelocity >= 0) {
            this.ticksSinceVelocity++;
        }
        if (this.ticksSinceVelocity >= 10) {
            this.ticksSinceVelocity = -1;
        }
        if (this.jumpReset.getValue()) {
            this.handleJumpReset();
        }
    }

    private void handleJumpReset() {
        if (mc.currentScreen instanceof GuiInventory || LeaderScaffold.isAnyScaffoldEnabled()) {
            return;
        }
        int jumpKey = mc.gameSettings.keyBindJump.getKeyCode();
        if (this.ticksSinceVelocity >= 0) {
            if (this.ticksSinceVelocity == 0) {
                this.jumpWasPressed = mc.gameSettings.keyBindJump.isPressed();
            }
            if (this.ticksSinceVelocity <= 2 && mc.thePlayer.onGround) {
                KeyBindUtil.setKeyBindState(jumpKey, true);
            }
        }
        if (this.ticksSinceVelocity >= 4 && this.ticksSinceVelocity <= 9) {
            KeyBindUtil.setKeyBindState(jumpKey, this.jumpWasPressed);
        }
    }

    @EventTarget
    public void onUpdatePrediction(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.mode.getValue() == HYPIXEL_AIR && event.getType() == EventType.PRE) {
            if (this.airVelocity) {
                // one-shot: checked on the first PRE after the S12 only (Legacy behaviour, no deferred landing jump)
                if (mc.thePlayer.onGround) {
                    MoveUtil.tryJump();
                }
                this.airVelocity = false;
            }
            return;
        }
        if (this.mode.getValue() != PREDICTION) {
            return;
        }
        KillAura killAura = this.killAura();
        String ab = killAura.getAutoBlockModeName();
        boolean abLeader = ab.equals("HYPIXEL_LEADER");
        boolean abCustom = ab.equals("HYPIXEL_CUSTOM");
        if (this.reduce.getValue() && event.getType() == EventType.PRE) {
            if (velocityAttacked) {
                if (killAura.isEnabled() && killAura.getTarget() != null) {
                    this.dbg("extra attack");
                    this.reduceAttack(killAura.getTarget());
                }
                velocityAttacked = false;
            }
            if (hasReceivedVelocity) {
                if (this.reduceTick >= this.attackTimes.getValue()) {
                    this.reduceTick = 0;
                    hasReceivedVelocity = false;
                }
                RayCastUtil.RayCastResult hit = RayCastUtil.rayCast(new RotationUtil.RotationVec(event.getYaw(), event.getPitch()), 3);
                if (hit != null && this.reduceMode.getValue() == 0
                        && hit.entityHit instanceof EntityPlayer && hit.entityHit != mc.thePlayer
                        && (mc.thePlayer.isSprinting() || !this.onlySprinting.getValue())) {
                    if (killAura.getTarget() != null) {
                        if (!this.reduceWhenCanAttack.getValue()
                                || !abLeader && !abCustom
                                || killAura.isBlockAttackPhase()) {
                            this.reduceAttack(killAura.getTarget());
                        }
                    } else {
                        this.reduceAttack(hit.entityHit);
                    }
                }
                this.reduceTick++;
            }
        }
        if (event.getType() == EventType.PRE) {
            int maxTick = this.rotateTick.getValue();
            if (this.rotateTickCounter > 0 && this.rotateTickCounter <= maxTick) {
                if (this.rotateTickCounter == 1) {
                    this.targetRotation = RotationUtil.getRotationsTo(-this.knockbackX, 0, -this.knockbackZ, event.getYaw(), event.getPitch());
                }
                if (this.targetRotation != null) {
                    event.setRotation(this.targetRotation[0], this.targetRotation[1], 2);
                    event.setPervRotation(this.targetRotation[0], 2);
                }
            }
        }
        if (event.getType() == EventType.POST) {
            int maxTick = this.rotateTick.getValue();
            if (this.rotateTickCounter > 0 && this.rotateTickCounter <= maxTick) {
                this.rotateTickCounter++;
                if (this.rotateTickCounter > maxTick) {
                    this.rotateTickCounter = 0;
                    this.targetRotation = null;
                    this.knockbackX = 0;
                    this.knockbackZ = 0;
                }
            }
            // Same condition as LeaderClient: buffered velocity is released on ground (or after N ticks),
            // and with reduce-mode RELEASE_* it is also "released" on KillAura's attack / unblock tick.
            boolean release = this.predDelayFlag && (
                    this.predDelay.getValue() && (this.isInLiquidOrWeb() || Myau.delayManager.getDelay() >= (long) this.predDelayTicks.getValue() && !this.airBuffer.getValue())
                            || mc.thePlayer.onGround && !this.groundDelay.getValue() && !this.airBuffer.getValue()
                            || this.airBuffer.getValue() && mc.thePlayer.onGround);
            if (this.reduce.getValue() && abLeader && killAura.shouldAutoBlock()
                    && (this.reduceMode.getValue() == 1 && killAura.blockTick == 0
                    || this.reduceMode.getValue() == 2 && killAura.blockTick == 2)) {
                release = true;
            }
            if (release) {
                this.ticksSinceVelocity = 0;
                if (killAura.getTarget() != null && this.extraAttack.getValue() && this.reduce.getValue()
                        && this.reduceMode.getValue() != 0 && !extraAttacked) {
                    extraAttacked = true;
                    velocityAttacked = true;
                }
                hasReceivedVelocity = true;
                this.dbg("Delay/Buffer " + Myau.delayManager.getDelay() + " ticks");
                Myau.delayManager.setDelayState(false, DelayModules.VELOCITY);
                this.predDelayFlag = false;
            }
        }
    }

    @EventTarget
    public void onMoveInputPrediction(MoveInputEvent event) {
        if (this.isEnabled() && this.mode.getValue() == PREDICTION
                && this.rotateTickCounter > 0 && this.rotateTickCounter <= this.rotateTick.getValue()) {
            if (this.autoMove.getValue()) {
                mc.thePlayer.movementInput.moveForward = 1.0F;
            }
            if (this.targetRotation != null && RotationState.isActived() && RotationState.getPriority() == 2.0F && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
        }
    }

    @Override
    public void onEnabled() {
        xw.reset(false);
        hasReceivedVelocity = false;
        this.rotateTickCounter = 0;
        this.targetRotation = null;
        this.knockbackX = 0;
        this.knockbackZ = 0;
        this.absorbedVelocity = false;
        this.airVelocity = false;
    }

    @Override
    public void onDisabled() {
        xw.reset(true);
        this.pendingExplosion = false;
        this.allowNext = true;
        this.jumpFlag = false;
        this.delayActive = false;
        if (this.reverseFlag) {
            Myau.delayManager.setDelayState(false, DelayModules.VELOCITY);
            this.reverseFlag = false;
        }
        this.packetHitReceived = false;
        this.legitTickCounter = 0;
        hasReceivedVelocity = false;
        extraAttacked = false;
        velocityAttacked = false;
        if (this.predDelayFlag) {
            Myau.delayManager.setDelayState(false, DelayModules.VELOCITY);
            this.predDelayFlag = false;
        }
        this.ticksSinceVelocity = -1;
        this.rotateTickCounter = 0;
        this.targetRotation = null;
        this.absorbedVelocity = false;
        this.airVelocity = false;
    }

    @Override
    public String[] getSuffix() {
        if (isXwMode()) return new String[]{"Xw " + XwVelocity.MODES[mode.getValue() - 8]};
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
