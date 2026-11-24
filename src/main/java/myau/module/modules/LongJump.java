package myau.module.modules;

import myau.events.Render2DEvent;
import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.module.mode.ModeList;
import myau.module.modules.longjumpmodes.LongJumpMode;
import myau.module.modules.longjumpmodes.aac.AACv1;
import myau.module.modules.longjumpmodes.aac.AACv2;
import myau.module.modules.longjumpmodes.aac.AACv3;
import myau.module.modules.longjumpmodes.matrix.Matrix;
import myau.module.modules.longjumpmodes.ncp.NCP;
import myau.module.modules.longjumpmodes.other.Buzz;
import myau.module.modules.longjumpmodes.other.Hycraft;
import myau.module.modules.longjumpmodes.other.KKCraft;
import myau.module.modules.longjumpmodes.other.Redesky;
import myau.module.modules.longjumpmodes.other.VerusDamage;
import myau.util.*;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemFireball;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

public class LongJump extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** The original fireball modes (indices 0-3); their logic lives in this class. */
    private static final String[] LEGACY = {"FIREBALL", "FIREBALL_MANUAL", "FIREBALL_HIGH", "FIREBALL_FLAT"};
    private final TimerUtil fireballTimer = new TimerUtil();
    private final TimerUtil jumpTimer = new TimerUtil();
    private boolean isJumping = false;
    private int tickCounter = 0;
    private int jumpModeStage = 0;
    private boolean readyToUseFireball = false;
    private boolean fireballLaunched = false;
    private int savedHotbarSlot = -1;
    /** Modes ported from xw, selected by mode index {@code LEGACY.length + i}. Must be declared before {@link #mode}. */
    private final ModeList<LongJump, LongJumpMode> modes = new ModeList<LongJump, LongJumpMode>(
            new NCP(),
            new AACv1(), new AACv2(), new AACv3(),
            new Matrix(),
            new Redesky(), new Hycraft(), new Buzz(), new VerusDamage(), new KKCraft()
    );
    public final ModeProperty mode = new ModeProperty("mode", 0, concat(LEGACY, this.modes.names()));
    public final FloatProperty motion = new FloatProperty("motion", 1.0F, 1.0F, 20.0F, this::isLongJumpMode);
    public final FloatProperty speedMotion = new FloatProperty("speed-motion", 1.0F, 1.0F, 20.0F, this::isLongJumpMode);
    public final PercentProperty strafe = new PercentProperty("strafe", 0, this::isLongJumpMode);
    public final FloatProperty ncpBoost = new FloatProperty("ncp-boost", 4.25F, 1.0F, 10.0F, () -> this.is("NCP"));
    public final ModeProperty matrixBypassMethod = new ModeProperty("matrix-bypass-method", 0, new String[]{"Fall", "NoGround"}, () -> this.is("Matrix"));
    public final FloatProperty matrixBoostSpeed = new FloatProperty("matrix-boost-speed", 2.1F, -3.0F, 8.0F, () -> this.is("Matrix"));
    public final BooleanProperty autoJump = new BooleanProperty("auto-jump", true, () -> !this.isLongJumpMode());
    public final BooleanProperty autoDisable = new BooleanProperty("auto-disable", true, () -> this.is("VerusDamage") || this.is("Matrix"));

    // State shared with the xw modes (xw kept these on the module object as well).
    /** The player left the ground by jumping and has not landed yet. Tracked even while disabled, like xw. */
    public boolean jumped = false;
    public boolean canBoost = false;
    public boolean teleported = false;
    /** VerusDamage: the damage packets were sent on enable. */
    public boolean verusDamaged = false;
    /** The xw mode that is currently running; null while a fireball mode is selected or the module is off. */
    private LongJumpMode active;

    private static String[] concat(String[] first, String[] second) {
        String[] result = new String[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private LongJumpMode cur() {
        int index = this.mode.getValue() - LEGACY.length;
        return index < 0 ? null : this.modes.get(index);
    }

    private boolean is(String modeName) {
        return this.mode.getModeString().equals(modeName);
    }

    private int findFireballInHotbar() {
        if (mc.thePlayer == null) {
            return -1;
        } else {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && stack.getItem() instanceof ItemFireball) {
                    return i;
                }
            }
            return -1;
        }
    }

    private double getMotionFactor() {
        return MoveUtil.getSpeedLevel() > 0
                ? (double) this.speedMotion.getValue()
                : (double) this.motion.getValue();
    }

    public LongJump() {
        super("LongJump", false);
        this.modes.bind(this);
    }

    public boolean isAutoMode() {
        return this.mode.getValue() == 0 || this.mode.getValue() == 2 || this.mode.getValue() == 3;
    }

    public boolean isManualMode() {
        return this.mode.getValue() == 1;
    }

    /** True for the fireball modes (auto or manual); false for the modes ported from xw. */
    public boolean isLongJumpMode() {
        return this.isAutoMode() || this.isManualMode();
    }

    /** Fireball modes only: a fireball was used within the last second and the knockback has not arrived yet. */
    public boolean canStartJump() {
        return this.isLongJumpMode() && !this.fireballTimer.hasTimeElapsed(1000L) && !this.isJumping;
    }

    /** A long jump is in progress: the fireball boost, or (while enabled) the airborne phase of an xw mode. */
    public boolean isJumping() {
        return this.isJumping || this.isEnabled() && this.active != null && this.jumped;
    }

    @EventTarget(Priority.HIGHEST)
    public void onKnockback(KnockbackEvent event) {
        if (this.isEnabled() && !event.isCancelled()) {
            if ((this.isManualMode() || this.isAutoMode()) && this.canStartJump()) {
                event.setCancelled(true);
                this.isJumping = true;
                this.tickCounter = 0;
            }
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled()) {
            switch (event.getType()) {
                case PRE:
                    if (this.isAutoMode() && !this.fireballLaunched && this.readyToUseFireball) {
                        int slot = this.findFireballInHotbar();
                        if (slot != -1) {
                            this.savedHotbarSlot = mc.thePlayer.inventory.currentItem;
                            mc.thePlayer.inventory.currentItem = slot;
                            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                            this.fireballTimer.reset();
                            this.fireballLaunched = true;
                        }
                    }
                    break;
                case POST:
                    if (this.savedHotbarSlot != -1) {
                        mc.thePlayer.inventory.currentItem = this.savedHotbarSlot;
                        this.savedHotbarSlot = -1;
                    }
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.active != null) {
                this.updateMode(event);
                return;
            }
            if (this.isLongJumpMode() && this.isJumping) {
                this.tickCounter++;
                if (this.tickCounter == 1) {
                    switch (this.mode.getValue()) {
                        case 0:
                        case 1:
                            this.jumpModeStage = 0;
                            break;
                        case 2:
                            this.jumpModeStage = 1;
                            break;
                        case 3:
                            this.jumpModeStage = MoveUtil.isForwardPressed() ? 2 : 1;
                    }
                }
                if (this.tickCounter == 2 && MoveUtil.isForwardPressed()) {
                    MoveUtil.setSpeed(MoveUtil.getSpeed() * this.getMotionFactor());
                }
                if (this.tickCounter >= 1 && this.tickCounter <= 30) {
                    switch (this.jumpModeStage) {
                        case 1:
                            if (this.tickCounter == 1) {
                                mc.thePlayer.motionY *= 0.75;
                            } else {
                                double motion = mc.thePlayer.motionY / 0.98F + 0.055;
                                if (motion > 0.0) {
                                    mc.thePlayer.motionY = motion;
                                }
                            }
                            break;
                        case 2:
                            if (this.tickCounter == 1) {
                                mc.thePlayer.motionY *= 0.75;
                            } else {
                                mc.thePlayer.motionY = 0.01 + (double) this.tickCounter * 0.003;
                            }
                    }
                }
                if (this.tickCounter >= 30) {
                    this.isJumping = false;
                    this.tickCounter = 0;
                    this.jumpModeStage = 0;
                    if (this.isAutoMode()) {
                        this.setEnabled(false);
                    }
                    return;
                }
            }
            if (this.isAutoMode() && !this.isJumping) {
                if (this.jumpTimer.hasTimeElapsed(1500L)) {
                    this.setEnabled(false);
                    return;
                }
                this.readyToUseFireball = true;
                float yaw = RotationUtil.quantizeAngle(mc.thePlayer.rotationYaw - 180.0F - RandomUtil.nextFloat(0.0F, 1.0F));
                float pitch = RotationUtil.quantizeAngle(89.0F + RandomUtil.nextFloat(-0.25F, 0.25F));
                event.setRotation(yaw, pitch, 4);
                event.setPervRotation(yaw, 4);
            }
        }
    }

    /** The common update logic of the xw LongJump module (jump tracking, auto jump), for the xw modes only. */
    private void updateMode(UpdateEvent event) {
        LongJumpMode current = this.active;
        if (this.jumped) {
            if (mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
                this.jumped = false;
                if (current instanceof NCP) {
                    mc.thePlayer.motionX = 0.0;
                    mc.thePlayer.motionZ = 0.0;
                }
                return;
            }
            current.onUpdate(event);
        }
        if (this.autoJump.getValue() && mc.thePlayer.onGround && MoveUtil.isMoving()) {
            // VerusDamage must not jump before its damage packets went out. xw applied this check in every
            // mode although the option only exists for VerusDamage, which left auto jump dead elsewhere.
            if (current instanceof VerusDamage && this.autoDisable.getValue() && !this.verusDamaged) {
                return;
            }
            this.jumped = true;
            MoveUtil.tryJump();
        }
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        if (this.isEnabled() && this.active != null) {
            this.active.onMove(event);
        }
    }

    /** Like xw (handler(always = true)): the jump flags are tracked even while the module is off. */
    @EventTarget
    public void onJump(JumpEvent event) {
        this.jumped = true;
        this.canBoost = true;
        this.teleported = false;
        if (this.isEnabled() && this.active != null) {
            this.active.onJump(event);
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (RotationState.isActived()
                    && RotationState.getPriority() == 4.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (this.isEnabled()) {
            if (this.isLongJumpMode()
                    && this.isJumping
                    && this.tickCounter >= 5
                    && this.tickCounter <= 30
                    && this.strafe.getValue() > 0) {
                double speed = MoveUtil.getSpeed();
                MoveUtil.setSpeed(speed * (double) ((float) (100 - this.strafe.getValue()) / 100.0F), MoveUtil.getDirectionYaw());
                MoveUtil.addSpeed(
                        speed * (double) ((float) this.strafe.getValue() / 100.0F), MoveUtil.getMoveYaw()
                );
                MoveUtil.setSpeed(speed);
            }
        }
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (event.getKey() == mc.gameSettings.keyBindUseItem.getKeyCode()) {
            ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
            if (stack != null && stack.getItem() instanceof ItemFireball) {
                this.fireballTimer.reset();
            }
        }
    }

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && this.active != null && mc.thePlayer != null) {
            this.active.onPacket(event);
        }
        if (event.getType() == EventType.RECEIVE && !event.isCancelled()) {
            if (event.getPacket() instanceof S08PacketPlayerPosLook) {
                this.isJumping = false;
                this.tickCounter = 0;
                this.jumpModeStage = 0;
                if (this.isAutoMode()) {
                    this.setEnabled(false);
                }
            }
        }
    }

    @Override
    public void onEnabled() {
        this.jumpTimer.reset();
        this.active = this.cur();
        if (this.active != null) {
            this.active.onEnable();
            return;
        }
        if (this.isAutoMode() && this.findFireballInHotbar() == -1) {
            this.setEnabled(false);
            ChatUtil.sendFormatted(String.format("%s%s: &cNo fireball found in your hotbar!&r", Myau.clientName, this.getName()));
        }
    }

    @Override
    public void onDisabled() {
        this.isJumping = false;
        this.tickCounter = 0;
        this.jumpModeStage = 0;
        this.readyToUseFireball = false;
        this.fireballLaunched = false;
        LongJumpMode previous = this.active;
        this.active = null;
        if (previous != null) {
            previous.onDisable();
        }
    }

    /** Switching the mode while enabled: stop the old xw mode / fireball state and start the new one. */
    @Override
    public void verifyValue(String name) {
        if (this.isEnabled() && this.active != this.cur()) {
            this.isJumping = false;
            this.tickCounter = 0;
            this.jumpModeStage = 0;
            this.readyToUseFireball = false;
            this.fireballLaunched = false;
            this.jumpTimer.reset();
            LongJumpMode previous = this.active;
            this.active = this.cur();
            if (previous != null) {
                previous.onDisable();
            }
            if (this.active != null) {
                this.active.onEnable();
            }
        }
    }

    @Override
    public String[] getSuffix() {
        String mode = this.mode.getModeString();
        if (this.mode.getValue() >= LEGACY.length) {
            return new String[]{mode};
        }
        return mode.contains("FIREBALL") ? new String[]{"Fireball"} : new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, mode)};
    }

    // ---------------------------------------------------------------- dynamic island


    /** Fireball modes: waiting for the knockback, then the boost ticking up; other modes: ready or in the air. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (this.isLongJumpMode()) {
            if (this.isJumping) {
                myau.ui.glass.Island.live("longjump", myau.ui.glass.Island.P_PROGRESS, "LongJump §fboost §7" + this.tickCounter + "/30",
                        myau.ui.GlassTheme.accent(), Math.min(1.0F, this.tickCounter / 30.0F));
            } else if (this.canStartJump()) {
                myau.ui.glass.Island.live("longjump", myau.ui.glass.Island.P_PROGRESS, "LongJump §ewaiting KB",
                        myau.ui.GlassTheme.WARN, 1.0F - Math.min(1.0F, this.fireballTimer.getElapsedTime() / 1000.0F));
            } else {
                myau.ui.glass.Island.live("longjump", myau.ui.glass.Island.P_PROGRESS, "LongJump §7ready", myau.ui.GlassTheme.accent(), -1);
            }
        } else {
            myau.ui.glass.Island.live("longjump", myau.ui.glass.Island.P_PROGRESS,
                    "LongJump §f" + this.mode.getModeString() + (this.jumped ? " §7air" : " §7ready"), myau.ui.GlassTheme.accent(), -1);
        }
    }
}
