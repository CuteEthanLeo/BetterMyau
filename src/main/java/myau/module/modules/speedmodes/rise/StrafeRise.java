package myau.module.modules.speedmodes.rise;

import myau.events.StrafeEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;

public class StrafeRise extends SpeedMode {
    public StrafeRise() {
        super("StrafeRise");
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        if (!MoveUtil.isMoving()) {
            MoveUtil.stopXZ();
        } else {
            if (mc.thePlayer.onGround) {
                mc.thePlayer.jump();
            }
            if (parent.strafeRiseHurtBoost.getValue() && mc.thePlayer.hurtTime == 9) {
                MoveUtil.strafe(parent.strafeRiseBoostSpeed.getValue());
            }
            MoveUtil.strafe();
        }
    }
}
