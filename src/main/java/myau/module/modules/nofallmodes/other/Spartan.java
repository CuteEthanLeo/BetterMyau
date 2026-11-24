package myau.module.modules.nofallmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.util.PacketUtil;
import myau.util.TickTimer;
import net.minecraft.network.play.client.C03PacketPlayer;

public class Spartan extends NoFallMode {
    private final TickTimer spartanTimer = new TickTimer();

    public Spartan() {
        super("Spartan");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        this.spartanTimer.update();
        if (mc.thePlayer.fallDistance > 1.5 && this.spartanTimer.hasTimePassed(10)) {
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX, mc.thePlayer.posY + 10.0, mc.thePlayer.posZ, true));
            PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(mc.thePlayer.posX, mc.thePlayer.posY - 10.0, mc.thePlayer.posZ, true));
            this.spartanTimer.reset();
        }
    }
}
