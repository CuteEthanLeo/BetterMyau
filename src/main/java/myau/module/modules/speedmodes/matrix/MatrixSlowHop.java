package myau.module.modules.speedmodes.matrix;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Working on Matrix 7.11.8. Credit: EclipsesDev.
 */
public class MatrixSlowHop extends SpeedMode {
    public MatrixSlowHop() {
        super("MatrixSlowHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving()) {
            if (!player.onGround && player.fallDistance > 2.0F) {
                MoveUtil.setTimer(1.0F);
                return;
            }
            if (player.onGround) {
                player.motionY = 0.42 - (parent.matrixLowHop.getValue() ? 3.48E-3 : 0.0);
                MoveUtil.setTimer(0.5195F);
                MoveUtil.strafe(SpeedUtil.speed() + parent.matrixExtraGroundBoost.getValue());
            } else {
                MoveUtil.setTimer(1.0973F);
            }
            if (player.fallDistance <= 0.4 && player.moveStrafing == 0.0F) {
                SpeedUtil.setSpeedInAir(0.02035F);
            } else {
                SpeedUtil.setSpeedInAir(0.02F);
            }
        } else {
            MoveUtil.setTimer(1.0F);
        }
    }
}
