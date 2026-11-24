package myau.module.modules.nowebmodes.intave;

import myau.events.UpdateEvent;
import myau.module.modules.nowebmodes.NoWebMode;

public class IntaveOld extends NoWebMode {
    public IntaveOld() {
        super("IntaveOld");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!isInWeb()) {
            return;
        }
        if (mc.thePlayer.movementInput.moveStrafe == 0.0F && mc.gameSettings.keyBindForward.isKeyDown() && mc.thePlayer.isCollidedVertically) {
            mc.thePlayer.jumpMovementFactor = 0.74F;
        } else {
            mc.thePlayer.jumpMovementFactor = 0.2F;
            mc.thePlayer.onGround = true;
        }
    }
}
