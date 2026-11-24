package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Simulates the local player falling (gravity + air drag) to find where and when it lands. */
public class FallingPlayer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double[][] OFFSETS = new double[][]{
            {0.0, 0.0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3},
            {0.3, 0.15}, {-0.3, 0.15}, {0.15, 0.3}, {0.15, -0.3}
    };

    private double x;
    private double y;
    private double z;
    private double motionX;
    private double motionY;
    private double motionZ;
    private final float yaw;
    private float strafe;
    private float forward;

    public FallingPlayer(double x, double y, double z, double motionX, double motionY, double motionZ, float yaw, float strafe, float forward) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.motionX = motionX;
        this.motionY = motionY;
        this.motionZ = motionZ;
        this.yaw = yaw;
        this.strafe = strafe;
        this.forward = forward;
    }

    public FallingPlayer(EntityPlayerSP player) {
        this(player, false);
    }

    /** @param predict start one tick ahead of the current position */
    public FallingPlayer(EntityPlayerSP player, boolean predict) {
        this(
                predict ? player.posX + player.motionX : player.posX,
                predict ? player.posY + player.motionY : player.posY,
                predict ? player.posZ + player.motionZ : player.posZ,
                player.motionX, player.motionY, player.motionZ,
                player.rotationYaw, player.moveStrafing, player.moveForward
        );
    }

    private void calculateForTick() {
        this.strafe *= 0.98f;
        this.forward *= 0.98f;
        float v = this.strafe * this.strafe + this.forward * this.forward;
        if (v >= 1.0E-4f) {
            v = mc.thePlayer.jumpMovementFactor / Math.max((float) Math.sqrt(v), 1.0f);
            this.strafe *= v;
            this.forward *= v;
            float sin = (float) Math.sin(Math.toRadians(this.yaw));
            float cos = (float) Math.cos(Math.toRadians(this.yaw));
            this.motionX += this.strafe * cos - this.forward * sin;
            this.motionZ += this.forward * cos + this.strafe * sin;
        }
        this.motionY -= 0.08;
        this.motionX *= 0.91;
        this.motionY *= 0.9800000190734863;
        this.motionY *= 0.91;
        this.motionZ *= 0.91;
        this.x += this.motionX;
        this.y += this.motionY;
        this.z += this.motionZ;
    }

    /** @return the block landed on and after how many ticks, or null when nothing is hit within {@code ticks} */
    public CollisionResult findCollision(int ticks) {
        for (int i = 0; i < ticks; i++) {
            Vec3 start = new Vec3(this.x, this.y, this.z);
            this.calculateForTick();
            Vec3 end = new Vec3(this.x, this.y, this.z);
            for (double[] offset : OFFSETS) {
                BlockPos pos = this.rayTrace(start.addVector(offset[0], 0.0, offset[1]), end);
                if (pos != null) {
                    return new CollisionResult(pos, i);
                }
            }
        }
        return null;
    }

    private BlockPos rayTrace(Vec3 start, Vec3 end) {
        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(start, end, true);
        if (result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && result.sideHit == EnumFacing.UP) {
            return result.getBlockPos();
        }
        return null;
    }

    public static class CollisionResult {
        private final BlockPos pos;
        private final int tick;

        public CollisionResult(BlockPos pos, int tick) {
            this.pos = pos;
            this.tick = tick;
        }

        public BlockPos getPos() {
            return this.pos;
        }

        public int getTick() {
            return this.tick;
        }
    }
}
