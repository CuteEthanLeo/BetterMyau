package myau.module.modules.nofallmodes.other;

import myau.events.PacketEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.util.FallingPlayer;
import myau.util.MoveUtil;
import myau.util.TickScheduler;
import net.minecraft.network.play.client.C03PacketPlayer;

/** Watchdog. Credit: @localpthebest / HypixelPacket */
public class HypixelTimer extends NoFallMode {
    /** The timer is slowed down and the reset is still pending. */
    private boolean slowed = false;

    public HypixelTimer() {
        super("HypixelTimer");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (mc.theWorld == null) {
            return;
        }
        if (event.getPacket() instanceof C03PacketPlayer) {
            FallingPlayer fallingPlayer = new FallingPlayer(mc.thePlayer);
            if (fallingPlayer.findCollision(500) != null && mc.thePlayer.fallDistance - mc.thePlayer.motionY >= 3.3) {
                MoveUtil.setTimer(0.5F);
                this.slowed = true;
                ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(true);
                mc.thePlayer.fallDistance = 0.0F;
                TickScheduler.schedule(1, () -> {
                    if (this.slowed) {
                        this.slowed = false;
                        MoveUtil.setTimer(1.0F);
                    }
                });
            }
        }
    }

    @Override
    public void onDisable() {
        if (this.slowed) {
            this.slowed = false;
            MoveUtil.setTimer(1.0F);
        }
    }
}
