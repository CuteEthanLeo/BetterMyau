package myau.module.modules.nowebmodes.intave;

import myau.events.UpdateEvent;
import myau.module.modules.nowebmodes.NoWebMode;
import myau.util.MoveUtil;

public class IntaveNew extends NoWebMode {
    public IntaveNew() {
        super("IntaveNew");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!isInWeb()) {
            return;
        }
        if (MoveUtil.isMoving() && mc.thePlayer.moveStrafing == 0.0F) {
            if (mc.thePlayer.onGround) {
                if (mc.thePlayer.ticksExisted % 3 == 0) {
                    MoveUtil.strafe(0.734F);
                } else {
                    MoveUtil.tryJump();
                    MoveUtil.strafe(0.346F);
                }
            }
        }
    }
}
