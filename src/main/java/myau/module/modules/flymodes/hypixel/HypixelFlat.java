package myau.module.modules.flymodes.hypixel;

import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.util.MoveUtil;
import net.minecraft.network.play.server.S27PacketExplosion;

/**
 * Ported from LiquidBounce Nextgen FlyHypixelFlat (GPLv3), author @liquidsquid1.
 * anticheat: Watchdog (NCP), anticheatVersion 21.01.25, tested on hypixel.net
 * Needs an explosion (e.g. a fireball at your feet): flies flat for 30 ticks, then turns Fly off on landing.
 */
public class HypixelFlat extends FlyMode {
    private int flyTicks = 0;
    private boolean isFlying = false;
    /** 0 = waiting for the explosion, 1 = first boost done, 2 = second boost done, waiting for ground. */
    private int speedStage = 0;

    public HypixelFlat() {
        super("HypixelFlat");
    }

    @Override
    public void onEnable() {
        this.flyTicks = 0;
        this.isFlying = false;
        this.speedStage = 0;
    }

    @Override
    public void onDisable() {
        this.flyTicks = 0;
        this.isFlying = false;
        this.speedStage = 0;
        MoveUtil.setTimer(1.0F);
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!this.isFlying) {
            return;
        }
        // speed sequence
        switch (this.speedStage) {
            case 0:
                MoveUtil.strafe(0.8);
                this.speedStage = 1;
                break;
            case 1:
                MoveUtil.strafe(this.parent.hypixelFlatSpeed.getValue());
                this.speedStage = 2;
                if (mc.thePlayer.onGround) {
                    this.parent.setEnabled(false);
                    return;
                }
                break;
            default:
                if (mc.thePlayer.onGround) {
                    this.parent.setEnabled(false);
                    return;
                }
                break;
        }
        // velocity
        this.flyTicks++;
        if (this.flyTicks > 30) {
            MoveUtil.setTimer(1.0F);
            return;
        }
        MoveUtil.setTimer(this.parent.hypixelFlatTimer.getValue());
        mc.thePlayer.motionY = 0.0314 + Math.random() / 1000.0;
        MoveUtil.strafe(MoveUtil.getSpeed());
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S27PacketExplosion) {
            this.isFlying = true;
        }
    }
}
