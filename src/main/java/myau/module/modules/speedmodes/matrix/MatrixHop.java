package myau.module.modules.speedmodes.matrix;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Working on Matrix 7.11.8. Credit: EclipsesDev.
 */
public class MatrixHop extends SpeedMode {
    public MatrixHop() {
        super("MatrixHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        boolean lowHop = parent.matrixLowHop.getValue();
        if (lowHop) {
            player.jumpMovementFactor = 0.026F;
        }
        if (MoveUtil.isMoving()) {
            boolean scaffold = SpeedUtil.scaffoldEnabled();
            if (player.onGround) {
                MoveUtil.strafe(!scaffold ? SpeedUtil.speed() + parent.matrixExtraGroundBoost.getValue() : SpeedUtil.speed());
                player.motionY = 0.42 - (lowHop ? 3.48E-3 : 0.0);
            } else if (!scaffold && SpeedUtil.speed() < 0.19) {
                MoveUtil.strafe();
            }
            if (player.fallDistance <= 0.4 && player.moveStrafing == 0.0F) {
                SpeedUtil.setSpeedInAir(0.02035F);
            } else {
                SpeedUtil.setSpeedInAir(0.02F);
            }
        }
    }
}
