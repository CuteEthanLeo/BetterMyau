package myau.module.modules.nofallmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C03PacketPlayer;

public class AAC extends NoFallMode {
    private int currentState = 0;

    public AAC() {
        super("AAC");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.fallDistance > 2.0F) {
            PacketUtil.sendPacket(new C03PacketPlayer(true));
            this.currentState = 2;
        } else if (this.currentState == 2 && mc.thePlayer.fallDistance < 2.0F) {
            mc.thePlayer.motionY = 0.1;
            this.currentState = 3;
            return;
        }
        switch (this.currentState) {
            case 3:
                mc.thePlayer.motionY = 0.1;
                this.currentState = 4;
                break;
            case 4:
                mc.thePlayer.motionY = 0.1;
                this.currentState = 5;
                break;
            case 5:
                mc.thePlayer.motionY = 0.1;
                this.currentState = 1;
                break;
            default:
                break;
        }
    }
}
