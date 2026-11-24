package myau.module.modules.flymodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;

public class AAC305 extends FlyMode {
    private int tick = 0;

    public AAC305() {
        super("AAC3.0.5");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (this.tick == 2) {
            mc.thePlayer.motionY = 0.1;
        } else if (this.tick > 2) {
            this.tick = 0;
        }

        if (this.parent.aacFast.getValue()) {
            if (mc.thePlayer.movementInput.moveStrafe == 0.0F) {
                mc.thePlayer.jumpMovementFactor = 0.08F;
            } else {
                mc.thePlayer.jumpMovementFactor = 0.0F;
            }
        }

        this.tick++;
    }
}
