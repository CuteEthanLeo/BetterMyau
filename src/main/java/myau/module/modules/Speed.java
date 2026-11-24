package myau.module.modules;

import myau.events.Render2DEvent;
import myau.mixin.IAccessorMinecraft;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.JumpEvent;
import myau.events.LivingUpdateEvent;
import myau.events.MoveEvent;
import myau.events.MoveInputEvent;
import myau.events.TickEvent;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorEntityPlayer;
import myau.module.Module;
import myau.module.mode.ModeList;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.aac.AACHop3313;
import myau.module.modules.speedmodes.aac.AACHop350;
import myau.module.modules.speedmodes.aac.AACHop4;
import myau.module.modules.speedmodes.aac.AACHop5;
import myau.module.modules.speedmodes.hypixel.HypixelHop;
import myau.module.modules.speedmodes.hypixel.HypixelBHop;
import myau.module.modules.speedmodes.hypixel.HypixelLowHop;
import myau.module.modules.speedmodes.hypixel.HypixelLowHop2025;
import myau.module.modules.speedmodes.leader.LeaderPrediction;
import myau.module.modules.speedmodes.intave.IntaveHop14;
import myau.module.modules.speedmodes.matrix.MatrixHop;
import myau.module.modules.speedmodes.matrix.MatrixSlowHop;
import myau.module.modules.speedmodes.matrix.OldMatrixHop;
import myau.module.modules.speedmodes.ncp.Boost;
import myau.module.modules.speedmodes.ncp.Frame;
import myau.module.modules.speedmodes.ncp.MiJump;
import myau.module.modules.speedmodes.ncp.NCPBHop;
import myau.module.modules.speedmodes.ncp.NCPFHop;
import myau.module.modules.speedmodes.ncp.NCPHop;
import myau.module.modules.speedmodes.ncp.NCPYPort;
import myau.module.modules.speedmodes.ncp.OnGround;
import myau.module.modules.speedmodes.ncp.SNCPBHop;
import myau.module.modules.speedmodes.ncp.UNCPHop;
import myau.module.modules.speedmodes.ncp.UNCPHopNew;
import myau.module.modules.speedmodes.other.BlocksMCHop;
import myau.module.modules.speedmodes.other.CustomSpeed;
import myau.module.modules.speedmodes.other.Legit;
import myau.module.modules.speedmodes.other.SlowHop;
import myau.module.modules.speedmodes.other.TeleportCubeCraft;
import myau.module.modules.speedmodes.rise.Grim2Speed;
import myau.module.modules.speedmodes.rise.GrimSpeed;
import myau.module.modules.speedmodes.rise.KoksCraft;
import myau.module.modules.speedmodes.rise.MineMenClub;
import myau.module.modules.speedmodes.rise.MiniBlox;
import myau.module.modules.speedmodes.rise.OldNCPYPort;
import myau.module.modules.speedmodes.rise.Polar;
import myau.module.modules.speedmodes.rise.StrafeRise;
import myau.module.modules.speedmodes.rise.Tatako;
import myau.module.modules.speedmodes.rise.VanillaRise;
import myau.module.modules.speedmodes.rise.Watchdog6Tick;
import myau.module.modules.speedmodes.rise.WatchdogPrediction;
import myau.module.modules.speedmodes.rise.WatchdogRise;
import myau.module.modules.speedmodes.spartan.SpartanYPort;
import myau.module.modules.speedmodes.spectre.SpectreBHop;
import myau.module.modules.speedmodes.spectre.SpectreLowHop;
import myau.module.modules.speedmodes.spectre.SpectreOnGround;
import myau.module.modules.speedmodes.verus.VerusFHop;
import myau.module.modules.speedmodes.verus.VerusHop;
import myau.module.modules.speedmodes.verus.VerusLowHop;
import myau.module.modules.speedmodes.verus.VerusLowHopNew;
import myau.module.modules.speedmodes.vulcan.VulcanGround288;
import myau.module.modules.speedmodes.vulcan.VulcanHop;
import myau.module.modules.speedmodes.vulcan.VulcanLowHop;
import myau.util.MoveUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Timer;


public class Speed extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** The two original modes; their logic stays in this class (indices 0 and 1). */
    private static final String[] LEGACY = {"Vanilla", "Grim"};

    /** Modes ported from xw, in xw's order. Must be declared before {@link #mode}. */
    private final ModeList<Speed, SpeedMode> modes = new ModeList<Speed, SpeedMode>(
            // NCP
            new NCPBHop(), new NCPFHop(), new SNCPBHop(), new NCPHop(), new NCPYPort(), new UNCPHop(), new UNCPHopNew(),
            // AAC
            new AACHop3313(), new AACHop350(), new AACHop4(), new AACHop5(),
            // Spartan
            new SpartanYPort(),
            // Spectre
            new SpectreLowHop(), new SpectreBHop(), new SpectreOnGround(),
            // Verus
            new VerusHop(), new VerusFHop(), new VerusLowHop(), new VerusLowHopNew(),
            // Vulcan
            new VulcanHop(), new VulcanLowHop(), new VulcanGround288(),
            // Matrix
            new OldMatrixHop(), new MatrixHop(), new MatrixSlowHop(),
            // Intave
            new IntaveHop14(),
            // Server specific
            new TeleportCubeCraft(), new HypixelHop(), new HypixelLowHop(), new BlocksMCHop(),
            // Other
            new Boost(), new Frame(), new MiJump(), new OnGround(), new SlowHop(), new Legit(), new CustomSpeed(),
            // Rise modes
            new VanillaRise(), new StrafeRise(), new KoksCraft(), new Polar(), new OldNCPYPort(), new GrimSpeed(),
            new Grim2Speed(), new MineMenClub(), new Tatako(), new WatchdogPrediction(), new Watchdog6Tick(),
            new MiniBlox(), new WatchdogRise(),
            // LiquidBounce Nextgen (Watchdog) / LeaderClient
            new HypixelBHop(), new HypixelLowHop2025(), new LeaderPrediction()
    );

    // 模式选择：0 是原版暴力，1 是绕过模拟预测的 Grim 模式，其余为 xw 移植模式
    public final ModeProperty mode = new ModeProperty("Mode", 0, concat(LEGACY, this.modes.names()));

    public final FloatProperty multiplier = new FloatProperty("multiplier", 1.0F, 0.0F, 10.0F, this::isLegacy);
    public final FloatProperty friction = new FloatProperty("friction", 1.0F, 0.0F, 10.0F, this::isLegacy);
    public final PercentProperty strafe = new PercentProperty("strafe", 0, this::isLegacy);

    // Custom
    public final FloatProperty customY = new FloatProperty("custom-y", 0.42F, 0.0F, 4.0F, () -> this.is("Custom"));
    public final FloatProperty customGroundStrafe = new FloatProperty("custom-ground-strafe", 1.6F, 0.0F, 2.0F, () -> this.is("Custom"));
    public final FloatProperty customAirStrafe = new FloatProperty("custom-air-strafe", 0.0F, 0.0F, 2.0F, () -> this.is("Custom"));
    public final FloatProperty customGroundTimer = new FloatProperty("custom-ground-timer", 1.0F, 0.1F, 2.0F, () -> this.is("Custom"));
    public final IntProperty customAirTimerTick = new IntProperty("custom-air-timer-tick", 5, 1, 20, () -> this.is("Custom"));
    public final FloatProperty customAirTimer = new FloatProperty("custom-air-timer", 1.0F, 0.1F, 2.0F, () -> this.is("Custom"));
    public final BooleanProperty customResetXZ = new BooleanProperty("custom-reset-xz", false, () -> this.is("Custom"));
    public final BooleanProperty customResetY = new BooleanProperty("custom-reset-y", false, () -> this.is("Custom"));
    public final BooleanProperty customNotOnConsuming = new BooleanProperty("custom-not-on-consuming", false, () -> this.is("Custom"));
    public final BooleanProperty customNotOnFalling = new BooleanProperty("custom-not-on-falling", false, () -> this.is("Custom"));
    public final BooleanProperty customNotOnVoid = new BooleanProperty("custom-not-on-void", true, () -> this.is("Custom"));

    // TeleportCubeCraft
    public final FloatProperty cubecraftPortLength = new FloatProperty("cubecraft-port-length", 1.0F, 0.1F, 2.0F, () -> this.is("TeleportCubeCraft"));

    // IntaveHop14
    public final BooleanProperty intaveBoost = new BooleanProperty("intave-boost", true, () -> this.is("IntaveHop14"));
    public final FloatProperty intaveInitialBoostMultiplier = new FloatProperty("intave-initial-boost-multiplier", 1.0F, 0.01F, 10.0F, () -> this.is("IntaveHop14") && this.intaveBoost.getValue());
    public final BooleanProperty intaveLowHop = new BooleanProperty("intave-low-hop", true, () -> this.is("IntaveHop14"));
    public final FloatProperty intaveStrafeStrength = new FloatProperty("intave-strafe-strength", 0.29F, 0.1F, 0.29F, () -> this.is("IntaveHop14"));
    public final FloatProperty intaveGroundTimer = new FloatProperty("intave-ground-timer", 0.5F, 0.1F, 5.0F, () -> this.is("IntaveHop14"));
    public final FloatProperty intaveAirTimer = new FloatProperty("intave-air-timer", 1.09F, 0.1F, 5.0F, () -> this.is("IntaveHop14"));

    // UNCPHopNew
    public final BooleanProperty uncpPullDown = new BooleanProperty("uncp-pull-down", true, () -> this.is("UNCPHopNew"));
    public final IntProperty uncpOnTick = new IntProperty("uncp-on-tick", 5, 5, 9, () -> this.is("UNCPHopNew") && this.uncpPullDown.getValue());
    public final BooleanProperty uncpOnHurt = new BooleanProperty("uncp-on-hurt", true, () -> this.is("UNCPHopNew") && this.uncpPullDown.getValue());
    public final BooleanProperty uncpShouldBoost = new BooleanProperty("uncp-should-boost", true, () -> this.is("UNCPHopNew"));
    public final BooleanProperty uncpTimerBoost = new BooleanProperty("uncp-timer-boost", true, () -> this.is("UNCPHopNew"));
    public final BooleanProperty uncpDamageBoost = new BooleanProperty("uncp-damage-boost", true, () -> this.is("UNCPHopNew"));
    public final BooleanProperty uncpLowHop = new BooleanProperty("uncp-low-hop", true, () -> this.is("UNCPHopNew"));
    public final BooleanProperty uncpAirStrafe = new BooleanProperty("uncp-air-strafe", true, () -> this.is("UNCPHopNew"));

    // MatrixHop / MatrixSlowHop
    public final BooleanProperty matrixLowHop = new BooleanProperty("matrix-low-hop", true, () -> this.is("MatrixHop") || this.is("MatrixSlowHop"));
    public final FloatProperty matrixExtraGroundBoost = new FloatProperty("matrix-extra-ground-boost", 0.2F, 0.0F, 0.5F, () -> this.is("MatrixHop") || this.is("MatrixSlowHop"));

    // HypixelLowHop
    public final BooleanProperty hypixelGlide = new BooleanProperty("hypixel-glide", true, () -> this.is("HypixelLowHop"));

    // HypixelBHop / HypixelLowHop2025 (LiquidBounce Nextgen)
    public final BooleanProperty bhopHorizontalAcceleration = new BooleanProperty("bhop-horizontal-acceleration", true, () -> this.is("HypixelBHop"));
    public final BooleanProperty bhopVerticalAcceleration = new BooleanProperty("bhop-vertical-acceleration", true, () -> this.is("HypixelBHop"));
    public final BooleanProperty lowHop2025Glide = new BooleanProperty("lowhop2025-glide", false, () -> this.is("HypixelLowHop2025"));

    // LeaderPrediction (LeaderClient)
    public final FloatProperty lpTimerBoost = new FloatProperty("lp-timer-boost", 0.5F, 0.1F, 1.0F, () -> this.is("LeaderPrediction"));
    public final IntProperty lpLowTimerTicks = new IntProperty("lp-low-timer-ticks", 6, 1, 10, () -> this.is("LeaderPrediction"));
    public final BooleanProperty lpRotation = new BooleanProperty("lp-rotation", false, () -> this.is("LeaderPrediction"));

    // BlocksMCHop
    public final BooleanProperty bmcFullStrafe = new BooleanProperty("bmc-full-strafe", true, () -> this.is("BlocksMCHop"));
    public final BooleanProperty bmcLowHop = new BooleanProperty("bmc-low-hop", true, () -> this.is("BlocksMCHop"));
    public final BooleanProperty bmcDamageBoost = new BooleanProperty("bmc-damage-boost", true, () -> this.is("BlocksMCHop"));
    public final BooleanProperty bmcDamageLowHop = new BooleanProperty("bmc-damage-low-hop", false, () -> this.is("BlocksMCHop"));
    public final BooleanProperty bmcSafeY = new BooleanProperty("bmc-safe-y", true, () -> this.is("BlocksMCHop"));

    // VanillaRise
    public final FloatProperty vanillaRiseSpeed = new FloatProperty("vanilla-rise-speed", 1.0F, 0.1F, 9.5F, () -> this.is("VanillaRise"));

    // StrafeRise
    public final BooleanProperty strafeRiseHurtBoost = new BooleanProperty("strafe-rise-hurt-boost", false, () -> this.is("StrafeRise"));
    public final FloatProperty strafeRiseBoostSpeed = new FloatProperty("strafe-rise-boost-speed", 1.0F, 0.1F, 9.5F, () -> this.is("StrafeRise") && this.strafeRiseHurtBoost.getValue());

    // OldNCPYPort (declared but unused in xw as well)
    public final BooleanProperty oldNcpYPortSmoothCamera = new BooleanProperty("old-ncp-yport-smooth-camera", false, () -> this.is("OldNCPYPort"));

    // GrimSpeed
    public final FloatProperty grimBoundingBoxSize = new FloatProperty("grim-bounding-box-size", 0.4F, 0.0F, 1.0F, () -> this.is("GrimSpeed"));
    public final FloatProperty grimInPlayerSpeed = new FloatProperty("grim-in-player-speed", 0.08F, 0.0F, 0.08F, () -> this.is("GrimSpeed"));
    public final FloatProperty grimMoveFlyingIncrease = new FloatProperty("grim-move-flying-increase", 0.0001F, 0.0F, 0.001F, () -> this.is("GrimSpeed"));
    public final BooleanProperty grimFastFall = new BooleanProperty("grim-fast-fall", true, () -> this.is("GrimSpeed"));

    // Grim2Speed
    public final BooleanProperty grim2HighPingMode = new BooleanProperty("grim2-high-ping-mode", false, () -> this.is("Grim2Speed"));
    public final FloatProperty grim2Speed = new FloatProperty("grim2-speed", 1.0F, 0.0F, 1.0F, () -> this.is("Grim2Speed"));

    // MineMenClub
    public final BooleanProperty mmcAllowMoreStrafing = new BooleanProperty("mmc-allow-more-strafing", true, () -> this.is("MineMenClub"));

    // MiniBlox
    public final FloatProperty miniBloxSpeed = new FloatProperty("mini-blox-speed", 4.0F, 1.0F, 9.5F, () -> this.is("MiniBlox"));

    // WatchdogPrediction (declared but unused in xw as well)
    public final FloatProperty watchdogPredictionSpeed = new FloatProperty("watchdog-prediction-speed", 1.0F, 1.0F, 10.0F, () -> this.is("WatchdogPrediction"));

    // WatchdogRise
    public final ModeProperty watchdogType = new ModeProperty("watchdog-type", 1, new String[]{"Strafe", "LowStrafe", "NCPStrafe"}, () -> this.is("WatchdogRise"));
    public final BooleanProperty watchdogAirStrafe = new BooleanProperty("watchdog-air-strafe", true, () -> this.is("WatchdogRise"));
    public final BooleanProperty watchdogAlwaysGlideStrafe = new BooleanProperty("watchdog-always-glide-strafe", true, () -> this.is("WatchdogRise"));
    public final BooleanProperty watchdogFrictionOverride = new BooleanProperty("watchdog-friction-override", true, () -> this.is("WatchdogRise"));
    public final BooleanProperty watchdogFrictionFullDisabler = new BooleanProperty("watchdog-friction-full-disabler", true, () -> this.is("WatchdogRise"));
    public final BooleanProperty watchdogDamageBoost = new BooleanProperty("watchdog-damage-boost", false, () -> this.is("WatchdogRise"));
    public final BooleanProperty watchdogDamageStrafeHypixelFlyDisabler = new BooleanProperty("watchdog-damage-strafe-hypixel-fly-disabler", false, () -> this.is("WatchdogRise"));
    public final BooleanProperty watchdogUHCMode = new BooleanProperty("watchdog-uhc-mode", false, () -> this.is("WatchdogRise"));
    public final BooleanProperty watchdogAlternateMotion = new BooleanProperty("watchdog-alternate-motion", false, () -> this.is("WatchdogRise"));

    private boolean wasOnGround = false;

    /** The ported mode that is currently running; null while a legacy mode is selected. */
    private SpeedMode active;

    private static String[] concat(String[] first, String[] second) {
        String[] result = new String[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private boolean isLegacy() {
        return this.mode.getValue() < LEGACY.length;
    }

    private boolean is(String modeName) {
        return this.mode.getModeString().equals(modeName);
    }

    private SpeedMode cur() {
        int index = this.mode.getValue() - LEGACY.length;
        return index < 0 ? null : this.modes.get(index);
    }

    private Timer getMcTimer() {
        return ((IAccessorMinecraft) mc).getTimer();
    }

    // 安全设置 Timer 速度
    private void setTimerSpeed(float speed) {
        Timer t = getMcTimer();
        if (t != null) {
            t.timerSpeed = speed;
        }
    }

    private boolean canBoost() {
        // 修复：换回你原代码里确定存在的 MoveUtil.isForwardPressed()
        return !LeaderScaffold.isAnyScaffoldEnabled() && MoveUtil.isForwardPressed()
                && mc.thePlayer.getFoodStats().getFoodLevel() > 6
                && !mc.thePlayer.isSneaking()
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isInLava()
                && !((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    public Speed() {
        super("Speed", false);
        this.modes.bind(this);
    }

    private void resetState() {
        MoveUtil.setTimer(1.0F);
        ((IAccessorEntityPlayer) mc.thePlayer).setSpeedInAir(0.02F);
    }

    /** Makes {@link #active} follow the selected mode (mode switched while enabled, deferred enable). */
    private void syncMode() {
        SpeedMode target = this.cur();
        if (this.active == target || mc.thePlayer == null) {
            return;
        }
        SpeedMode old = this.active;
        this.active = null;
        this.resetState();
        if (old != null) {
            old.onDisable();
        }
        if (target != null) {
            target.onEnable();
        }
        this.active = target;
    }

    /** The ported mode to forward an event to, or null (legacy mode, no player, sneaking as in xw). */
    private SpeedMode forward() {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return null;
        }
        SpeedMode current = this.active;
        if (current == null || mc.thePlayer.isSneaking()) {
            return null;
        }
        return current;
    }

    private void autoSprint(SpeedMode current) {
        // xw: every mode except Legit forces sprint while moving.
        if (MoveUtil.isMoving() && !(current instanceof Legit)) {
            mc.thePlayer.setSprinting(true);
        }
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null) {
            return;
        }
        this.wasOnGround = false;
        SpeedMode target = this.cur();
        if (target != null) {
            MoveUtil.setTimer(1.0F);
            target.onEnable();
        }
        this.active = target;
    }

    @Override
    public void onDisabled() {
        SpeedMode old = this.active;
        this.active = null;
        if (mc.thePlayer == null) {
            MoveUtil.setTimer(1.0F);
            return;
        }
        this.resetState();
        if (old != null) {
            old.onDisable();
        }
    }

    @Override
    public void verifyValue(String name) {
        if (this.isEnabled() && mc.thePlayer != null && name.equalsIgnoreCase("Mode")) {
            this.syncMode();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (event.getType() == EventType.PRE) {
            this.syncMode();
        }
        SpeedMode current = this.forward();
        if (current == null) {
            return;
        }
        if (event.getType() == EventType.PRE) {
            this.autoSprint(current);
            current.onUpdate(event);
        } else {
            current.onPostMotion();
        }
    }

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        SpeedMode current = this.forward();
        if (current != null) {
            this.autoSprint(current);
            current.onMotion();
        }
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        SpeedMode current = this.forward();
        if (current != null) {
            current.onMove(event);
        }
    }

    @EventTarget
    public void onTickMode(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        SpeedMode current = this.forward();
        if (current != null) {
            current.onTick();
        }
    }

    @EventTarget
    public void onMoveInputMode(MoveInputEvent event) {
        SpeedMode current = this.forward();
        if (current != null) {
            current.onMoveInput();
        }
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        SpeedMode current = this.forward();
        if (current != null) {
            current.onJump(event);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        SpeedMode current = this.forward();
        if (current != null) {
            current.onPacket(event);
        }
    }

    @EventTarget(Priority.LOW)
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (!this.isLegacy()) {
            SpeedMode current = this.forward();
            if (current != null) {
                current.onStrafe(event);
            }
            return;
        }
        if (!this.canBoost()) {
            setTimerSpeed(1.0F);
            return;
        }

        switch (this.mode.getValue()) {
            case 0: // 原本的 Vanilla 暴力模式
                if (mc.thePlayer.onGround) {
                    mc.thePlayer.motionY = 0.42F;
                    MoveUtil.setSpeed(
                            MoveUtil.getJumpMotion() * (double) this.multiplier.getValue().floatValue(),
                            MoveUtil.getMoveYaw()
                    );
                } else {
                    if (this.friction.getValue() != 1.0F) {
                        event.setFriction(event.getFriction() * this.friction.getValue());
                    }
                    if (this.strafe.getValue() > 0) {
                        double speed = MoveUtil.getSpeed();
                        MoveUtil.setSpeed(speed * (double) ((float) (100 - this.strafe.getValue()) / 100.0F), MoveUtil.getDirectionYaw());
                        MoveUtil.addSpeed(
                                speed * (double) ((float) this.strafe.getValue().intValue() / 100.0F), MoveUtil.getMoveYaw()
                        );
                        MoveUtil.setSpeed(speed);
                    }
                }
                break;

            case 1: // Grim/Simulate 模式
                // 【玩法 1: Tick 律动微爆破】
                if (mc.thePlayer.ticksExisted % 4 == 0) {
                    setTimerSpeed(1.08F);
                } else if (mc.thePlayer.ticksExisted % 4 == 1) {
                    setTimerSpeed(0.96F);
                } else {
                    setTimerSpeed(1.0F);
                }

                // 【玩法 2: 严格物理向量注入】
                if (mc.thePlayer.onGround) {
                    mc.thePlayer.jump();

                    // 获取结合了当前朝向的精确 Yaw
                    float strictYaw = MoveUtil.getDirectionYaw();

                    double baseSpeed = 0.2873;
                    if (mc.thePlayer.isPotionActive(net.minecraft.potion.Potion.moveSpeed)) {
                        baseSpeed *= 1.0 + 0.2 * (mc.thePlayer.getActivePotionEffect(net.minecraft.potion.Potion.moveSpeed).getAmplifier() + 1);
                    }

                    MoveUtil.setSpeed(baseSpeed, strictYaw);
                    wasOnGround = true;
                } else {
                    if (wasOnGround) {
                        wasOnGround = false;
                        double currentSpeed = MoveUtil.getSpeed();
                        MoveUtil.setSpeed(currentSpeed, MoveUtil.getDirectionYaw());
                    } else {
                        // 强制校正空中的朝向偏航角，保持 45度 移动向量精确闭合
                        MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getDirectionYaw());
                    }
                }
                break;
        }
    }

    @EventTarget(Priority.LOW)
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.isEnabled() && this.isLegacy() && this.canBoost()) {
            mc.thePlayer.movementInput.jump = false;
        }
    }

    // ---------------------------------------------------------------- dynamic island


    /** While moving: the speed mode and the real horizontal speed. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !myau.util.MoveUtil.isMoving()) return;
        double bps = Math.hypot(mc.thePlayer.posX - mc.thePlayer.prevPosX, mc.thePlayer.posZ - mc.thePlayer.prevPosZ) * 20.0;
        myau.ui.glass.Island.live("speed", myau.ui.glass.Island.P_INFO,
                "Speed §f" + this.mode.getModeString() + " §7" + String.format("%.1f", bps) + " b/s", myau.ui.GlassTheme.accent(), -1);
    }
}
