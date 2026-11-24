package myau.module.modules.longjumpmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.longjumpmodes.LongJumpMode;
import myau.util.MoveUtil;

public class Buzz extends LongJumpMode {
    public Buzz() {
        super("Buzz");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.motionY += 0.4679942989799998;
        MoveUtil.strafe((float) MoveUtil.getSpeed() * 0.7578698F);
    }
}
