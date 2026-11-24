package myau.module.modules.nowebmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.nowebmodes.NoWebMode;
import myau.util.MoveUtil;

public class LAAC extends NoWebMode {
    public LAAC() {
        super("LAAC");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!isInWeb()) {
            return;
        }
        mc.thePlayer.jumpMovementFactor = mc.thePlayer.movementInput.moveStrafe != 0.0F ? 1.0F : 1.21F;
        if (!mc.gameSettings.keyBindSneak.isKeyDown()) {
            mc.thePlayer.motionY = 0.0;
        }
        if (mc.thePlayer.onGround) {
            MoveUtil.tryJump();
        }
    }
}
