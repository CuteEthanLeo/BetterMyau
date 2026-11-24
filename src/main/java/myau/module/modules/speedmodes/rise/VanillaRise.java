package myau.module.modules.speedmodes.rise;

import myau.events.StrafeEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;

public class VanillaRise extends SpeedMode {
    public VanillaRise() {
        super("VanillaRise");
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        if (MoveUtil.isMoving() && mc.thePlayer.onGround) {
            mc.thePlayer.jump();
        }
        MoveUtil.strafe(parent.vanillaRiseSpeed.getValue());
    }
}
