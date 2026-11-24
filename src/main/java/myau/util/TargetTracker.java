package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Remembers where nearby targets were over the last few ticks (from the raw server positions, not the smoothed
 * render positions) and extrapolates where they will be, for projectile aiming.
 */
public final class TargetTracker {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int HISTORY = 6;
    /** Players are only sent a position update every second tick, so velocities are averaged over this many ticks. */
    private static final int VELOCITY_SPAN = 4;
    private static final double MAX_HORIZONTAL_SPEED = 0.5;
    private static final int FORGET_AFTER = 40;

    private final Map<Integer, Track> tracks = new HashMap<>();
    private long tick;

    private static final class Track {
        final double[] x = new double[HISTORY];
        final double[] y = new double[HISTORY];
        final double[] z = new double[HISTORY];
        int count;
        int head = -1;
        double groundY = Double.NaN;
        long lastSeen;

        void push(double px, double py, double pz, boolean onGround, long tick) {
            this.head = (this.head + 1) % HISTORY;
            this.x[this.head] = px;
            this.y[this.head] = py;
            this.z[this.head] = pz;
            this.count = Math.min(this.count + 1, HISTORY);
            if (onGround) {
                this.groundY = py;
            }
            this.lastSeen = tick;
        }

        /** Index of the sample {@code back} ticks ago. */
        int at(int back) {
            return ((this.head - back) % HISTORY + HISTORY) % HISTORY;
        }
    }

    /** Where a target is expected to be on each tick of a projectile's flight. */
    public static final class Prediction {
        /** Expanded target boxes, one per projectile tick. */
        public final AxisAlignedBB[] boxes;
        /** Aim points (chest height), one per projectile tick. */
        public final double[][] centers;

        Prediction(AxisAlignedBB[] boxes, double[][] centers) {
            this.boxes = boxes;
            this.centers = centers;
        }
    }

    public void clear() {
        this.tracks.clear();
        this.tick = 0L;
    }

    /** Records the current server position of every entity within {@code range} that passes {@code filter}. */
    public void update(EntityPlayerSP player, double range, Predicate<Entity> filter) {
        this.tick++;
        double rangeSq = range * range;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase) || entity == player || !filter.test(entity)) {
                continue;
            }
            if (entity.getDistanceSqToEntity(player) > rangeSq) {
                continue;
            }
            Track track = this.tracks.get(entity.getEntityId());
            if (track == null) {
                track = new Track();
                this.tracks.put(entity.getEntityId(), track);
            }
            track.push(serverX(entity), serverY(entity), serverZ(entity), entity.onGround, this.tick);
        }
        Iterator<Map.Entry<Integer, Track>> iterator = this.tracks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (this.tick - iterator.next().getValue().lastSeen > FORGET_AFTER) {
                iterator.remove();
            }
        }
    }

    private static double serverX(Entity entity) {
        return (double) entity.serverPosX / 32.0;
    }

    private static double serverY(Entity entity) {
        return (double) entity.serverPosY / 32.0;
    }

    private static double serverZ(Entity entity) {
        return (double) entity.serverPosZ / 32.0;
    }

    /** {vx, vy, vz} in blocks per tick; zero when the target has not been tracked long enough. */
    public double[] velocity(EntityLivingBase entity) {
        Track track = this.tracks.get(entity.getEntityId());
        if (track == null || track.count < 2) {
            return new double[3];
        }
        int span = Math.min(VELOCITY_SPAN, track.count - 1);
        int now = track.at(0);
        int then = track.at(span);
        double vx = (track.x[now] - track.x[then]) / span;
        double vz = (track.z[now] - track.z[then]) / span;
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        if (horizontal > MAX_HORIZONTAL_SPEED) {
            vx = vx / horizontal * MAX_HORIZONTAL_SPEED;
            vz = vz / horizontal * MAX_HORIZONTAL_SPEED;
        }
        int ySpan = Math.min(2, track.count - 1);
        double vy = (track.y[now] - track.y[track.at(ySpan)]) / ySpan;
        return new double[]{vx, vy, vz};
    }

    /**
     * Predicts the target for {@code ticks} projectile ticks. Projectile tick k sees the target {@code lead + k}
     * ticks into the future (lead covers the latency of our use packet and of the positions we see).
     */
    public Prediction predict(EntityLivingBase entity, int ticks, int lead) {
        Track track = this.tracks.get(entity.getEntityId());
        double x = serverX(entity);
        double y = serverY(entity);
        double z = serverZ(entity);
        double[] v = this.velocity(entity);
        double vy = v[1];
        boolean airborne = !entity.onGround;
        double floor = track != null && !Double.isNaN(track.groundY) ? track.groundY : y;
        if (!airborne) {
            vy = 0.0;
        }
        double halfWidth = entity.width / 2.0;
        double height = entity.height;
        AxisAlignedBB[] boxes = new AxisAlignedBB[ticks];
        double[][] centers = new double[ticks][];
        int total = lead + ticks;
        int k = 0;
        for (int step = 1; step <= total; step++) {
            x += v[0];
            z += v[2];
            if (airborne) {
                vy = (vy - 0.08) * 0.98;
                y += vy;
                if (y < floor) {
                    y = floor;
                    vy = 0.0;
                    airborne = false;
                }
            }
            if (step > lead) {
                boxes[k] = new AxisAlignedBB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth)
                        .expand(ThrowSim.HIT_EXPAND, ThrowSim.HIT_EXPAND, ThrowSim.HIT_EXPAND);
                centers[k] = new double[]{x, y + height * 0.6, z};
                k++;
            }
        }
        return new Prediction(boxes, centers);
    }

    /** How fast the target moves towards the player, in blocks per tick (negative = moving away). */
    public double approachSpeed(EntityLivingBase entity, EntityPlayerSP player) {
        double[] v = this.velocity(entity);
        double dx = player.posX - serverX(entity);
        double dz = player.posZ - serverZ(entity);
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4) {
            return 0.0;
        }
        return (v[0] * dx + v[2] * dz) / length;
    }

    /** Whether the target's head points at the player within {@code maxAngle} degrees. */
    public static boolean isFacing(EntityLivingBase entity, EntityPlayerSP player, float maxAngle) {
        float yawToPlayer = (float) (Math.atan2(player.posZ - entity.posZ, player.posX - entity.posX) * 180.0 / Math.PI) - 90.0F;
        return Math.abs(MathHelper.wrapAngleTo180_float(yawToPlayer - entity.rotationYawHead)) <= maxAngle;
    }

    /**
     * True when the target stands on ground but being knocked straight away from the player would put it over
     * the void (nothing solid below, all the way down).
     */
    public static boolean wouldFallIntoVoid(EntityLivingBase entity, EntityPlayerSP player) {
        double dx = entity.posX - player.posX;
        double dz = entity.posZ - player.posZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4) {
            return false;
        }
        dx /= length;
        dz /= length;
        AxisAlignedBB box = entity.getEntityBoundingBox();
        if (isOverVoid(box)) {
            return false;
        }
        for (double distance : new double[]{1.0, 2.0, 2.8}) {
            if (isOverVoid(box.offset(dx * distance, 0.0, dz * distance))) {
                return true;
            }
        }
        return false;
    }

    /** Nothing but replaceable blocks under the box down to y=0. Unloaded chunks never count as void. */
    private static boolean isOverVoid(AxisAlignedBB box) {
        int minY = MathHelper.floor_double(box.minY);
        if (minY < 0) {
            return true;
        }
        int minX = MathHelper.floor_double(box.minX);
        int maxX = MathHelper.floor_double(box.maxX);
        int minZ = MathHelper.floor_double(box.minZ);
        int maxZ = MathHelper.floor_double(box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos column = new BlockPos(x, minY, z);
                if (!mc.theWorld.isBlockLoaded(column)) {
                    return false;
                }
                for (int y = minY; y >= 0; y--) {
                    if (!BlockUtil.isReplaceable(new BlockPos(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
