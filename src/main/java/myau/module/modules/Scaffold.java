package myau.module.modules;

import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorMinecraft;
import myau.mixin.IAccessorRenderManager;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.WorldBoxes;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.Packet;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.util.Timer;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Scaffold extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double[] placeOffsets = new double[]{
            0.03125, 0.09375, 0.15625, 0.21875, 0.28125, 0.34375, 0.40625, 0.46875,
            0.53125, 0.59375, 0.65625, 0.71875, 0.78125, 0.84375, 0.90625, 0.96875
    };
    private int rotationTick = 0;
    private int lastSlot = -1;
    private int blockCount = -1;
    private float yaw = -180.0F;
    private float pitch = 0.0F;
    private boolean canRotate = false;
    private int towerTick = 0;
    private int towerDelay = 0;
    private int stage = 0;
    private int startY = 256;
    private boolean shouldKeepY = false;
    private boolean towering = false;
    private EnumFacing targetFacing = null;

    private final List<Packet<?>> blinkedPackets = new ArrayList<>();
    private boolean isActualBlinking = false;
    private float currentSmoothYaw = -180.0F;
    private float currentSmoothPitch = 0.0F;
    // 🌟 TELLY3 GrimAC 绕过专用字段
    private float cinematicYaw = -180.0F;
    private float cinematicPitch = 0.0F;
    private int tickPlayPhase = 0;
    private int airBlinkTick = 0;
    private boolean airBlinkActive = false;
    // TELLY4 (Hypixel): ticks since leaving the ground, and whether the sent yaw faces away from the walk direction
    private int offGroundTicks = 0;
    private boolean tellyFacingBack = false;
    private static final double[] TELLY_OFFSETS = new double[]{0.5, 0.35, 0.65, 0.2, 0.8};

    /** Place trail, next-block preview and block counter (shared with LeaderScaffold). */
    private final ScaffoldVisuals visuals = new ScaffoldVisuals();

    public final ModeProperty rotationMode = new ModeProperty("rotations", 2, new String[]{"NONE", "DEFAULT", "BACKWARDS", "SIDEWAYS"});
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT"});
    public final ModeProperty sprintMode = new ModeProperty("sprint", 0, new String[]{"NONE", "VANILLA"});
    public final PercentProperty groundMotion = new PercentProperty("ground-motion", 100);
    public final PercentProperty airMotion = new PercentProperty("air-motion", 100);
    public final PercentProperty speedMotion = new PercentProperty("speed-motion", 100);
    public final ModeProperty tower = new ModeProperty("tower", 0, new String[]{"NONE", "VANILLA", "EXTRA", "TELLY", "HYPIXEL"});
    // 🌟 核心模式
    public final ModeProperty keepY = new ModeProperty("keep-y", 0, new String[]{"NONE", "VANILLA", "EXTRA", "TELLY", "TELLY2", "TELLY3-TEST", "TELLY4"});
    public final BooleanProperty keepYonPress = new BooleanProperty("keep-y-on-press", false, () -> this.keepY.getValue() != 0);
    public final BooleanProperty disableWhileJumpActive = new BooleanProperty("no-keep-y-on-jump-potion", false, () -> this.keepY.getValue() != 0);
    public final BooleanProperty multiplace = new BooleanProperty("multi-place", true);
    public final BooleanProperty safeWalk = new BooleanProperty("safe-walk", true);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", false);
    public final BooleanProperty blockCounter = new BooleanProperty("block-counter", true);
    public final BooleanProperty placeTrail = new BooleanProperty("place-trail", true);
    public final BooleanProperty targetPreview = new BooleanProperty("target-preview", true);
    public final BooleanProperty placeGlow = new BooleanProperty("place-glow", true, () -> this.placeTrail.getValue());
    // 🌟 TELLY3 GrimAC 绕过专用属性
    public final ModeProperty grimRotations = new ModeProperty("grim-rotations", 1, new String[]{"NONE", "CINEMATIC"}, () -> this.keepY.getValue() == 5);
    public final ModeProperty tickPlay = new ModeProperty("tick-play", 1, new String[]{"NONE", "MICRO_BURST"}, () -> this.keepY.getValue() == 5);
    public final BooleanProperty airBlink = new BooleanProperty("air-blink", true, () -> this.keepY.getValue() == 5);
    // TELLY4 (Hypixel) settings
    public final IntProperty tellyTicks = new IntProperty("telly-ticks", 2, 0, 6, () -> this.keepY.getValue() == 6);
    public final IntProperty tellyRotSpeed = new IntProperty("telly-rot-speed", 90, 30, 180, () -> this.keepY.getValue() == 6);
    public final BooleanProperty tellyPredict = new BooleanProperty("telly-predict", true, () -> this.keepY.getValue() == 6);

    public Scaffold() {
        super("Scaffold", false);
    }

    public int getSlot() {
        return this.lastSlot;
    }

    private boolean shouldStopSprint() {
        if (this.isTowering()) {
            return false;
        } else if (this.keepY.getValue() == 6) {
            // TELLY4: only drop sprint while the sent yaw faces away from where we walk
            return this.tellyFacingBack;
        } else if (this.keepY.getValue() == 5) {
            // TELLY3 极限速度：无论何时，绝对不松开疾跑
            return false;
        } else {
            boolean isKeepYActive = this.keepY.getValue() == 1 || this.keepY.getValue() == 2;
            return (!isKeepYActive || this.stage <= 0) && this.sprintMode.getValue() == 0;
        }
    }

    private boolean canPlace() {
        BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
        if (bedNuker.isEnabled() && bedNuker.isReady()) {
            return false;
        } else {
            LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
            return !longJump.isEnabled() || !longJump.isAutoMode() || longJump.isJumping();
        }
    }

    private EnumFacing getBestFacing(BlockPos blockPos1, BlockPos blockPos3) {
        double offset = 0.0;
        EnumFacing enumFacing = null;
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (facing != EnumFacing.DOWN) {
                BlockPos pos = blockPos1.offset(facing);
                if (pos.getY() <= blockPos3.getY()) {
                    double distance = pos.distanceSqToCenter((double) blockPos3.getX() + 0.5, (double) blockPos3.getY() + 0.5, (double) blockPos3.getZ() + 0.5);
                    if (enumFacing == null || distance < offset || distance == offset && facing == EnumFacing.UP) {
                        offset = distance;
                        enumFacing = facing;
                    }
                }
            }
        }
        return enumFacing;
    }

    private int getTargetY() {
        int startY = MathHelper.floor_double(mc.thePlayer.posY);
        return (this.stage != 0 && !this.shouldKeepY ? Math.min(startY, this.startY) : startY) - 1;
    }

    private BlockData getBlockData() {
        return this.getBlockData(mc.thePlayer.posX, mc.thePlayer.posZ);
    }

    private BlockData getBlockData(double centerX, double centerZ) {
        BlockPos targetPos = new BlockPos(
                MathHelper.floor_double(centerX),
                this.getTargetY(),
                MathHelper.floor_double(centerZ)
        );
        if (!BlockUtil.isReplaceable(targetPos)) {
            return null;
        } else {
            ArrayList<BlockPos> positions = new ArrayList<>();
            for (int x = -4; x <= 4; x++) {
                for (int y = -4; y <= 0; y++) {
                    for (int z = -4; z <= 4; z++) {
                        BlockPos pos = targetPos.add(x, y, z);
                        if (!BlockUtil.isReplaceable(pos)
                                && !BlockUtil.isInteractable(pos)
                                && !(
                                mc.thePlayer.getDistance((double) pos.getX() + 0.5, (double) pos.getY() + 0.5, (double) pos.getZ() + 0.5)
                                        > (double) mc.playerController.getBlockReachDistance()
                        )
                                && (this.stage == 0 || this.shouldKeepY || pos.getY() < this.startY)) {
                            for (EnumFacing facing : EnumFacing.VALUES) {
                                if (facing != EnumFacing.DOWN) {
                                    BlockPos blockPos = pos.offset(facing);
                                    if (BlockUtil.isReplaceable(blockPos)) {
                                        positions.add(pos);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (positions.isEmpty()) {
                return null;
            } else {
                positions.sort(
                        Comparator.comparingDouble(
                                o -> o.distanceSqToCenter((double) targetPos.getX() + 0.5, (double) targetPos.getY() + 0.5, (double) targetPos.getZ() + 0.5)
                        )
                );
                BlockPos blockPos = positions.get(0);
                EnumFacing facing = this.getBestFacing(blockPos, targetPos);
                return facing == null ? null : new BlockData(blockPos, facing);
            }
        }
    }

    private void place(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        if (ItemUtil.isHoldingBlock() && this.blockCount > 0) {
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.inventory.getCurrentItem(), blockPos, enumFacing, vec3)) {
                if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                    this.blockCount--;
                }
                if (this.swing.getValue()) {
                    mc.thePlayer.swingItem();
                } else {
                    PacketUtil.sendPacket(new C0APacketAnimation());
                }

                this.visuals.recordPlace(blockPos.offset(enumFacing));
            }
        }
    }

    private EnumFacing yawToFacing(float yaw) {
        if (yaw < -135.0F || yaw > 135.0F) {
            return EnumFacing.NORTH;
        } else if (yaw < -45.0F) {
            return EnumFacing.EAST;
        } else {
            return yaw < 45.0F ? EnumFacing.SOUTH : EnumFacing.WEST;
        }
    }

    private double distanceToEdge(EnumFacing enumFacing) {
        switch (enumFacing) {
            case NORTH:
                return mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
            case EAST:
                return Math.ceil(mc.thePlayer.posX) - mc.thePlayer.posX;
            case SOUTH:
                return Math.ceil(mc.thePlayer.posZ) - mc.thePlayer.posZ;
            case WEST:
            default:
                return mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        }
    }

    private float getSpeed() {
        if (!mc.thePlayer.onGround) {
            return (float) this.airMotion.getValue() / 100.0F;
        } else {
            return MoveUtil.getSpeedLevel() > 0
                    ? (float) this.speedMotion.getValue() / 100.0F
                    : (float) this.groundMotion.getValue() / 100.0F;
        }
    }

    private double getRandomOffset() {
        return 0.2155 - RandomUtil.nextDouble(1.0E-4, 9.0E-4);
    }

    private float getCurrentYaw() {
        return MoveUtil.adjustYaw(
                mc.thePlayer.rotationYaw, (float) MoveUtil.getForwardValue(), (float) MoveUtil.getLeftValue()
        );
    }

    private boolean isDiagonal(float yaw) {
        float absYaw = Math.abs(yaw % 90.0F);
        return absYaw > 20.0F && absYaw < 70.0F;
    }

    private boolean isTowering() {
        if (mc.thePlayer.onGround && MoveUtil.isForwardPressed() && !PlayerUtil.isAirAbove()) {
            boolean keepYActive = this.keepY.getValue() >= 3 && this.keepY.getValue() <= 5;
            boolean towerActive = this.tower.getValue() == 3;
            return keepYActive && this.stage > 0 || towerActive && mc.gameSettings.keyBindJump.isKeyDown();
        } else {
            return false;
        }
    }

    private void releaseBlinkedPackets() {
        this.isActualBlinking = false;
        if (!this.blinkedPackets.isEmpty()) {
            for (Packet<?> p : this.blinkedPackets) {
                PacketUtil.sendPacketNoEvent(p);
            }
            this.blinkedPackets.clear();
        }
    }

    // ==========================================
    // 🌟 TELLY3 GrimAC 绕过核心：Timer 反射操控
    // ==========================================
    private Timer getMcTimer() {
        return ((IAccessorMinecraft) mc).getTimer();
    }

    private void setTimerSpeed(float speed) {
        Timer t = getMcTimer();
        if (t != null) {
            t.timerSpeed = speed;
        }
    }

    // ==========================================
    // 🌟 TELLY3 GrimAC 绕过核心：Tick 律动微爆破
    // ==========================================
    private void handleTickPlay() {
        if (this.tickPlay.getValue() == 0) return; // NONE

        int tick = mc.thePlayer.ticksExisted;
        // 🌟 7 Tick 周期：微加速 -> 微减速 -> 5 Tick 正常，均速 = 1.0
        int phase = tick % 7;
        switch (phase) {
            case 0:
                // 速度窗口：搭块用
                setTimerSpeed(1.07F);
                this.tickPlayPhase = 1;
                break;
            case 1:
                // 补偿减速：抵消可疑均速
                setTimerSpeed(0.93F);
                this.tickPlayPhase = 2;
                break;
            default:
                setTimerSpeed(1.0F);
                this.tickPlayPhase = 0;
                break;
        }
        // 平均: (1.07 + 0.93 + 5×1.0) / 7 = 1.0 → 不触发 Timer 检测
    }

    // ==========================================
    // 🌟 TELLY3 GrimAC 绕过核心：空中 Tick Blink
    // ==========================================
    private void handleAirBlink(boolean isPlacing) {
        if (!this.airBlink.getValue()) {
            this.airBlinkActive = false;
            return;
        }

        if (mc.thePlayer.onGround) {
            // 落地立刻释放所有缓存包
            if (this.airBlinkActive) {
                this.releaseBlinkedPackets();
                this.airBlinkActive = false;
            }
            this.airBlinkTick = 0;
            return;
        }

        // 空中 Tick 级 Blink：每 5 Tick 缓冲 2 Tick 的 C03 包
        this.airBlinkTick++;
        int airCycle = this.airBlinkTick % 5;

        if (airCycle < 2 && !isPlacing) {
            // Blink 窗口：缓存包不发送
            this.isActualBlinking = true;
            this.airBlinkActive = true;
        } else {
            // 发送窗口：释放缓存
            if (this.airBlinkActive) {
                this.releaseBlinkedPackets();
                this.airBlinkActive = false;
            }
        }
    }

    // ==========================================
    // 🌟 TELLY3 GrimAC 绕过核心：电影级渐进式旋转
    // ==========================================
    private float[] getCinematicRotation(float targetYaw, float targetPitch, boolean isDiagonal) {
        // Step 1: 基于 ticksExisted 的不可预测种子 (比 System.currentTimeMillis 更难被 ML 学习)
        int seed = mc.thePlayer.ticksExisted + mc.thePlayer.getEntityId() * 7;

        // Step 2: 人类手部肌肉群移动速度 (每 Tick 最大旋转角度)
        float maxYawStep = RandomUtil.nextFloat(8.0F, 15.0F);    // 人类极限 ~15°/tick
        float maxPitchStep = RandomUtil.nextFloat(5.0F, 10.0F);  // 俯仰更慢

        // Step 3: 斜搭时增大步进 (因为需要更快回头)
        if (isDiagonal) {
            maxYawStep *= 1.2F;
            maxPitchStep *= 1.1F;
        }

        // Step 4: 渐进式追迹
        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - this.cinematicYaw);
        float pitchDiff = targetPitch - this.cinematicPitch;

        float newYaw, newPitch;
        if (Math.abs(yawDiff) > maxYawStep) {
            newYaw = this.cinematicYaw + Math.copySign(maxYawStep, yawDiff);
        } else {
            newYaw = targetYaw;
        }

        if (Math.abs(pitchDiff) > maxPitchStep) {
            newPitch = this.cinematicPitch + Math.copySign(maxPitchStep, pitchDiff);
        } else {
            newPitch = targetPitch;
        }

        // Step 5: 生理性微颤 (模拟人手肌肉纤维震颤，幅度远小于正弦波方案)
        float tremorYaw = (float) (Math.sin(seed * 0.617 + mc.thePlayer.posX * 3.7) * 0.12
                + Math.cos(seed * 0.383 + mc.thePlayer.posZ * 2.9) * 0.09);
        float tremorPitch = (float) (Math.cos(seed * 0.523 + mc.thePlayer.posY * 4.1) * 0.10
                + Math.sin(seed * 0.419 + mc.thePlayer.posX * 3.3) * 0.07);

        newYaw += tremorYaw;
        newPitch += tremorPitch;

        // Step 6: GCD 像素对齐 (骗过 GrimAC 的 Invalid Rotation 检测)
        float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = f * f * f * 8.0F * 0.15F;

        newYaw -= newYaw % gcd;
        newPitch -= newPitch % gcd;
        newPitch = MathHelper.clamp_float(newPitch, -90.0F, 90.0F);

        // Step 7: 更新电影级状态
        this.cinematicYaw = newYaw;
        this.cinematicPitch = newPitch;

        return new float[]{newYaw, newPitch};
    }

    // ==========================================
    // 🌟 商业级 ML (Machine Learning) 视角拟合系统
    // ==========================================
    private float[] getMLRotationFix(float targetYaw, float targetPitch, boolean isDiag) {
        long t = System.currentTimeMillis();
        int entityId = mc.thePlayer.getEntityId();

        // 1. 三重正弦波干涉拟合 (完美模拟人类手部肌肉细微震颤，骗过机器学习波形检测)
        float noiseYaw = (float) (Math.sin(t * 0.0034 + entityId) * 0.75 + Math.cos(t * 0.0017 + entityId) * 0.95 + Math.sin(t * 0.008) * 0.3);
        float noisePitch = (float) (Math.cos(t * 0.0041 + entityId) * 0.65 + Math.sin(t * 0.0022 + entityId) * 0.85 + Math.sin(t * 0.009) * 0.25);

        // 2. 动态高度追迹 Pitch (根据下落的实时高度计算边缘距离，而不是死板的常数)
        double fallDist = this.startY - mc.thePlayer.posY;
        float basePitch = isDiag ? 76.5F : 80.0F; // 斜搭距离远，准星需微抬
        basePitch += (float) (fallDist * 3.5); // 下落越深，视角压得越死
        basePitch = MathHelper.clamp_float(basePitch, 73.0F, 88.0F);

        float finalYaw = targetYaw + noiseYaw;
        float finalPitch = basePitch + noisePitch;

        // 3. GCD 绝对鼠标公约数对齐 (针对 Grim/NCP 的 Invalid Pitch 封禁)
        float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = f * f * f * 8.0F * 0.15F;

        finalYaw -= finalYaw % gcd;
        finalPitch -= finalPitch % gcd;
        finalPitch = MathHelper.clamp_float(finalPitch, -90.0F, 90.0F);

        return new float[]{finalYaw, finalPitch};
    }

    // ==========================================
    // TELLY4 (Hypixel)
    // A C08 sent from PRE reaches the server before this tick's C03, so the server checks it
    // against the rotation it already has: event.getYaw()/getPitch() (last reported). So:
    // place only when that rotation really hits a useful face, and aim this tick for the
    // next tick's position. No timer, no blink, one block per tick, mouse-step rotations.
    // ==========================================
    private void handleTelly4(UpdateEvent event) {
        float baseYaw = event.getYaw();
        float basePitch = event.getPitch();
        double reach = mc.playerController.getBlockReachDistance();

        // 1. Place with the rotation the server already knows.
        if (this.rotationTick <= 0) {
            MovingObjectPosition mop = RotationUtil.rayTrace(baseYaw, basePitch, reach, 1.0F);
            if (this.isUsefulTellyHit(mop)) {
                this.place(mop.getBlockPos(), mop.sideHit, mop.hitVec);
            }
        }

        // 2. Where to look for the next tick.
        BlockData urgent = this.getBlockData();
        BlockData ahead = this.tellyPredict.getValue()
                ? this.getBlockData(mc.thePlayer.posX + mc.thePlayer.motionX * 2.0, mc.thePlayer.posZ + mc.thePlayer.motionZ * 2.0)
                : null;
        BlockData target = urgent != null ? urgent : ahead;

        boolean airPhase = !mc.thePlayer.onGround && this.offGroundTicks >= this.tellyTicks.getValue();
        boolean placingPhase = this.stage == 0 || airPhase;
        boolean aim = placingPhase && target != null;
        if (aim && this.stage > 0 && this.isLandingSoon() && urgent == null) {
            // About to land: only keep aiming if the block we land on is still missing.
            BlockPos land = new BlockPos(
                    MathHelper.floor_double(mc.thePlayer.posX + mc.thePlayer.motionX * 2.0),
                    this.startY - 1,
                    MathHelper.floor_double(mc.thePlayer.posZ + mc.thePlayer.motionZ * 2.0));
            aim = BlockUtil.isReplaceable(land);
        }

        if (aim) {
            Vec3 nextEye = new Vec3(
                    mc.thePlayer.posX + mc.thePlayer.motionX,
                    mc.thePlayer.posY + mc.thePlayer.motionY + (double) mc.thePlayer.getEyeHeight(),
                    mc.thePlayer.posZ + mc.thePlayer.motionZ);
            float[] rot = this.findFaceRotation(target, nextEye, baseYaw, basePitch, reach);
            if (rot == null) {
                rot = this.findFaceRotation(target, mc.thePlayer.getPositionEyes(1.0F), baseYaw, basePitch, reach);
            }
            if (rot != null) {
                float[] step = this.stepTellyRotation(baseYaw, basePitch, rot[0], rot[1]);
                this.sendTellyRotation(event, step[0], step[1]);
                return;
            }
        }

        if (this.stage == 0 && !airPhase && RotationState.isActived()) {
            // Plain scaffolding (keep-y off): hold the last rotation instead of jittering forward.
            this.sendTellyRotation(event, baseYaw, basePitch);
            return;
        }

        // 3. Turn back to where the player really looks, so the jump on landing is a normal sprint jump.
        float realYaw = mc.thePlayer.rotationYaw;
        float realPitch = mc.thePlayer.rotationPitch;
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(realYaw - baseYaw));
        float pitchDiff = Math.abs(realPitch - basePitch);
        float speed = (float) this.tellyRotSpeed.getValue();
        if (yawDiff <= speed && pitchDiff <= speed) {
            this.tellyFacingBack = false; // release: server rotation becomes the real one
        } else {
            float[] step = this.stepTellyRotation(baseYaw, basePitch, realYaw, realPitch);
            this.sendTellyRotation(event, step[0], step[1]);
        }
    }

    private boolean isUsefulTellyHit(MovingObjectPosition mop) {
        if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK || mop.sideHit == EnumFacing.DOWN) {
            return false;
        }
        if (BlockUtil.isInteractable(mop.getBlockPos())) {
            return false;
        }
        BlockPos placed = mop.getBlockPos().offset(mop.sideHit);
        if (!BlockUtil.isReplaceable(placed) || placed.getY() != this.getTargetY()) {
            return false;
        }
        double dx = placed.getX() + 0.5 - mc.thePlayer.posX;
        double dz = placed.getZ() + 0.5 - mc.thePlayer.posZ;
        return dx * dx + dz * dz <= 4.0;
    }

    private boolean isLandingSoon() {
        if (mc.thePlayer.onGround || mc.thePlayer.motionY >= 0.0) {
            return false;
        }
        double y1 = mc.thePlayer.posY + mc.thePlayer.motionY;
        double y2 = y1 + (mc.thePlayer.motionY - 0.08) * 0.98;
        return y2 <= (double) this.startY + 0.01;
    }

    /** A rotation from {@code eye} that hits the target face away from its edges, closest to the base rotation. */
    private float[] findFaceRotation(BlockData data, Vec3 eye, float baseYaw, float basePitch, double reach) {
        BlockPos bp = data.blockPos();
        EnumFacing face = data.facing();
        float[] best = null;
        float bestDiff = Float.MAX_VALUE;
        for (double a : TELLY_OFFSETS) {
            for (double b : TELLY_OFFSETS) {
                double px, py, pz;
                switch (face.getAxis()) {
                    case X:
                        px = face.getFrontOffsetX() > 0 ? 1.0 : 0.0; py = a; pz = b;
                        break;
                    case Y:
                        px = a; py = face.getFrontOffsetY() > 0 ? 1.0 : 0.0; pz = b;
                        break;
                    default:
                        px = a; py = b; pz = face.getFrontOffsetZ() > 0 ? 1.0 : 0.0;
                        break;
                }
                double dx = bp.getX() + px - eye.xCoord;
                double dy = bp.getY() + py - eye.yCoord;
                double dz = bp.getZ() + pz - eye.zCoord;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float yaw = RotationUtil.wrapAngleDiff((float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F, baseYaw);
                float pitch = (float) (-Math.atan2(dy, horizontal) * 180.0 / Math.PI);
                MovingObjectPosition mop = this.rayTraceFrom(eye, yaw, pitch, reach);
                if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK
                        || !mop.getBlockPos().equals(bp) || mop.sideHit != face) {
                    continue;
                }
                float diff = Math.abs(MathHelper.wrapAngleTo180_float(yaw - baseYaw)) + Math.abs(pitch - basePitch);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = new float[]{yaw, pitch};
                }
            }
        }
        return best;
    }

    private MovingObjectPosition rayTraceFrom(Vec3 eye, float yaw, float pitch, double reach) {
        Vec3 look = ((IAccessorEntity) mc.thePlayer).callGetVectorForRotation(pitch, yaw);
        return mc.theWorld.rayTraceBlocks(eye, eye.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach));
    }

    /** Speed-limited step towards the target, snapped to whole mouse steps of the current sensitivity. */
    private float[] stepTellyRotation(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        float speed = (float) this.tellyRotSpeed.getValue() * RandomUtil.nextFloat(0.85F, 1.15F);
        float dYaw = RotationUtil.clampAngle(MathHelper.wrapAngleTo180_float(toYaw - fromYaw), speed);
        float dPitch = RotationUtil.clampAngle(toPitch - fromPitch, speed * 0.7F);
        float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = f * f * f * 8.0F * 0.15F;
        dYaw = Math.round(dYaw / gcd) * gcd;
        dPitch = Math.round(dPitch / gcd) * gcd;
        float pitch = fromPitch + dPitch;
        if (pitch > 90.0F || pitch < -90.0F) {
            pitch = fromPitch; // never send an out-of-range pitch
        }
        return new float[]{fromYaw + dYaw, pitch};
    }

    private void sendTellyRotation(UpdateEvent event, float yaw, float pitch) {
        event.setRotation(yaw, pitch, 3);
        if (this.moveFix.getValue() == 1) {
            event.setPervRotation(yaw, 3);
        }
        this.tellyFacingBack = Math.abs(MathHelper.wrapAngleTo180_float(yaw - this.getCurrentYaw())) > 60.0F;
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.rotationTick > 0) {
                this.rotationTick--;
            }
            this.offGroundTicks = mc.thePlayer.onGround ? 0 : this.offGroundTicks + 1;
            if (this.targetPreview.getValue() && ItemUtil.isHoldingBlock()) {
                BlockData next = this.getBlockData();
                this.visuals.setPreview(next == null ? null : next.blockPos().offset(next.facing()));
            } else {
                this.visuals.setPreview(null);
            }

            if (mc.thePlayer.onGround) {
                this.releaseBlinkedPackets();
                if (this.stage > 0) {
                    this.stage--;
                }
                if (this.stage < 0) {
                    this.stage++;
                }
                if (this.stage == 0
                        && this.keepY.getValue() != 0
                        && (!(Boolean) this.keepYonPress.getValue() || PlayerUtil.isUsingItem())
                        && (!this.disableWhileJumpActive.getValue() || !mc.thePlayer.isPotionActive(Potion.jump))
                        && !mc.gameSettings.keyBindJump.isKeyDown()) {
                    this.stage = 1;
                }
                this.startY = this.shouldKeepY ? this.startY : MathHelper.floor_double(mc.thePlayer.posY);
                this.shouldKeepY = false;
                this.towering = false;
            }

            boolean isTelly2Mode = this.keepY.getValue() == 4;
            boolean isTelly3Mode = this.keepY.getValue() == 5;
            float currentMoveYaw = this.getCurrentYaw();
            boolean isDiag = this.isDiagonal(currentMoveYaw);

            if (isTelly2Mode && !mc.thePlayer.onGround) {
                long timeFactor = System.currentTimeMillis() % 380;
                this.isActualBlinking = timeFactor < 110;

                BlockPos forwardAirCheck = new BlockPos(mc.thePlayer.posX + mc.thePlayer.motionX * 1.5, mc.thePlayer.posY - 1.0, mc.thePlayer.posZ + mc.thePlayer.motionZ * 1.5);
                if (mc.theWorld.isAirBlock(forwardAirCheck) && this.getBlockData() != null) {
                    this.releaseBlinkedPackets();
                }
            }

            if (this.canPlace()) {
                ItemStack stack = mc.thePlayer.getHeldItem();
                int count = ItemUtil.isBlock(stack) ? stack.stackSize : 0;
                this.blockCount = Math.min(this.blockCount, count);
                if (this.blockCount <= 0) {
                    int slot = mc.thePlayer.inventory.currentItem;
                    if (this.blockCount == 0) {
                        slot--;
                    }
                    for (int i = slot; i > slot - 9; i--) {
                        int hotbarSlot = (i % 9 + 9) % 9;
                        ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(hotbarSlot);
                        if (ItemUtil.isBlock(candidate)) {
                            mc.thePlayer.inventory.currentItem = hotbarSlot;
                            this.blockCount = candidate.stackSize;
                            break;
                        }
                    }
                }

                if (this.keepY.getValue() == 6) {
                    this.handleTelly4(event);
                    return;
                }

                float yawDiffTo180 = RotationUtil.wrapAngleDiff(currentMoveYaw - 180.0F, event.getYaw());
                float diagonalYaw = isDiag
                        ? yawDiffTo180
                        : RotationUtil.wrapAngleDiff(currentMoveYaw - 135.0F * ((currentMoveYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F), event.getYaw());

                if (!this.canRotate && !isTelly3Mode) {
                    switch (this.rotationMode.getValue()) {
                        case 1:
                        case 3:
                            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                                this.pitch = RotationUtil.quantizeAngle(85.0F);
                            } else {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                            }
                            break;
                        case 2:
                            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                                this.yaw = RotationUtil.quantizeAngle(yawDiffTo180);
                                this.pitch = RotationUtil.quantizeAngle(85.0F);
                            } else {
                                this.yaw = RotationUtil.quantizeAngle(yawDiffTo180);
                            }
                            break;
                    }
                }

                BlockData blockData = this.getBlockData();
                Vec3 hitVec = null;
                if (blockData != null) {
                    double[] x = placeOffsets;
                    double[] y = placeOffsets;
                    double[] z = placeOffsets;
                    switch (blockData.facing()) {
                        case NORTH: z = new double[]{0.0}; break;
                        case EAST:  x = new double[]{1.0}; break;
                        case SOUTH: z = new double[]{1.0}; break;
                        case WEST:  x = new double[]{0.0}; break;
                        case DOWN:  y = new double[]{0.0}; break;
                        case UP:    y = new double[]{1.0}; break;
                    }
                    float bestYaw = -180.0F;
                    float bestPitch = 0.0F;
                    float bestDiff = 0.0F;
                    for (double dx : x) {
                        for (double dy : y) {
                            for (double dz : z) {
                                double relX = (double) blockData.blockPos().getX() + dx - mc.thePlayer.posX;
                                double relY = (double) blockData.blockPos().getY() + dy - mc.thePlayer.posY - (double) mc.thePlayer.getEyeHeight();
                                double relZ = (double) blockData.blockPos().getZ() + dz - mc.thePlayer.posZ;
                                float baseYaw = RotationUtil.wrapAngleDiff(this.yaw, event.getYaw());
                                float[] rotations = RotationUtil.getRotationsTo(relX, relY, relZ, baseYaw, this.pitch);
                                MovingObjectPosition mop = RotationUtil.rayTrace(rotations[0], rotations[1], mc.playerController.getBlockReachDistance(), 1.0F);
                                if (mop != null
                                        && mop.typeOfHit == MovingObjectType.BLOCK
                                        && mop.getBlockPos().equals(blockData.blockPos())
                                        && mop.sideHit == blockData.facing()) {
                                    float totalDiff = Math.abs(rotations[0] - baseYaw) + Math.abs(rotations[1] - this.pitch);
                                    if (bestYaw == -180.0F && bestPitch == 0.0F || totalDiff < bestDiff) {
                                        bestYaw = rotations[0];
                                        bestPitch = rotations[1];
                                        bestDiff = totalDiff;
                                        hitVec = mop.hitVec;
                                    }
                                }
                            }
                        }
                    }
                    if (bestYaw != -180.0F || bestPitch != 0.0F) {
                        this.yaw = bestYaw;
                        this.pitch = bestPitch;
                        this.canRotate = true;
                    }
                }
                if (this.canRotate && MoveUtil.isForwardPressed() && Math.abs(MathHelper.wrapAngleTo180_float(yawDiffTo180 - this.yaw)) < 90.0F && !isTelly3Mode) {
                    switch (this.rotationMode.getValue()) {
                        case 2:
                            this.yaw = RotationUtil.quantizeAngle(yawDiffTo180);
                            break;
                        case 3:
                            this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                    }
                }

                // 🌟 Telly3 极限速度阈值 (大幅扩大窗口期，一跃过顶立刻回头猛铺)
                float telly3Motion = isDiag ? 0.40F : 0.28F;
                double telly3DiffY = isDiag ? 0.02 : 0.15;

                if (this.rotationMode.getValue() != 0) {
                    float targetYaw = this.yaw;
                    float targetPitch = this.pitch;

                    if (this.towering && (mc.thePlayer.motionY > 0.0 || mc.thePlayer.posY > (double) (this.startY + 1))) {
                        float yawDiff = MathHelper.wrapAngleTo180_float(this.yaw - event.getYaw());
                        float tolerance = this.rotationTick >= 2 ? RandomUtil.nextFloat(90.0F, 95.0F) : RandomUtil.nextFloat(30.0F, 35.0F);
                        if (Math.abs(yawDiff) > tolerance) {
                            float clampedYaw = RotationUtil.clampAngle(yawDiff, tolerance);
                            targetYaw = RotationUtil.quantizeAngle(event.getYaw() + clampedYaw);
                            this.rotationTick = Math.max(this.rotationTick, 1);
                        }
                    }
                    if (this.isTowering()) {
                        float yawDelta = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - event.getYaw());
                        targetYaw = RotationUtil.quantizeAngle(event.getYaw() + yawDelta * RandomUtil.nextFloat(0.98F, 0.99F));
                        targetPitch = RotationUtil.quantizeAngle(RandomUtil.nextFloat(30.0F, 80.0F));
                        this.rotationTick = 3;
                        this.towering = true;
                    }

                    if (isTelly2Mode) {
                        float currentStepYaw = RandomUtil.nextFloat(14.0F, 22.0F);
                        float currentStepPitch = RandomUtil.nextFloat(10.0F, 16.0F);
                        float wrappedTargetYaw = MathHelper.wrapAngleTo180_float(targetYaw);
                        float yawDifference = MathHelper.wrapAngleTo180_float(wrappedTargetYaw - this.currentSmoothYaw);

                        if (Math.abs(yawDifference) > currentStepYaw) {
                            this.currentSmoothYaw += Math.copySign(currentStepYaw, yawDifference);
                        } else {
                            this.currentSmoothYaw = wrappedTargetYaw;
                        }

                        float pitchDifference = targetPitch - this.currentSmoothPitch;
                        if (Math.abs(pitchDifference) > currentStepPitch) {
                            this.currentSmoothPitch += Math.copySign(currentStepPitch, pitchDifference);
                        } else {
                            this.currentSmoothPitch = targetPitch;
                        }

                        this.currentSmoothYaw += ThreadLocalRandom.current().nextDouble(-0.08, 0.08);
                        this.currentSmoothPitch += ThreadLocalRandom.current().nextDouble(-0.04, 0.04);
                        this.currentSmoothPitch = MathHelper.clamp_float(this.currentSmoothPitch, -90.0F, 90.0F);

                        float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
                        float f1 = f * f * f * 8.0F;
                        float f2 = f1 * 0.15F;

                        float yawPixels = Math.round(this.currentSmoothYaw / f2);
                        float pitchPixels = Math.round(this.currentSmoothPitch / f2);

                        targetYaw = yawPixels * f2;
                        targetPitch = pitchPixels * f2;

                    } else if (isTelly3Mode) {
                        // 🌟 Telly3 GrimAC 绕过：电影级渐进旋转 + Tick 玩法 + 空中 Blink 🌟
                        double diffY = mc.thePlayer.posY - this.startY;

                        // GrimAC 旋转模式选择
                        if (this.grimRotations.getValue() == 1) {
                            // CINEMATIC：渐进式追迹，骗过所有 Rotation 检测
                            if (!mc.thePlayer.onGround && mc.thePlayer.motionY < telly3Motion && diffY > telly3DiffY) {
                                // 空中加速期：渐进回头看下搭块
                                float baseYaw = currentMoveYaw - 180.0F;
                                double fallDist = this.startY - mc.thePlayer.posY;
                                float wantedPitch = 78.0F + (float)(fallDist * 2.5);
                                wantedPitch = MathHelper.clamp_float(wantedPitch, 75.0F, 87.0F);
                                if (isDiag) wantedPitch -= 2.0F;
                                float[] cinematic = getCinematicRotation(baseYaw, wantedPitch, isDiag);
                                targetYaw = cinematic[0];
                                targetPitch = cinematic[1];
                            } else {
                                // 未达阈值：渐进回正到当前视角
                                float[] cinematic = getCinematicRotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false);
                                targetYaw = cinematic[0];
                                targetPitch = cinematic[1];
                            }
                        } else {
                            // NONE：保持原有阈值式旋转 (兼容)
                            if (!mc.thePlayer.onGround && mc.thePlayer.motionY < telly3Motion && diffY > telly3DiffY) {
                                float baseYaw = currentMoveYaw - 180.0F;
                                float[] mlFix = getMLRotationFix(baseYaw, 0.0F, isDiag);
                                targetYaw = mlFix[0];
                                targetPitch = mlFix[1];
                            } else {
                                float[] mlFix = getMLRotationFix(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false);
                                targetYaw = mlFix[0];
                                targetPitch = mlFix[1];
                            }
                        }
                        this.currentSmoothYaw = targetYaw;
                        this.currentSmoothPitch = targetPitch;
                    } else {
                        this.currentSmoothYaw = targetYaw;
                        this.currentSmoothPitch = targetPitch;
                    }

                    event.setRotation(targetYaw, targetPitch, 3);
                    if (this.moveFix.getValue() == 1) {
                        event.setPervRotation(targetYaw, 3);
                    }
                }

                // 🌟 Telly3 GrimAC 绕过：Tick 玩法 + 空中 Blink
                if (isTelly3Mode) {
                    this.handleTickPlay();
                    this.handleAirBlink(!mc.thePlayer.onGround && mc.thePlayer.motionY < telly3Motion && (mc.thePlayer.posY - this.startY) > telly3DiffY);
                }

                // 🌟 Telly3 防露馅阀门：非搭块状态绝不乱发包！
                boolean shouldPlaceTelly3 = !isTelly3Mode || (!mc.thePlayer.onGround && mc.thePlayer.motionY < telly3Motion && (mc.thePlayer.posY - this.startY) > telly3DiffY);

                if (!this.isActualBlinking && shouldPlaceTelly3) {
                    if (blockData != null && hitVec != null && this.rotationTick <= 0) {
                        this.place(blockData.blockPos(), blockData.facing(), hitVec);
                        if (this.multiplace.getValue()) {
                            // 🌟 GrimAC 优化：同 Tick 限 3/2 块，避免速度检测；依赖 Tick 玩法补偿速度
                            int loops = isTelly3Mode ? (isDiag ? 3 : 2) : 3;
                            for (int i = 0; i < loops; i++) {
                                blockData = this.getBlockData();
                                if (blockData == null) {
                                    break;
                                }
                                MovingObjectPosition mop = RotationUtil.rayTrace(this.yaw, this.pitch, mc.playerController.getBlockReachDistance(), 1.0F);
                                if (mop != null
                                        && mop.typeOfHit == MovingObjectType.BLOCK
                                        && mop.getBlockPos().equals(blockData.blockPos())
                                        && mop.sideHit == blockData.facing()) {
                                    this.place(blockData.blockPos(), blockData.facing(), mop.hitVec);
                                } else {
                                    hitVec = BlockUtil.getClickVec(blockData.blockPos(), blockData.facing());
                                    double dx = hitVec.xCoord - mc.thePlayer.posX;
                                    double dy = hitVec.yCoord - mc.thePlayer.posY - (double) mc.thePlayer.getEyeHeight();
                                    double dz = hitVec.zCoord - mc.thePlayer.posZ;
                                    float[] rotations = RotationUtil.getRotationsTo(dx, dy, dz, event.getYaw(), event.getPitch());
                                    if (!(Math.abs(rotations[0] - this.yaw) < 120.0F) || !(Math.abs(rotations[1] - this.pitch) < 60.0F)) {
                                        break;
                                    }
                                    mop = RotationUtil.rayTrace(rotations[0], rotations[1], mc.playerController.getBlockReachDistance(), 1.0F);
                                    if (mop == null
                                            || mop.typeOfHit != MovingObjectType.BLOCK
                                            || !mop.getBlockPos().equals(blockData.blockPos())
                                            || mop.sideHit != blockData.facing()) {
                                        break;
                                    }
                                    this.place(blockData.blockPos(), blockData.facing(), mop.hitVec);
                                }
                            }
                        }
                    }
                    if (this.targetFacing != null) {
                        if (this.rotationTick <= 0) {
                            int playerBlockX = MathHelper.floor_double(mc.thePlayer.posX);
                            int playerBlockY = MathHelper.floor_double(mc.thePlayer.posY);
                            int playerBlockZ = MathHelper.floor_double(mc.thePlayer.posZ);
                            BlockPos belowPlayer = new BlockPos(playerBlockX, playerBlockY - 1, playerBlockZ);
                            hitVec = BlockUtil.getHitVec(belowPlayer, this.targetFacing, this.yaw, this.pitch);
                            this.place(belowPlayer, this.targetFacing, hitVec);
                        }
                        this.targetFacing = null;
                    } else if ((this.keepY.getValue() >= 2 && this.keepY.getValue() <= 5) && this.stage > 0 && !mc.thePlayer.onGround) {
                        int nextBlockY = MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.motionY);
                        if (nextBlockY <= this.startY && mc.thePlayer.posY > (double) (this.startY + 1)) {
                            this.shouldKeepY = true;
                            blockData = this.getBlockData();
                            if (blockData != null && this.rotationTick <= 0) {
                                hitVec = BlockUtil.getHitVec(blockData.blockPos(), blockData.facing(), this.yaw, this.pitch);
                                this.place(blockData.blockPos(), blockData.facing(), hitVec);
                            }
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (this.isEnabled()) {
            if (!mc.thePlayer.isCollidedHorizontally
                    && mc.thePlayer.hurtTime <= 5
                    && !mc.thePlayer.isPotionActive(Potion.jump)
                    && mc.gameSettings.keyBindJump.isKeyDown()
                    && ItemUtil.isHoldingBlock()) {
                int yState = (int) (mc.thePlayer.posY % 1.0 * 100.0);
                switch (this.tower.getValue()) {
                    case 1:
                        switch (this.towerTick) {
                            case 0:
                                if (mc.thePlayer.onGround) {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY = -0.0784000015258789;
                                }
                                return;
                            case 1:
                                if (yState == 0 && PlayerUtil.isAirBelow()) {
                                    this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                    this.towerTick = 2;
                                    mc.thePlayer.motionY = 0.42F;
                                    if (MoveUtil.isForwardPressed()) {
                                        MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
                                    } else {
                                        MoveUtil.setSpeed(0.0);
                                        event.setForward(0.0F);
                                        event.setStrafe(0.0F);
                                    }
                                    return;
                                } else {
                                    this.towerTick = 0;
                                    return;
                                }
                            case 2:
                                this.towerTick = 3;
                                mc.thePlayer.motionY = 0.75 - mc.thePlayer.posY % 1.0;
                                return;
                            case 3:
                                this.towerTick = 1;
                                mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                                return;
                            default:
                                this.towerTick = 0;
                                return;
                        }
                    case 2:
                        switch (this.towerTick) {
                            case 0:
                                if (mc.thePlayer.onGround) {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY = -0.0784000015258789;
                                }
                                return;
                            case 1:
                                if (yState == 0 && PlayerUtil.isAirBelow()) {
                                    this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                    if (!MoveUtil.isForwardPressed()) {
                                        this.towerDelay = 2;
                                        MoveUtil.setSpeed(0.0);
                                        event.setForward(0.0F);
                                        event.setStrafe(0.0F);
                                        EnumFacing facing = this.yawToFacing(MathHelper.wrapAngleTo180_float(this.yaw - 180.0F));
                                        double distance = this.distanceToEdge(facing);
                                        if (distance > 0.1) {
                                            if (mc.thePlayer.onGround) {
                                                Vec3i directionVec = facing.getDirectionVec();
                                                double offset = Math.min(this.getRandomOffset(), distance - 0.05);
                                                double jitter = RandomUtil.nextDouble(0.02, 0.03);
                                                AxisAlignedBB nextBox = mc.thePlayer
                                                        .getEntityBoundingBox()
                                                        .offset((double) directionVec.getX() * (offset - jitter), 0.0, (double) directionVec.getZ() * (offset - jitter));
                                                if (mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, nextBox).isEmpty()) {
                                                    mc.thePlayer.motionY = -0.0784000015258789;
                                                    mc.thePlayer
                                                            .setPosition(nextBox.minX + (nextBox.maxX - nextBox.minX) / 2.0, nextBox.minY, nextBox.minZ + (nextBox.maxZ - nextBox.minZ) / 2.0);
                                                }
                                                return;
                                            }
                                        } else {
                                            this.towerTick = 2;
                                            this.targetFacing = facing;
                                            mc.thePlayer.motionY = 0.42F;
                                        }
                                        return;
                                    } else {
                                        this.towerTick = 2;
                                        this.towerDelay++;
                                        mc.thePlayer.motionY = 0.42F;
                                        MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
                                        return;
                                    }
                                } else {
                                    this.towerTick = 0;
                                    this.towerDelay = 0;
                                    return;
                                }
                            case 2:
                                this.towerTick = 3;
                                mc.thePlayer.motionY = mc.thePlayer.motionY - RandomUtil.nextDouble(0.00101, 0.00109);
                                return;
                            case 3:
                                if (this.towerDelay >= 4) {
                                    this.towerTick = 4;
                                    this.towerDelay = 0;
                                } else {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                                }
                                return;
                            case 4:
                                this.towerTick = 5;
                                return;
                            case 5:
                                if (!PlayerUtil.isAirBelow()) {
                                    this.towerTick = 0;
                                } else {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY -= 0.08;
                                    mc.thePlayer.motionY *= 0.98F;
                                    mc.thePlayer.motionY -= 0.08;
                                    mc.thePlayer.motionY *= 0.98F;
                                }
                                return;
                            default:
                                this.towerTick = 0;
                                this.towerDelay = 0;
                                return;
                        }
                    case 4:
                        // LiquidBounce Nextgen ScaffoldTowerHypixel: stationary tower only
                        if (Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ) > 0.01) {
                            return;
                        }
                        if (mc.thePlayer.onGround) {
                            mc.thePlayer.motionY = 0.42;
                        }
                        if (mc.thePlayer.motionY <= 0.0 && mc.thePlayer.motionY >= -0.09) {
                            mc.thePlayer.motionY = -0.38;
                        }
                        return;
                    default:
                        this.towerTick = 0;
                        this.towerDelay = 0;
                }
            } else {
                this.towerTick = 0;
                this.towerDelay = 0;
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.moveFix.getValue() == 1
                    && RotationState.isActived()
                    && RotationState.getPriority() == 3.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
            if (mc.thePlayer.onGround && this.stage > 0 && MoveUtil.isForwardPressed()) {
                if (this.keepY.getValue() == 5 || this.keepY.getValue() == 6 && !this.tellyFacingBack) {
                    mc.thePlayer.setSprinting(true);
                }
                mc.thePlayer.movementInput.jump = true;
            }
        }
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.isEnabled()) {
            float speed = this.getSpeed();
            if (speed != 1.0F) {
                if (mc.thePlayer.movementInput.moveForward != 0.0F && mc.thePlayer.movementInput.moveStrafe != 0.0F) {
                    mc.thePlayer.movementInput.moveForward = mc.thePlayer.movementInput.moveForward * (1.0F / (float) Math.sqrt(2.0));
                    mc.thePlayer.movementInput.moveStrafe = mc.thePlayer.movementInput.moveStrafe * (1.0F / (float) Math.sqrt(2.0));
                }
                mc.thePlayer.movementInput.moveForward *= speed;
                mc.thePlayer.movementInput.moveStrafe *= speed;
            }
            if (this.shouldStopSprint()) {
                mc.thePlayer.setSprinting(false);
            }
        }
    }

    @EventTarget
    public void onSafeWalk(SafeWalkEvent event) {
        if (this.isEnabled() && this.safeWalk.getValue()) {
            if (mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0 && PlayerUtil.canMove(mc.thePlayer.motionX, mc.thePlayer.motionZ, -1.0)) {
                event.setSafeWalk(true);
            }
        }
    }

    @EventTarget
    public void onSafeWalkUpdate(SafeWalkEvent event) {
    }

    // Visuals (place trail / next-block preview / block counter) live in ScaffoldVisuals,
    // shared with LeaderScaffold.
    @EventTarget
    public void onRender3D(Render3DEvent event) {
        this.visuals.render3D(this.placeTrail.getValue(),
                this.isEnabled() && this.targetPreview.getValue(), this.placeGlow.getValue());
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (this.isEnabled() && this.blockCounter.getValue()) {
            this.visuals.renderCounter(this.isTowering());
        }
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) {
            this.lastSlot = event.setSlot(this.lastSlot);
            event.setCancelled(true);
        }
    }

    @Override
    public void onEnabled() {
        Module other = Myau.moduleManager.modules.get(LeaderScaffold.class);
        if (other != null && other.isEnabled()) {
            other.setEnabled(false);
        }
        if (mc.thePlayer != null) {
            this.lastSlot = mc.thePlayer.inventory.currentItem;
            this.currentSmoothYaw = mc.thePlayer.rotationYaw;
            this.currentSmoothPitch = mc.thePlayer.rotationPitch;
            this.cinematicYaw = mc.thePlayer.rotationYaw;
            this.cinematicPitch = mc.thePlayer.rotationPitch;
        } else {
            this.lastSlot = -1;
            this.currentSmoothYaw = -180.0F;
            this.currentSmoothPitch = 0.0F;
            this.cinematicYaw = -180.0F;
            this.cinematicPitch = 0.0F;
        }
        this.blockCount = -1;
        this.rotationTick = 3;
        this.yaw = -180.0F;
        this.pitch = 0.0F;
        this.canRotate = false;
        this.towerTick = 0;
        this.towerDelay = 0;
        this.towering = false;
        this.isActualBlinking = false;
        this.tickPlayPhase = 0;
        this.airBlinkTick = 0;
        this.airBlinkActive = false;
        this.offGroundTicks = 0;
        this.tellyFacingBack = false;
        this.blinkedPackets.clear();
        this.visuals.clearTrail();
    }

    @Override
    public void onDisabled() {
        this.setTimerSpeed(1.0F);
        this.releaseBlinkedPackets();
        this.tellyFacingBack = false;
        if (mc.thePlayer != null && this.lastSlot != -1) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        this.visuals.setPreview(null);
    }

    public static class BlockData {
        private final BlockPos blockPos;
        private final EnumFacing facing;

        public BlockData(BlockPos blockPos, EnumFacing enumFacing) {
            this.blockPos = blockPos;
            this.facing = enumFacing;
        }

        public BlockPos blockPos() {
            return this.blockPos;
        }

        public EnumFacing facing() {
            return this.facing;
        }
    }
}