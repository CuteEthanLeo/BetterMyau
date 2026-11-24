package myau.module.modules.flymodes.blocksmc;

import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;

import java.util.ArrayList;
import java.util.List;

/**
 * Flying method similar to the BlocksMC mode, written by EclipsesDev to lag instead of clip.
 *
 * As in the original, the only thing that is ever held back is the first inbound packet seen
 * after enabling (released on disable, on death and right after a respawn); no outbound packet
 * is queued.
 */
public class BlocksMC2 extends FlyMode {
    private boolean isFlying = false;
    private boolean isNotUnder = false;
    private boolean isBlinked = false;
    private boolean jumped = false;

    private final List<Packet<?>> packetsReceived = new ArrayList<Packet<?>>();

    public BlocksMC2() {
        super("BlocksMC2");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        boolean debug = this.parent.blocksmcDebug.getValue();

        if (this.isFlying) {
            if (mc.thePlayer.onGround && this.parent.blocksmcStopOnLanding.getValue()) {
                if (debug) {
                    FlyUtil.chat("Ground Detected.. Stopping Fly");
                }
                this.parent.setEnabled(false);
            }

            if (!MoveUtil.isMoving() && this.parent.blocksmcStopOnNoMove.getValue()) {
                if (debug) {
                    FlyUtil.chat("No Movement Detected.. Stopping Fly. (Could be flagged)");
                }
                this.parent.setEnabled(false);
            }
        }

        if (this.shouldFly()) {
            if (this.isBlinked) {
                if (this.parent.blocksmcStable.getValue()) {
                    mc.thePlayer.motionY = 0.0;
                }

                this.handleTimerSlow();
                this.handlePlayerFlying();
            } else {
                if (mc.thePlayer.onGround) {
                    MoveUtil.strafe();
                }
            }
        } else {
            if (debug) {
                FlyUtil.chat("Pls stand under a block");
            }
        }
    }

    @Override
    public void onDisable() {
        this.isNotUnder = false;
        this.isFlying = false;
        this.jumped = false;
        this.isBlinked = false;

        if (mc.thePlayer == null) {
            return;
        }

        this.blink();
    }

    @Override
    public void onWorld() {
        this.parent.setEnabled(false);

        // Disabling replays the held packet; if that was not possible (no player) it is dropped.
        synchronized (this.packetsReceived) {
            this.packetsReceived.clear();
        }
    }

    private void handleTimerSlow() {
        if (!mc.thePlayer.onGround && this.parent.blocksmcTimerSlowed.getValue()) {
            if (mc.thePlayer.ticksExisted % 4 == 0) {
                MoveUtil.setTimer(0.45F);
            } else {
                MoveUtil.setTimer(0.4F);
            }
        } else {
            MoveUtil.setTimer(1.0F);
        }
    }

    private boolean shouldFly() {
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, mc.thePlayer.getEntityBoundingBox().offset(0.0, 0.5, 0.0)).isEmpty()
                || this.isFlying;
    }

    private void handlePlayerFlying() {
        float boostSpeed = this.parent.blocksmcBoostSpeed.getValue();
        switch (MoveUtil.airTicks) {
            case 0:
                if (this.isNotUnder) {
                    MoveUtil.strafe(boostSpeed + this.parent.blocksmcExtraSpeed.getValue());
                    MoveUtil.tryJump();
                    this.isFlying = true;
                    this.isNotUnder = false;
                }
                break;
            case 1:
                if (this.isFlying) {
                    MoveUtil.strafe(boostSpeed);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();

        if (mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.isDead) {
            return;
        }

        if (event.isCancelled()) {
            return;
        }

        if (packet instanceof C00Handshake
                || packet instanceof C00PacketServerQuery
                || packet instanceof C01PacketPing
                || packet instanceof S02PacketChat
                || packet instanceof S40PacketDisconnect) {
            return;
        }

        if (!this.isBlinked) {
            this.isNotUnder = true;
            this.isBlinked = true;

            if (this.parent.blocksmcDebug.getValue()) {
                FlyUtil.chat("blinked.. fly now!");
            }

            if (event.getType() == EventType.RECEIVE && mc.thePlayer.ticksExisted > 10) {
                event.setCancelled(true);
                synchronized (this.packetsReceived) {
                    this.packetsReceived.add(packet);
                }
            }
        }
    }

    @Override
    public void onMotion() {
        this.releaseIfDead();
    }

    @Override
    public void onPostMotion() {
        this.releaseIfDead();
    }

    private void releaseIfDead() {
        if (mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.isDead || mc.thePlayer.ticksExisted <= 10) {
            this.blink();
        }
    }

    private void blink() {
        List<Packet<?>> held;
        synchronized (this.packetsReceived) {
            held = new ArrayList<Packet<?>>(this.packetsReceived);
            this.packetsReceived.clear();
        }
        FlyUtil.processPackets(held);
    }
}
