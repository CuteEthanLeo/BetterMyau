package myau.module.modules.nofallmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C03PacketPlayer;

/** The xw "Packet" mode (named Packet2 here: the original PACKET mode keeps its name). */
public class Packet2 extends NoFallMode {
    public Packet2() {
        super("Packet2");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.fallDistance > 2.0F) {
            PacketUtil.sendPacket(new C03PacketPlayer(true));
        }
    }
}
