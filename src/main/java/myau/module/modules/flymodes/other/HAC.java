package myau.module.modules.flymodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;

public class HAC extends FlyMode {
    public HAC() {
        super("HAC");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.motionX *= 0.8;
        mc.thePlayer.motionZ *= 0.8;
        mc.thePlayer.motionY = mc.thePlayer.motionY <= -0.42 ? 0.42 : -0.42;
    }
}
