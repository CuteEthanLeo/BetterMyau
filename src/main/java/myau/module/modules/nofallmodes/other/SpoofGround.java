package myau.module.modules.nofallmodes.other;

import myau.events.PacketEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.nofallmodes.NoFallMode;
import net.minecraft.network.play.client.C03PacketPlayer;

public class SpoofGround extends NoFallMode {
    public SpoofGround() {
        super("SpoofGround");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C03PacketPlayer) {
            ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(true);
        }
    }
}
