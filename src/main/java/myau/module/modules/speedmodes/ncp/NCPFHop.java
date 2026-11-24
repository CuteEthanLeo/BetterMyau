package myau.module.modules.speedmodes.ncp;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;

public class NCPFHop extends SpeedMode {
    public NCPFHop() {
        super("NCPFHop");
    }

    @Override
    public void onEnable() {
        MoveUtil.setTimer(1.0866F);
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
                mc.thePlayer.motionX *= 1.01;
                mc.thePlayer.motionZ *= 1.01;
                SpeedUtil.setSpeedInAir(0.0223F);
            }
            mc.thePlayer.motionY -= 0.00099999;
            MoveUtil.strafe();
        } else {
            mc.thePlayer.motionX = 0.0;
            mc.thePlayer.motionZ = 0.0;
        }
    }
}
