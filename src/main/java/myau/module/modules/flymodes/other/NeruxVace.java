package myau.module.modules.flymodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;

public class NeruxVace extends FlyMode {
    private int tick = 0;

    public NeruxVace() {
        super("NeruxVace");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!mc.thePlayer.onGround) {
            this.tick++;
        }

        if (this.tick >= this.parent.neruxVaceTicks.getValue() && !mc.thePlayer.onGround) {
            this.tick = 0;
            mc.thePlayer.motionY = 0.015;
        }
    }
}
