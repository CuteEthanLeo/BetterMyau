package myau.module.modules.speedmodes.other;

import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class SlowHop extends SpeedMode {
    public SlowHop() {
        super("SlowHop");
    }

    @Override
    public void onMotion() {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving()) {
            if (player.onGround) {
                MoveUtil.tryJump();
            } else {
                MoveUtil.strafe(SpeedUtil.speed() * 1.011F);
            }
        } else {
            player.motionX = 0.0;
            player.motionZ = 0.0;
        }
    }
}
