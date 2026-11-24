package myau.module.modules.flymodes.vulcan;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;

public class VulcanOld extends FlyMode {
    public VulcanOld() {
        super("VulcanOld");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!mc.thePlayer.onGround && mc.thePlayer.fallDistance > 0.0F) {
            if (mc.thePlayer.ticksExisted % 2 == 0) {
                mc.thePlayer.motionY = -0.1;
                mc.thePlayer.jumpMovementFactor = 0.0265F;
            } else {
                mc.thePlayer.motionY = -0.16;
                mc.thePlayer.jumpMovementFactor = 0.0265F;
            }
        }
    }
}
