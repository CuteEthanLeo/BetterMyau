package myau.util;

import myau.Myau;
import myau.module.modules.KillAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The "Smart" decision shared by AutoRod and AutoProjectile: which target to throw at, whether throwing right now
 * actually helps (knockback lands, enemy is coming at us or standing at a void edge, sword is not the better
 * option) and which rotation makes the projectile meet the moving target.
 */
public final class SmartThrowHelper {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Only this many of the best-ranked candidates are run through the aim solver each tick. */
    private static final int MAX_SOLVED = 3;
    private static final double RETREAT_SPEED = -0.03;
    private static final float FACING_ANGLE = 60.0F;

    public static final class Settings {
        public ThrowSim.Kind kind;
        public double minDistance;
        public double maxDistance;
        public float fov;
        public boolean yieldToKillAura;
        public boolean approachOnly;
        public boolean edgeKnock;
        public boolean pingCompensation;
        public boolean skipLowHealth;
        public float lowHealthThreshold;
        public ToDoubleFunction<EntityLivingBase> health;
        public Predicate<Entity> filter;
    }

    public static final class Plan {
        public final EntityLivingBase target;
        public final float yaw;
        public final float pitch;
        public final int ticks;
        public final int lead;
        public final boolean edge;

        Plan(EntityLivingBase target, float yaw, float pitch, int ticks, int lead, boolean edge) {
            this.target = target;
            this.yaw = yaw;
            this.pitch = pitch;
            this.ticks = ticks;
            this.lead = lead;
            this.edge = edge;
        }
    }

    private SmartThrowHelper() {
    }

    /** Ticks between sending the use packet and the server acting on it, plus the age of the positions we see. */
    public static int leadTicks(boolean pingCompensation) {
        if (!pingCompensation || mc.getNetHandler() == null || mc.thePlayer == null) {
            return 0;
        }
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        if (info == null) {
            return 0;
        }
        return MathHelper.clamp_int(Math.round((float) info.getResponseTime() / 50.0F), 0, 8);
    }

    /** True while KillAura is already fighting someone at sword range. */
    public static boolean isMeleeEngaged() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura == null || !killAura.isEnabled()) {
            return false;
        }
        EntityLivingBase target = killAura.getTarget();
        return target != null && !target.isDead
                && RotationUtil.distanceToEntity(target) <= (double) killAura.swingRange.getValue();
    }

    /**
     * Picks a target and the rotation to hit it, or null when throwing now would not help. Knockback timing
     * (hurt time) is not checked here: the caller keeps aiming and fires once {@link #hurtTimeReady} agrees.
     */
    public static Plan plan(EntityPlayerSP player, TargetTracker tracker, Settings settings) {
        if (settings.yieldToKillAura && isMeleeEngaged()) {
            return null;
        }
        List<EntityLivingBase> candidates = new ArrayList<>();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase) || entity == player || !settings.filter.test(entity)) {
                continue;
            }
            double distance = RotationUtil.distanceToEntity(entity);
            if (distance < settings.minDistance || distance > settings.maxDistance) {
                continue;
            }
            if (angleFromView(player, entity) > settings.fov / 2.0F) {
                continue;
            }
            candidates.add((EntityLivingBase) entity);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        EntityLivingBase auraTarget = killAura != null && killAura.isEnabled() ? killAura.getTarget() : null;
        candidates.sort((a, b) -> {
            if (a == b) {
                return 0;
            }
            if (a == auraTarget) {
                return -1;
            }
            if (b == auraTarget) {
                return 1;
            }
            return Float.compare(angleFromView(player, a), angleFromView(player, b));
        });
        int lead = leadTicks(settings.pingCompensation);
        int solved = 0;
        for (EntityLivingBase target : candidates) {
            boolean edge = settings.edgeKnock && TargetTracker.wouldFallIntoVoid(target, player);
            if (!edge) {
                if (settings.approachOnly && tracker.approachSpeed(target, player) < RETREAT_SPEED
                        && !TargetTracker.isFacing(target, player, FACING_ANGLE)) {
                    // Knocking a fleeing enemy only pushes them further away.
                    continue;
                }
                if (settings.skipLowHealth && settings.health.applyAsDouble(target) < (double) settings.lowHealthThreshold) {
                    // Rods and snowballs deal no damage; finish low enemies with the sword.
                    continue;
                }
            }
            if (solved++ >= MAX_SOLVED) {
                break;
            }
            TargetTracker.Prediction prediction = tracker.predict(target, ThrowSim.MAX_TICKS, lead);
            ThrowSim.Solution solution = ThrowSim.solve(settings.kind, player, prediction.boxes, prediction.centers);
            if (solution != null) {
                return new Plan(target, solution.yaw, solution.pitch, solution.ticks, lead, edge);
            }
        }
        return null;
    }

    /** Projectile tick on which a throw with this exact rotation hits the target, or -1 (blocks included). */
    public static int checkHit(ThrowSim.Kind kind, EntityPlayerSP player, TargetTracker tracker, EntityLivingBase target,
                               float yaw, float pitch, int lead) {
        if (target == null || target.isDead) {
            return -1;
        }
        TargetTracker.Prediction prediction = tracker.predict(target, ThrowSim.MAX_TICKS, lead);
        return ThrowSim.simulate(kind, player, yaw, pitch, prediction.boxes, true);
    }

    /**
     * Zero-damage projectiles only knock back once the target's damage immunity is over. Client hurtTime runs
     * 10 -> 0 alongside the server's immunity (which allows knockback again at 10 of its 20 ticks), and what we
     * see is {@code lead} ticks behind the server once our packet arrives, so it must be over on arrival.
     */
    public static boolean hurtTimeReady(EntityLivingBase target, int ticks, int lead) {
        return target.hurtTime <= ticks + lead;
    }

    /** Moves from {@code from} towards {@code to}, at most {@code maxTurn} degrees per axis, snapped to mouse steps. */
    public static float[] stepRotation(float fromYaw, float fromPitch, float toYaw, float toPitch, float maxTurn) {
        float yawDiff = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float pitchDiff = toPitch - fromPitch;
        float yaw = fromYaw + MathHelper.clamp_float(yawDiff, -maxTurn, maxTurn);
        float pitch = MathHelper.clamp_float(fromPitch + MathHelper.clamp_float(pitchDiff, -maxTurn, maxTurn), -90.0F, 90.0F);
        yaw = RotationUtil.snapToGcd(fromYaw, yaw);
        pitch = MathHelper.clamp_float(RotationUtil.snapToGcd(fromPitch, pitch), -90.0F, 90.0F);
        return new float[]{yaw, pitch};
    }

    private static float angleFromView(EntityPlayerSP player, Entity entity) {
        float yawTo = (float) (Math.atan2(entity.posZ - player.posZ, entity.posX - player.posX) * 180.0 / Math.PI) - 90.0F;
        return Math.abs(MathHelper.wrapAngleTo180_float(yawTo - player.rotationYaw));
    }
}
