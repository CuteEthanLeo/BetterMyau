package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * Tick-accurate flight simulation for fishing-rod hooks and thrown snowballs / eggs, plus an aim solver that
 * finds the rotation whose flight path meets a moving target. Physics constants match {@code Trajectories}.
 */
public final class ThrowSim {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Slightly smaller than vanilla's 0.3 entity expansion, so a predicted hit leaves some margin for error. */
    public static final double HIT_EXPAND = 0.25;
    /** Longest flight we ever simulate, in ticks. */
    public static final int MAX_TICKS = 20;

    private static final float PITCH_MIN = -45.0F;
    private static final float PITCH_MAX = 45.0F;
    private static final float PITCH_STEP = 0.5F;

    public enum Kind {
        ROD(1.5, 0.04, 0.92),
        THROWABLE(1.5, 0.03, 0.99);

        final double speed;
        final double gravity;
        final double drag;

        Kind(double speed, double gravity, double drag) {
            this.speed = speed;
            this.gravity = gravity;
            this.drag = drag;
        }
    }

    public static final class Solution {
        public final float yaw;
        public final float pitch;
        /** Projectile tick (1-based) on which it reaches the target. */
        public final int ticks;

        Solution(float yaw, float pitch, int ticks) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.ticks = ticks;
        }
    }

    private ThrowSim() {
    }

    /**
     * Simulates a projectile launched by the local player with the given rotation.
     *
     * @param boxes boxes[k] is where the target is (already expanded) while the projectile travels its (k+1)-th tick
     * @return the 1-based tick on which it hits the target, or -1 when it misses or hits a block first
     */
    public static int simulate(Kind kind, EntityPlayerSP player, float yaw, float pitch, AxisAlignedBB[] boxes, boolean checkBlocks) {
        float yawRad = yaw / 180.0F * (float) Math.PI;
        float pitchRad = pitch / 180.0F * (float) Math.PI;
        double x = player.posX - (double) MathHelper.cos(yawRad) * 0.16;
        double y = player.posY + (double) player.getEyeHeight() - 0.1;
        double z = player.posZ - (double) MathHelper.sin(yawRad) * 0.16;
        double mx = -MathHelper.sin(yawRad) * MathHelper.cos(pitchRad);
        double my = -MathHelper.sin(pitchRad);
        double mz = MathHelper.cos(yawRad) * MathHelper.cos(pitchRad);
        double length = Math.sqrt(mx * mx + my * my + mz * mz);
        mx = mx / length * kind.speed;
        my = my / length * kind.speed;
        mz = mz / length * kind.speed;
        int ticks = Math.min(boxes.length, MAX_TICKS);
        double startX = x;
        double startZ = z;
        for (int k = 0; k < ticks; k++) {
            AxisAlignedBB box = boxes[k];
            if (box != null) {
                // Already well past the target horizontally: it can only miss from here on.
                double bx = (box.minX + box.maxX) / 2.0 - startX;
                double bz = (box.minZ + box.maxZ) / 2.0 - startZ;
                double px = x - startX;
                double pz = z - startZ;
                double limit = Math.sqrt(bx * bx + bz * bz) + 2.0;
                if (px * px + pz * pz > limit * limit) {
                    return -1;
                }
            }
            double ex = x + mx;
            double ey = y + my;
            double ez = z + mz;
            boolean blocked = false;
            if (checkBlocks) {
                MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(new Vec3(x, y, z), new Vec3(ex, ey, ez), false, true, false);
                if (mop != null && mop.hitVec != null) {
                    ex = mop.hitVec.xCoord;
                    ey = mop.hitVec.yCoord;
                    ez = mop.hitVec.zCoord;
                    blocked = true;
                }
            }
            if (box != null && segmentHits(box, x, y, z, ex, ey, ez)) {
                return k + 1;
            }
            if (blocked) {
                return -1;
            }
            x += mx;
            y += my;
            z += mz;
            mx *= kind.drag;
            my *= kind.drag;
            mz *= kind.drag;
            my -= kind.gravity;
            if (y < 0.0) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Finds a rotation whose flight path meets the predicted target.
     *
     * @param centers centers[k] = {x, y, z} of the aim point while the projectile travels its (k+1)-th tick
     * @return the solution, or null when no rotation within the pitch range hits without a block in the way
     */
    public static Solution solve(Kind kind, EntityPlayerSP player, AxisAlignedBB[] boxes, double[][] centers) {
        int ticks = Math.min(boxes.length, MAX_TICKS);
        if (ticks <= 0) {
            return null;
        }
        // First guess: roughly one block per tick.
        double dx0 = centers[0][0] - player.posX;
        double dz0 = centers[0][2] - player.posZ;
        int t = MathHelper.clamp_int((int) Math.ceil(Math.sqrt(dx0 * dx0 + dz0 * dz0) / 1.2), 1, ticks);
        float yaw = 0.0F;
        float pitch = Float.NaN;
        int hitTick = -1;
        for (int iteration = 0; iteration < 3; iteration++) {
            double[] c = centers[t - 1];
            yaw = (float) (Math.atan2(c[2] - player.posZ, c[0] - player.posX) * 180.0 / Math.PI) - 90.0F;
            float bandStart = Float.NaN;
            float bestPitch = Float.NaN;
            int bestTick = Integer.MAX_VALUE;
            // Scan pitches; every contiguous run of hitting pitches is a band, aim at the middle of the fastest band.
            for (float p = PITCH_MIN; p <= PITCH_MAX + 1.0E-3F; p += PITCH_STEP) {
                int hit = simulate(kind, player, yaw, p, boxes, false);
                if (hit > 0) {
                    if (Float.isNaN(bandStart)) {
                        bandStart = p;
                    }
                    continue;
                }
                if (!Float.isNaN(bandStart)) {
                    float mid = (bandStart + p - PITCH_STEP) / 2.0F;
                    int midHit = simulate(kind, player, yaw, mid, boxes, false);
                    if (midHit > 0 && midHit < bestTick) {
                        bestTick = midHit;
                        bestPitch = mid;
                    }
                    bandStart = Float.NaN;
                }
            }
            if (!Float.isNaN(bandStart)) {
                float mid = (bandStart + PITCH_MAX) / 2.0F;
                int midHit = simulate(kind, player, yaw, mid, boxes, false);
                if (midHit > 0 && midHit < bestTick) {
                    bestTick = midHit;
                    bestPitch = mid;
                }
            }
            if (Float.isNaN(bestPitch)) {
                return null;
            }
            pitch = bestPitch;
            hitTick = bestTick;
            if (hitTick == t) {
                break;
            }
            t = hitTick;
        }
        if (Float.isNaN(pitch)) {
            return null;
        }
        int verified = simulate(kind, player, yaw, pitch, boxes, true);
        if (verified <= 0) {
            return null;
        }
        return new Solution(yaw, pitch, verified);
    }

    /** Segment / box intersection (slab test); also true when the segment starts inside the box. */
    private static boolean segmentHits(AxisAlignedBB box, double x0, double y0, double z0, double x1, double y1, double z1) {
        double[] range = {0.0, 1.0};
        return clipAxis(range, x0, x1 - x0, box.minX, box.maxX)
                && clipAxis(range, y0, y1 - y0, box.minY, box.maxY)
                && clipAxis(range, z0, z1 - z0, box.minZ, box.maxZ);
    }

    /** Narrows {@code range} = {tMin, tMax} to where the segment is inside [min, max] on one axis. */
    private static boolean clipAxis(double[] range, double origin, double delta, double min, double max) {
        if (Math.abs(delta) < 1.0E-9) {
            return origin >= min && origin <= max;
        }
        double t0 = (min - origin) / delta;
        double t1 = (max - origin) / delta;
        if (t0 > t1) {
            double swap = t0;
            t0 = t1;
            t1 = swap;
        }
        range[0] = Math.max(range[0], t0);
        range[1] = Math.min(range[1], t1);
        return range[0] <= range[1];
    }
}
