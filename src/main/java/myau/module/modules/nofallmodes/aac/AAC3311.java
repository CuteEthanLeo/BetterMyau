package myau.module.modules.nofallmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C03PacketPlayer;

public class AAC3311 extends NoFallMode {
    public AAC3311() {
        super("AAC3.3.11");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.fallDistance > 2.0F) {
            MoveUtil.stopXZ();
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(
                    mc.thePlayer.posX, mc.thePlayer.posY - 10E-4, mc.thePlayer.posZ, this.parent.isServerOnGround()
            ));
            PacketUtil.sendPacket(new C03PacketPlayer(true));
        }
    }
}
