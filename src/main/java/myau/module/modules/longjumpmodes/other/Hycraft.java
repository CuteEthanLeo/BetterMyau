package myau.module.modules.longjumpmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.longjumpmodes.LongJumpMode;

public class Hycraft extends LongJumpMode {
    public Hycraft() {
        super("Hycraft");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.motionY < 0.0) {
            mc.thePlayer.motionY *= 0.75F;
            mc.thePlayer.jumpMovementFactor = 0.055F;
        } else {
            mc.thePlayer.motionY += 0.02F;
            mc.thePlayer.jumpMovementFactor = 0.08F;
        }
    }
}
