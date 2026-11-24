package myau.module.modules.speedmodes.vulcan;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class VulcanHop extends SpeedMode {
    public VulcanHop() {
        super("VulcanHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving()) {
            if (player.isAirBorne && player.fallDistance > 2.0F) {
                MoveUtil.setTimer(1.0F);
                return;
            }
            if (player.onGround) {
                MoveUtil.tryJump();
                if (player.motionY > 0.0) {
                    MoveUtil.setTimer(1.1453F);
                }
                MoveUtil.strafe(0.4815F);
            } else if (player.motionY < 0.0) {
                MoveUtil.setTimer(0.9185F);
            }
        } else {
            MoveUtil.setTimer(1.0F);
        }
    }
}
