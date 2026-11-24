package myau.module.modules.speedmodes.spectre;

import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;

public class SpectreLowHop extends SpeedMode {
    public SpectreLowHop() {
        super("SpectreLowHop");
    }

    @Override
    public void onMotion() {
        if (!MoveUtil.isMoving() || mc.thePlayer.movementInput.jump) {
            return;
        }
        if (mc.thePlayer.onGround) {
            MoveUtil.strafe(1.1F);
            mc.thePlayer.motionY = 0.15;
            return;
        }
        MoveUtil.strafe();
    }
}
