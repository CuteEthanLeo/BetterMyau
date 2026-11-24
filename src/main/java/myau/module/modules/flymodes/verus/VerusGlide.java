package myau.module.modules.flymodes.verus;

import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntity;
import myau.module.modules.flymodes.FlyMode;
import myau.util.MoveUtil;

/**
 * Working on Verus b3896 / b3901.
 * Tested on eu.loyisa.cn and anticheat-test.com.
 */
public class VerusGlide extends FlyMode {
    public VerusGlide() {
        super("VerusGlide");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer == null) {
            return;
        }
        if (MoveUtil.isInLiquid() || ((IAccessorEntity) mc.thePlayer).getIsInWeb() || mc.thePlayer.isOnLadder()) {
            return;
        }

        if (!mc.thePlayer.onGround && mc.thePlayer.fallDistance > 1.0F) {
            // Good job, Verus
            mc.thePlayer.motionY = -0.09800000190734863;
            if (mc.thePlayer.movementInput.moveForward != 0.0F && mc.thePlayer.movementInput.moveStrafe != 0.0F) {
                MoveUtil.strafe(0.334F);
            } else {
                MoveUtil.strafe(0.3345F);
            }
        }
    }
}
