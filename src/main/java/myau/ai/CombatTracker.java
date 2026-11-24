package myau.ai;

import myau.util.TargetTracker;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers who has been hitting us, for the "在打我" flag. The client is never told who dealt a
 * hit, so when our hurt time jumps we blame the players that were close, facing us and swinging.
 */
public final class CombatTracker {
    /** A hit counts as "hitting me" for this long. */
    public static final long WINDOW_MS = 2000L;
    private static final double MELEE_RANGE = 4.5;

    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Map<Integer, Long> lastHitMe = new HashMap<>();
    private int lastHurtTime = 0;

    public void reset() {
        this.lastHitMe.clear();
        this.lastHurtTime = 0;
    }

    public void tick() {
        EntityPlayerSP me = mc.thePlayer;
        if (me == null || mc.theWorld == null) return;
        if (me.hurtTime > this.lastHurtTime) {
            this.blameAttackers(me);
        }
        this.lastHurtTime = me.hurtTime;
        long now = System.currentTimeMillis();
        this.lastHitMe.values().removeIf(t -> now - t > WINDOW_MS * 5);
    }

    public boolean hitMeRecently(Entity entity) {
        Long t = this.lastHitMe.get(entity.getEntityId());
        return t != null && System.currentTimeMillis() - t <= WINDOW_MS;
    }

    /** Entity id of whoever hit us last within {@code withinMs}, or -1. */
    public int lastAttacker(long withinMs) {
        long now = System.currentTimeMillis();
        int best = -1;
        long bestTime = 0L;
        for (Map.Entry<Integer, Long> e : this.lastHitMe.entrySet()) {
            if (now - e.getValue() <= withinMs && e.getValue() > bestTime) {
                best = e.getKey();
                bestTime = e.getValue();
            }
        }
        return best;
    }

    private void blameAttackers(EntityPlayerSP me) {
        long now = System.currentTimeMillis();
        EntityOtherPlayerMP nearest = null;
        double nearestDist = MELEE_RANGE;
        boolean blamed = false;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityOtherPlayerMP)) continue;
            EntityOtherPlayerMP other = (EntityOtherPlayerMP) entity;
            double dist = me.getDistanceToEntity(other);
            if (dist > MELEE_RANGE || other.isDead || TeamUtil.isSameTeam(other)) continue;
            if (!TargetTracker.isFacing(other, me, 45.0F)) continue;
            if (other.isSwingInProgress) {
                this.lastHitMe.put(other.getEntityId(), now);
                blamed = true;
            } else if (dist < nearestDist) {
                nearest = other;
                nearestDist = dist;
            }
        }
        // nobody visibly swinging (packet order, lag): blame the closest player facing us
        if (!blamed && nearest != null) this.lastHitMe.put(nearest.getEntityId(), now);
    }
}
