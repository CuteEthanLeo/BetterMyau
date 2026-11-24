package myau.module.modules.flymodes.vulcan;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;

public class Vulcan extends FlyMode {
    public Vulcan() {
        super("Vulcan");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!mc.thePlayer.onGround && mc.thePlayer.fallDistance > 0.0F) {
            if (mc.thePlayer.ticksExisted % 2 == 0) {
                mc.thePlayer.motionY = -0.155;
            } else {
                mc.thePlayer.motionY = -0.1;
            }
        }
    }
}
