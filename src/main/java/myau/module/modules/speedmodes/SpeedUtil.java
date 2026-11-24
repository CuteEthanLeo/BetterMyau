package myau.module.modules.speedmodes;

import myau.Myau;
import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorEntityPlayer;
import myau.module.modules.Scaffold;
import myau.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

/** Helpers shared by the Speed modes (the parts of xw's MovementUtils that MoveUtil does not have). */
public final class SpeedUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private SpeedUtil() {
    }

    /** Horizontal speed as a float, like xw's {@code MovementUtils.speed}. */
    public static float speed() {
        return (float) Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
    }

    public static boolean isInWeb() {
        return ((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    public static void setSpeedInAir(float value) {
        ((IAccessorEntityPlayer) mc.thePlayer).setSpeedInAir(value);
    }

    public static boolean isInLiquid() {
        return mc.thePlayer.isInWater() || mc.thePlayer.isInLava();
    }

    /** Speed potion level: 0 without the effect, amplifier + 1 otherwise. */
    public static int speedLevel() {
        PotionEffect effect = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    /** Raw Speed potion amplifier, 0 without the effect (xw: {@code effect?.amplifier ?: 0}). */
    public static int speedAmplifier() {
        PotionEffect effect = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed);
        return effect == null ? 0 : effect.getAmplifier();
    }

    public static boolean scaffoldEnabled() {
        return myau.module.modules.LeaderScaffold.isAnyScaffoldEnabled();
    }

    /** True when the player's bounding box moved by {@code offsetY} collides with something. */
    public static boolean collides(double offsetY) {
        return mc.theWorld != null && mc.thePlayer != null
                && !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, mc.thePlayer.getEntityBoundingBox().offset(0.0, offsetY, 0.0)).isEmpty();
    }

    public static boolean isOnGround(double height) {
        return collides(-height);
    }

    /** 0.2873 scaled by Speed (+20% per level) and Slowness (-15% per level). */
    public static double getAllowedHorizontalDistance() {
        double base = 0.2873;
        if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
            base *= 1.0 + 0.2 * (double) (mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1);
        }
        if (mc.thePlayer.isPotionActive(Potion.moveSlowdown)) {
            base *= 1.0 - 0.15 * (double) (mc.thePlayer.getActivePotionEffect(Potion.moveSlowdown).getAmplifier() + 1);
        }
        return base;
    }

    /** Adds {@code amount} of motion in the input direction. */
    public static void moveFlying(double amount) {
        double yaw = MoveUtil.getDirection();
        mc.thePlayer.motionX += -Math.sin(yaw) * amount;
        mc.thePlayer.motionZ += Math.cos(yaw) * amount;
    }

    /**
     * Turns {@code percent}% of the current motion into the input direction and keeps the speed.
     * NOTE: xw implemented this as "set the speed to percent% of the current speed", which makes
     * Tatako (1% every tick) stand still; this follows the partial strafe of the Rise original.
     * For 100% (the only other caller, WatchdogRise) both versions are identical.
     */
    public static void partialStrafePercent(double percent) {
        if (!MoveUtil.isMoving()) {
            return;
        }
        double strength = Math.min(1.0, Math.max(0.0, percent / 100.0));
        MoveUtil.strafe(speed(), strength);
    }

    /** Multiplies the motion by sqrt(2) while moving diagonally (as in xw). */
    public static void useDiagonalSpeed() {
        if (mc.thePlayer.movementInput.moveForward != 0.0F && mc.thePlayer.movementInput.moveStrafe != 0.0F) {
            mc.thePlayer.motionX *= 1.41421356;
            mc.thePlayer.motionZ *= 1.41421356;
        }
    }

    /** Multiplies the motion by 1/sqrt(2) while moving diagonally (as in xw). */
    public static void preventDiagonalSpeed() {
        if (mc.thePlayer.movementInput.moveForward != 0.0F && mc.thePlayer.movementInput.moveStrafe != 0.0F) {
            mc.thePlayer.motionX *= 0.70710678;
            mc.thePlayer.motionZ *= 0.70710678;
        }
    }

    /** motionY after {@code ticks} ticks of gravity. */
    public static double predictedMotion(double motionY, int ticks) {
        double result = motionY;
        for (int i = 0; i < ticks; i++) {
            result = (result - 0.08) * 0.98;
        }
        return result;
    }

    public static Block blockAt(double offsetX, double offsetY, double offsetZ) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return Blocks.air;
        }
        return mc.theWorld.getBlockState(new BlockPos(mc.thePlayer.posX + offsetX, mc.thePlayer.posY + offsetY, mc.thePlayer.posZ + offsetZ)).getBlock();
    }

    public static boolean hasBlockAtOffset(double offsetY) {
        return blockAt(0.0, offsetY, 0.0) != Blocks.air;
    }

    public static double angleDiff(double a, double b) {
        double diff = (a - b) % 360.0;
        if (diff > 180.0) {
            diff -= 360.0;
        }
        if (diff < -180.0) {
            diff += 360.0;
        }
        return Math.abs(diff);
    }

    /** Input direction in degrees, wrapped to -180..180. */
    public static double moveAngle() {
        return MathHelper.wrapAngleTo180_double(Math.toDegrees(MoveUtil.getDirection()));
    }

    /** Direction of the current motion in degrees, wrapped to -180..180. */
    public static double motionAngle() {
        return MathHelper.wrapAngleTo180_double(Math.toDegrees(Math.atan2(mc.thePlayer.motionZ, mc.thePlayer.motionX)) - 90.0);
    }
}
