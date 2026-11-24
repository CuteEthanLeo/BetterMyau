package myau.module.modules.flymodes.other;

import myau.event.types.EventType;
import myau.events.MoveEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * PolarBoat fly (from Unfair Client).
 *
 * Get on a boat with this mode enabled: the boat is attacked until it breaks, dismount packets
 * are swallowed, and once the boat is gone the vanilla-style fly starts (jump = up, sneak = down).
 * Transaction packets are held from the first attack on and handled when the mode is disabled.
 */
public class PolarBoat extends FlyMode {
    private final Deque<Packet<?>> polarS32Packets = new ConcurrentLinkedDeque<Packet<?>>();
    private double verticalMotion = 0.0;
    private Entity polarBoat = null;
    private boolean polarAttackedBoat = false;
    private boolean polarMovementCheckDisabled = false;

    public PolarBoat() {
        super("PolarBoat");
    }

    @Override
    public void onEnable() {
        this.resetPolar();
    }

    @Override
    public void onDisable() {
        this.flushPolarPackets();
        this.resetPolar();
        if (mc.thePlayer == null) {
            return;
        }
        mc.thePlayer.motionY = 0.0;
        MoveUtil.strafe(0.0);

        KeyBindUtil.updateKeyState(mc.gameSettings.keyBindSneak.getKeyCode());
    }

    private boolean shouldUseVanillaFly() {
        return this.polarMovementCheckDisabled;
    }

    private void resetPolar() {
        this.polarBoat = null;
        this.polarAttackedBoat = false;
        this.polarMovementCheckDisabled = false;
        this.polarS32Packets.clear();
    }

    /** Handles all held transaction packets without firing packet events. */
    private void flushPolarPackets() {
        List<Packet<?>> held = new ArrayList<Packet<?>>();
        Packet<?> packet = this.polarS32Packets.poll();
        while (packet != null) {
            held.add(packet);
            packet = this.polarS32Packets.poll();
        }
        FlyUtil.processPackets(held);
    }

    private boolean isPolarBoatGone() {
        return this.polarBoat == null
                || this.polarBoat.isDead
                || mc.theWorld == null
                || !mc.theWorld.loadedEntityList.contains(this.polarBoat);
    }

    /** Sneak pressed while riding the boat. */
    private boolean isPolarDismountPacket(Packet<?> packet) {
        return packet instanceof C0BPacketEntityAction
                && ((C0BPacketEntityAction) packet).getAction() == C0BPacketEntityAction.Action.START_SNEAKING
                && (mc.thePlayer.ridingEntity instanceof EntityBoat || this.polarBoat != null);
    }

    /** Attacks the boat every tick until it is gone, then unlocks the fly. */
    private void updatePolarState() {
        if (mc.thePlayer == null) {
            return;
        }

        if (mc.thePlayer.ridingEntity instanceof EntityBoat && this.polarBoat == null) {
            this.polarBoat = mc.thePlayer.ridingEntity;
        }

        if (this.polarBoat != null && !this.polarMovementCheckDisabled && !this.isPolarBoatGone()) {
            PacketUtil.sendPacket(new C02PacketUseEntity(this.polarBoat, C02PacketUseEntity.Action.ATTACK));
            PacketUtil.sendPacket(new C0APacketAnimation());
            this.polarAttackedBoat = true;
        }

        if (this.polarAttackedBoat && !this.polarMovementCheckDisabled && this.isPolarBoatGone()) {
            this.polarMovementCheckDisabled = true;
            FlyUtil.chat("&a[PolarBoat] &fBoat destroyed, fly enabled!");
        }
    }

    @Override
    public void onMove(MoveEvent event) {
        if (!this.shouldUseVanillaFly()) {
            return;
        }

        if (mc.thePlayer == null) {
            return;
        }

        if (mc.thePlayer.posY % 1.0 != 0.0) {
            mc.thePlayer.motionY = this.verticalMotion;
        }

        mc.thePlayer.motionX = 0.0;
        mc.thePlayer.motionZ = 0.0;
        event.setX(0.0);
        event.setZ(0.0);

        float baseMoveSpeed = 0.28015F;
        float friction = baseMoveSpeed * this.parent.polarBoatHSpeed.getValue();
        FlyUtil.strafe(friction, false, event);
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        this.updatePolarState();

        if (!this.shouldUseVanillaFly()) {
            return;
        }

        if (mc.thePlayer == null) {
            return;
        }

        this.verticalMotion = 0.0;

        if (mc.currentScreen == null) {
            double vSpeed = this.parent.polarBoatVSpeed.getValue().doubleValue();

            if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                this.verticalMotion += vSpeed * 0.42F;
            }

            if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
                this.verticalMotion -= vSpeed * 0.42F;
            }

            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        }
    }

    @Override
    public void onPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();

        if (event.getType() == EventType.SEND && this.isPolarDismountPacket(packet)) {
            event.setCancelled(true);
            return;
        }

        if (event.getType() == EventType.RECEIVE
                && this.polarAttackedBoat
                && packet instanceof S32PacketConfirmTransaction) {
            this.polarS32Packets.offer(packet);
            event.setCancelled(true);
        }
    }
}
