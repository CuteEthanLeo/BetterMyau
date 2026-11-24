package myau.module.modules.speedmodes.matrix;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class OldMatrixHop extends SpeedMode {
    public OldMatrixHop() {
        super("OldMatrixHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving()) {
            if (player.onGround) {
                MoveUtil.tryJump();
                SpeedUtil.setSpeedInAir(0.02098F);
                MoveUtil.setTimer(1.055F);
            } else {
                MoveUtil.strafe();
            }
        } else {
            MoveUtil.setTimer(1.0F);
        }
    }
}
