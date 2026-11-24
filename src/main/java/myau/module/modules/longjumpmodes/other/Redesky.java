package myau.module.modules.longjumpmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.longjumpmodes.LongJumpMode;

public class Redesky extends LongJumpMode {
    public Redesky() {
        super("Redesky");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.jumpMovementFactor = 0.15F;
        mc.thePlayer.motionY += 0.05F;
    }
}
