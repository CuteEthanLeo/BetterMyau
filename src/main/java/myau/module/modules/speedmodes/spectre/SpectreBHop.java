package myau.module.modules.speedmodes.spectre;

import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;

public class SpectreBHop extends SpeedMode {
    public SpectreBHop() {
        super("SpectreBHop");
    }

    @Override
    public void onMotion() {
        if (!MoveUtil.isMoving() || mc.thePlayer.movementInput.jump) {
            return;
        }
        if (mc.thePlayer.onGround) {
            MoveUtil.strafe(1.1F);
            mc.thePlayer.motionY = 0.44;
            return;
        }
        MoveUtil.strafe();
    }
}
