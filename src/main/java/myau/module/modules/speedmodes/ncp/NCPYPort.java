package myau.module.modules.speedmodes.ncp;

import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;

public class NCPYPort extends SpeedMode {
    private int jumps = 0;

    public NCPYPort() {
        super("NCPYPort");
    }

    @Override
    public void onMotion() {
        if (mc.thePlayer.isOnLadder() || SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || !MoveUtil.isMoving() || mc.thePlayer.isInWater()) {
            return;
        }
        if (this.jumps >= 4 && mc.thePlayer.onGround) {
            this.jumps = 0;
        }
        if (mc.thePlayer.onGround) {
            mc.thePlayer.motionY = this.jumps <= 1 ? 0.42 : 0.4;
            float f = mc.thePlayer.rotationYaw * 0.017453292F;
            mc.thePlayer.motionX -= (float) Math.sin(f) * 0.2F;
            mc.thePlayer.motionZ += (float) Math.cos(f) * 0.2F;
            this.jumps++;
        } else if (this.jumps <= 1) {
            mc.thePlayer.motionY = -5.0;
        }
        MoveUtil.strafe();
    }
}
