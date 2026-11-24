package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorRenderManager;
import myau.mixin.IAccessorS14PacketEntity;
import myau.module.Module;
import myau.module.modules.flymodes.FlyUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds incoming server packets for a short, random time after hitting a player, so the target keeps
 * standing where it was a moment ago while the real position (tracked from the held movement packets)
 * is drawn as a box. Ported from Zak0's BackTrackV2; replaces the old BackTrack, which only re-sent an
 * old position of our own before each hit.
 */
public class BackTrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Stop holding when the target has not been hit for this long. */
    private static final long IDLE_MS = 2000L;

    public final IntProperty minMs = new IntProperty("min-ms", 50, 10, 1000);
    public final IntProperty maxMs = new IntProperty("max-ms", 100, 10, 1000);
    public final FloatProperty minDistance = new FloatProperty("min-distance", 0.0F, 0.0F, 3.0F);
    public final FloatProperty maxDistance = new FloatProperty("max-distance", 6.0F, 0.0F, 10.0F);
    /** Release everything when the target's hurt time equals this (-1 = off). */
    public final IntProperty targetHurtTime = new IntProperty("target-hurt-time", -1, -1, 10);
    /** Release everything when our own hurt time equals this (-1 = off). */
    public final IntProperty selfHurtTime = new IntProperty("self-hurt-time", -1, -1, 10);
    public final BooleanProperty drawRealPosition = new BooleanProperty("draw-real-position", true);

    /** Guards {@link #queue} and {@link #realPos}: packets are queued on the network thread, released on the client thread. */
    private final Object lock = new Object();
    private final ArrayDeque<TimedPacket> queue = new ArrayDeque<>();
    private volatile EntityPlayer target;
    /** Where the server has the target, following every movement packet including the held ones. */
    private volatile Vec3 realPos;
    private volatile int currentLatency;
    private volatile long lastAttack;
    private Vec3 renderPos;

    public BackTrack() {
        super("BackTrack", false);
    }

    @Override
    public void onEnabled() {
        synchronized (this.lock) {
            this.queue.clear();
        }
        this.resetTarget();
    }

    @Override
    public void onDisabled() {
        this.releaseAll();
        this.resetTarget();
    }

    @Override
    public void verifyValue(String name) {
        if (this.minMs.getName().equals(name)) {
            if (this.minMs.getValue() > this.maxMs.getValue()) this.maxMs.setValue(this.minMs.getValue());
        } else if (this.maxMs.getName().equals(name)) {
            if (this.minMs.getValue() > this.maxMs.getValue()) this.minMs.setValue(this.maxMs.getValue());
        } else if (this.minDistance.getName().equals(name)) {
            if (this.minDistance.getValue() > this.maxDistance.getValue()) this.maxDistance.setValue(this.minDistance.getValue());
        } else if (this.maxDistance.getName().equals(name)) {
            if (this.minDistance.getValue() > this.maxDistance.getValue()) this.minDistance.setValue(this.maxDistance.getValue());
        }
    }

    @Override
    public String[] getSuffix() {
        int min = this.minMs.getValue(), max = this.maxMs.getValue();
        return new String[]{min == max ? min + "ms" : min + "-" + max + "ms"};
    }

    // ---------------------------------------------------------------- events

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !(event.getTarget() instanceof EntityPlayer)) return;
        EntityPlayer hit = (EntityPlayer) event.getTarget();
        if (hit != this.target) {
            // packets held for the old target would put the new one's tracked position out of step
            this.releaseAll();
            synchronized (this.lock) {
                this.realPos = serverPos(hit);
            }
            this.renderPos = null;
            this.target = hit;
        }
        this.lastAttack = System.currentTimeMillis();
        double distance = hit.getPositionVector().distanceTo(mc.thePlayer.getPositionVector());
        if (distance > this.maxDistance.getValue() || distance < this.minDistance.getValue()) return;
        int min = this.minMs.getValue(), max = this.maxMs.getValue();
        this.currentLatency = min + (int) (Math.random() * (max - min));
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) return;
        EntityPlayer t = this.target;
        Vec3 real = this.realPos;
        if (t == null || real == null) {
            this.currentLatency = 0;
            return;
        }
        if (t.isDead || t.worldObj != mc.theWorld) {
            this.resetTarget();
            this.releaseAll();
            return;
        }
        Vec3 self = mc.thePlayer.getPositionVector();
        double realDistance = real.distanceTo(self);
        // the real position is closer than the one shown: holding only hurts
        if (realDistance < t.getPositionVector().distanceTo(self)) this.currentLatency = 0;
        if (realDistance > this.maxDistance.getValue() || realDistance < this.minDistance.getValue()) this.currentLatency = 0;
        if (System.currentTimeMillis() - this.lastAttack > IDLE_MS) this.currentLatency = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        List<Packet<?>> due = new ArrayList<>();
        synchronized (this.lock) {
            while (!this.queue.isEmpty() && this.queue.peek().age() >= this.currentLatency) {
                due.add(this.queue.poll().packet);
            }
            EntityPlayer t = this.target;
            if (this.queue.isEmpty() && t != null) this.realPos = serverPos(t);
        }
        // client thread: handled right here, ahead of anything the network thread queues meanwhile
        FlyUtil.processPackets(due);
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        // whatever is still held belongs to the world being left
        synchronized (this.lock) {
            this.queue.clear();
        }
        this.resetTarget();
    }

    /** Runs on the network thread. */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || event.isCancelled()) return;
        Packet<?> packet = event.getPacket();
        EntityPlayerSP self = mc.thePlayer;
        if (self == null || mc.theWorld == null || self.ticksExisted < 20) {
            this.releaseAll();
            return;
        }
        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S40PacketDisconnect
                || packet instanceof S01PacketJoinGame || packet instanceof S07PacketRespawn) {
            this.releaseAll();
            this.resetTarget();
            return;
        }
        EntityPlayer t = this.target;
        if (t == null) {
            this.releaseAll();
            return;
        }
        if (this.targetHurtTime.getValue() != -1 && t.hurtTime == this.targetHurtTime.getValue()
                || this.selfHurtTime.getValue() != -1 && self.hurtTime == this.selfHurtTime.getValue()) {
            this.releaseAll();
            return;
        }
        // these never wait
        if (packet instanceof S19PacketEntityStatus || packet instanceof S02PacketChat
                || packet instanceof S0BPacketAnimation || packet instanceof S06PacketUpdateHealth) {
            return;
        }
        List<Packet<?>> flush = null;
        synchronized (this.lock) {
            int id = t.getEntityId();
            if (packet instanceof S13PacketDestroyEntities) {
                for (int destroyed : ((S13PacketDestroyEntities) packet).getEntityIDs()) {
                    if (destroyed == id) {
                        flush = this.drainLocked();
                        this.resetTarget();
                        break;
                    }
                }
            } else if (packet instanceof S14PacketEntity) {
                S14PacketEntity move = (S14PacketEntity) packet;
                if (((IAccessorS14PacketEntity) move).getEntityId() == id && this.realPos != null) {
                    this.realPos = this.realPos.addVector(move.func_149062_c() / 32.0, move.func_149061_d() / 32.0, move.func_149064_e() / 32.0);
                }
            } else if (packet instanceof S18PacketEntityTeleport) {
                S18PacketEntityTeleport teleport = (S18PacketEntityTeleport) packet;
                if (teleport.getEntityId() == id) {
                    this.realPos = new Vec3(teleport.getX() / 32.0, teleport.getY() / 32.0, teleport.getZ() / 32.0);
                }
            }
            if (flush == null) {
                // Not holding and nothing waiting: let it through untouched. Something waiting: queue
                // behind it so the order stays the same.
                if (this.currentLatency <= 0 && this.queue.isEmpty()) return;
                this.queue.add(new TimedPacket(packet));
                event.setCancelled(true);
                return;
            }
        }
        // destroy packet for the target: everything held goes first, then the destroy packet itself
        FlyUtil.processPackets(flush);
    }

    // ---------------------------------------------------------------- render

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        EntityPlayer t = this.target;
        Vec3 real = this.realPos;
        if (!this.isEnabled() || !this.drawRealPosition.getValue() || t == null || real == null || t.isDead) {
            this.renderPos = null;
            return;
        }
        Vec3 shown = t.getPositionVector();
        if (real.distanceTo(shown) < 0.1) {
            this.renderPos = null;
            return;
        }
        Vec3 goal = this.currentLatency > 0 ? real : shown;
        if (this.renderPos == null) {
            this.renderPos = goal;
        } else {
            double k = 0.2;
            this.renderPos = new Vec3(
                    this.renderPos.xCoord + (goal.xCoord - this.renderPos.xCoord) * k,
                    this.renderPos.yCoord + (goal.yCoord - this.renderPos.yCoord) * k,
                    this.renderPos.zCoord + (goal.zCoord - this.renderPos.zCoord) * k);
        }
        IAccessorRenderManager rm = (IAccessorRenderManager) mc.getRenderManager();
        double x = this.renderPos.xCoord - rm.getRenderPosX();
        double y = this.renderPos.yCoord - rm.getRenderPosY();
        double z = this.renderPos.zCoord - rm.getRenderPosZ();
        double half = t.width / 2.0 + 0.1;
        AxisAlignedBB box = new AxisAlignedBB(x - half, y - 0.1, z - half, x + half, y + t.height + 0.1, z + half);
        RenderUtil.enableRenderState();
        RenderUtil.drawFilledBox(box, 255, 255, 255);
        RenderUtil.drawBoundingBox(box, 255, 255, 255, 200, 1.5F);
        RenderUtil.disableRenderState();
    }

    // ---------------------------------------------------------------- dynamic island


    /** While holding: the delay, and how far the oldest held packet is through it. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        int latency = this.currentLatency;
        if (!this.isEnabled() || mc.thePlayer == null || this.target == null || latency <= 0) return;
        long oldest;
        int held;
        synchronized (this.lock) {
            if (this.queue.isEmpty()) return;
            oldest = this.queue.peek().age();
            held = this.queue.size();
        }
        myau.ui.glass.Island.live("backtrack", myau.ui.glass.Island.P_TIMER,
                "Backtrack §f" + latency + "ms §7" + held,
                myau.ui.GlassTheme.accent(), Math.min(1.0F, oldest / (float) latency));
    }

    // ---------------------------------------------------------------- helpers

    /** Hands every held packet back, in order. Safe from either thread. */
    private void releaseAll() {
        List<Packet<?>> all;
        synchronized (this.lock) {
            if (this.queue.isEmpty()) return;
            all = this.drainLocked();
        }
        FlyUtil.processPackets(all);
    }

    private List<Packet<?>> drainLocked() {
        List<Packet<?>> all = new ArrayList<>(this.queue.size());
        for (TimedPacket timed : this.queue) all.add(timed.packet);
        this.queue.clear();
        return all;
    }

    private void resetTarget() {
        this.target = null;
        this.realPos = null;
        this.renderPos = null;
        this.currentLatency = 0;
    }

    /** The target's position as the client last heard it from the server (not the smoothed one shown). */
    private static Vec3 serverPos(Entity entity) {
        return new Vec3(entity.serverPosX / 32.0, entity.serverPosY / 32.0, entity.serverPosZ / 32.0);
    }

    private static final class TimedPacket {
        final Packet<?> packet;
        final long time = System.currentTimeMillis();

        TimedPacket(Packet<?> packet) {
            this.packet = packet;
        }

        long age() {
            return System.currentTimeMillis() - this.time;
        }
    }
}
