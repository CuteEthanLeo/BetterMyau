package myau.module.modules.longjumpmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.longjumpmodes.LongJumpMode;
import myau.util.MoveUtil;

public class AACv2 extends LongJumpMode {
    public AACv2() {
        super("AACv2");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.jumpMovementFactor = 0.09F;
        mc.thePlayer.motionY += 0.01320999999999999;
        mc.thePlayer.jumpMovementFactor = 0.08F;
        MoveUtil.strafe();
    }
}
