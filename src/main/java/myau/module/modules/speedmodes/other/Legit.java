package myau.module.modules.speedmodes.other;

import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;

/** Plain auto-jump; the only mode that handles sprinting by itself. */
public class Legit extends SpeedMode {
    public Legit() {
        super("Legit");
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        if (mc.thePlayer.onGround && MoveUtil.isMoving()) {
            MoveUtil.tryJump();
        }
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.setSprinting(mc.thePlayer.movementInput.moveForward > 0.8);
    }
}
