package myau.module.modules.speedmodes.intave;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Working on Intave 14. Credit: thatonecoder and larryngton.
 */
public class IntaveHop14 extends SpeedMode {
    private static final double BOOST_CONSTANT = 0.003;

    public IntaveHop14() {
        super("IntaveHop14");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (!MoveUtil.isMoving() || SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (player.onGround) {
            player.motionY = 0.42 - (parent.intaveLowHop.getValue() ? 1.7E-14 : 0.0);
            if (player.isSprinting()) {
                MoveUtil.strafe(SpeedUtil.speed(), (double) parent.intaveStrafeStrength.getValue());
            }
            MoveUtil.setTimer(parent.intaveGroundTimer.getValue());
        } else {
            MoveUtil.setTimer(parent.intaveAirTimer.getValue());
        }
        if (parent.intaveBoost.getValue() && player.motionY > 0.003 && player.isSprinting()) {
            double factor = 1.0 + BOOST_CONSTANT * (double) parent.intaveInitialBoostMultiplier.getValue();
            player.motionX *= factor;
            player.motionZ *= factor;
        }
    }
}
