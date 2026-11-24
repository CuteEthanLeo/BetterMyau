package myau.module.modules.nowebmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.nowebmodes.NoWebMode;
import myau.util.MoveUtil;

public class Rewi extends NoWebMode {
    public Rewi() {
        super("Rewi");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!isInWeb()) {
            return;
        }
        mc.thePlayer.jumpMovementFactor = 0.42F;
        if (mc.thePlayer.onGround) {
            MoveUtil.tryJump();
        }
    }
}
