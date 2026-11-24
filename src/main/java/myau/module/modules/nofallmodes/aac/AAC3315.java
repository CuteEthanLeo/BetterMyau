package myau.module.modules.nofallmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C03PacketPlayer;

public class AAC3315 extends NoFallMode {
    public AAC3315() {
        super("AAC3.3.15");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.isIntegratedServerRunning()) {
            return;
        }
        if (mc.thePlayer.fallDistance > 2.0F) {
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX, Double.NaN, mc.thePlayer.posZ, false));
            mc.thePlayer.fallDistance = -9999.0F;
        }
    }
}
