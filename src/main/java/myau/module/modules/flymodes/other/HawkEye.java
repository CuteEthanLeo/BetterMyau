package myau.module.modules.flymodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;

public class HawkEye extends FlyMode {
    public HawkEye() {
        super("HawkEye");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.motionY = mc.thePlayer.motionY <= -0.42 ? 0.42 : -0.42;
    }
}
