package myau.module.modules.nofallmodes.other;

import myau.events.PacketEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.nofallmodes.NoFallMode;
import myau.util.FallingPlayer;
import myau.util.MoveUtil;
import net.minecraft.network.play.client.C03PacketPlayer;

/** Vulcan 2.8.8. Credit: @ion1x and @Razzy52 / VulcanTP */
public class VulcanFast288 extends NoFallMode {
    public VulcanFast288() {
        super("VulcanFast2.8.8");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (mc.theWorld == null) {
            return;
        }
        if (event.getPacket() instanceof C03PacketPlayer.C04PacketPlayerPosition) {
            if (mc.thePlayer.fallDistance > 2.5 && mc.thePlayer.fallDistance < 50.0F) {
                // Do not fast-fall into the void.
                if (new FallingPlayer(mc.thePlayer).findCollision(500) != null) {
                    ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(true);
                    MoveUtil.stopXZ();
                    mc.thePlayer.motionY = -99.887575;
                    mc.thePlayer.setSneaking(true);
                }
            }
        }
    }
}
