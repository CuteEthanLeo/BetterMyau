package myau.module.modules.longjumpmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.longjumpmodes.LongJumpMode;
import myau.util.MoveUtil;

public class AACv1 extends LongJumpMode {
    public AACv1() {
        super("AACv1");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.motionY += 0.05999;
        MoveUtil.strafe((float) MoveUtil.getSpeed() * 1.08F);
    }
}
