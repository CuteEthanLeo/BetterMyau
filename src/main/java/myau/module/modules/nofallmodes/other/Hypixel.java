package myau.module.modules.nofallmodes.other;

import myau.events.PacketEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.nofallmodes.NoFallMode;
import net.minecraft.network.play.client.C03PacketPlayer;

/** Watchdog. Credit: @localpthebest / Hypixel */
public class Hypixel extends NoFallMode {
    private boolean jump = false;

    public Hypixel() {
        super("Hypixel");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C03PacketPlayer.C04PacketPlayerPosition) {
            C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
            if (mc.thePlayer.fallDistance >= 3.3) {
                this.jump = true;
            }
            if (this.jump && mc.thePlayer.onGround) {
                ((IAccessorC03PacketPlayer) packet).setOnGround(false);
                if (!mc.gameSettings.keyBindJump.isKeyDown()) {
                    mc.thePlayer.setPosition(packet.getPositionX(), packet.getPositionY() + 0.09, packet.getPositionZ());
                }
                this.jump = false;
            }
        }
    }
}
