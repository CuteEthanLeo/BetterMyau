package myau.module.modules.speedmodes.ncp;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;

public class NCPHop extends SpeedMode {
    public NCPHop() {
        super("NCPHop");
    }

    @Override
    public void onEnable() {
        MoveUtil.setTimer(1.0865F);
    }

    @Override
    public void onDisable() {
        SpeedUtil.setSpeedInAir(0.02F);
        MoveUtil.setTimer(1.0F);
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (MoveUtil.isMoving()) {
            if (mc.thePlayer.onGround) {
                MoveUtil.tryJump();
                SpeedUtil.setSpeedInAir(0.0223F);
            }
            MoveUtil.strafe();
        } else {
            mc.thePlayer.motionX = 0.0;
            mc.thePlayer.motionZ = 0.0;
        }
    }
}
