package myau.module.modules.nofallmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C03PacketPlayer;

public class CubeCraft extends NoFallMode {
    public CubeCraft() {
        super("CubeCraft");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.fallDistance > 2.0F) {
            mc.thePlayer.onGround = false;
            PacketUtil.sendPacket(new C03PacketPlayer(true));
        }
    }
}
