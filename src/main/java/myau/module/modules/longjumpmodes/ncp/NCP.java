package myau.module.modules.longjumpmodes.ncp;

import myau.events.MoveEvent;
import myau.events.UpdateEvent;
import myau.module.modules.longjumpmodes.LongJumpMode;
import myau.util.MoveUtil;

public class NCP extends LongJumpMode {
    public NCP() {
        super("NCP");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        MoveUtil.strafe((float) MoveUtil.getSpeed() * (this.parent.canBoost ? this.parent.ncpBoost.getValue() : 1.0F));
        this.parent.canBoost = false;
    }

    @Override
    public void onMove(MoveEvent event) {
        if (!MoveUtil.isMoving() && this.parent.jumped) {
            mc.thePlayer.motionX = 0.0;
            mc.thePlayer.motionZ = 0.0;
            event.zeroXZ();
        }
    }
}
