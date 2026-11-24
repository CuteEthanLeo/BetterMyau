package myau.module.modules.speedmodes.vulcan;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class VulcanLowHop extends SpeedMode {
    public VulcanLowHop() {
        super("VulcanLowHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving()) {
            if (!player.onGround && player.fallDistance > 1.1) {
                MoveUtil.setTimer(1.0F);
                player.motionY = -0.25;
                return;
            }
            if (player.onGround) {
                MoveUtil.tryJump();
                MoveUtil.strafe(0.4815F);
                MoveUtil.setTimer(1.263F);
            } else if (player.ticksExisted % 4 == 0) {
                if (player.ticksExisted % 3 == 0) {
                    player.motionY = -0.01 / player.motionY;
                } else {
                    player.motionY = -player.motionY / player.posY;
                }
                MoveUtil.setTimer(0.8985F);
            }
        } else {
            MoveUtil.setTimer(1.0F);
        }
    }
}
