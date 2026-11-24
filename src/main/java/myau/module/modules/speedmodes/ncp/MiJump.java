package myau.module.modules.speedmodes.ncp;

import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;

public class MiJump extends SpeedMode {
    public MiJump() {
        super("MiJump");
    }

    @Override
    public void onMotion() {
        if (!MoveUtil.isMoving()) {
            return;
        }
        if (mc.thePlayer.onGround && !mc.thePlayer.movementInput.jump) {
            mc.thePlayer.motionY += 0.1;
            double multiplier = 1.8;
            mc.thePlayer.motionX *= multiplier;
            mc.thePlayer.motionZ *= multiplier;
            float currentSpeed = SpeedUtil.speed();
            double maxSpeed = 0.66;
            if (currentSpeed > maxSpeed) {
                mc.thePlayer.motionX = mc.thePlayer.motionX / currentSpeed * maxSpeed;
                mc.thePlayer.motionZ = mc.thePlayer.motionZ / currentSpeed * maxSpeed;
            }
        }
        MoveUtil.strafe();
    }
}
