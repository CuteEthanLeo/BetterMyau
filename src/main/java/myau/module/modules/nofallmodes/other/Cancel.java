package myau.module.modules.nofallmodes.other;

import myau.events.PacketEvent;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.util.PacketUtil;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

/** Recommended for falls shorter than 15 blocks. */
public class Cancel extends NoFallMode {
    private boolean isFalling = false;

    public Cancel() {
        super("Cancel");
    }

    @Override
    public void onPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        if (event.isCancelled()) {
            return;
        }
        if (packet instanceof S08PacketPlayerPosLook && this.isFalling) {
            S08PacketPlayerPosLook posLook = (S08PacketPlayerPosLook) packet;
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(posLook.getX(), posLook.getY(), posLook.getZ(), true));
            this.isFalling = false;
        }
        if (packet instanceof C03PacketPlayer) {
            if (mc.thePlayer.fallDistance > 3.0F) {
                this.isFalling = true;
                event.setCancelled(true);
            }
        }
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null) {
            mc.thePlayer.fallDistance = 0.0F;
        }
        this.isFalling = false;
    }
}
