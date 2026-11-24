package myau.module.modules.nowebmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.nowebmodes.NoWebMode;

public class AAC extends NoWebMode {
    public AAC() {
        super("AAC");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!isInWeb()) {
            return;
        }
        mc.thePlayer.jumpMovementFactor = 0.59F;
        if (!mc.gameSettings.keyBindSneak.isKeyDown()) {
            mc.thePlayer.motionY = 0.0;
        }
    }
}
