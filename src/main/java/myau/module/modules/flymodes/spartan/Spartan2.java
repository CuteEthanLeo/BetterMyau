package myau.module.modules.flymodes.spartan;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;

public class Spartan2 extends FlyMode {
    public Spartan2() {
        super("Spartan2");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        MoveUtil.strafe(0.264F);

        if (mc.thePlayer.ticksExisted % 8 == 0) {
            FlyUtil.sendPosition(mc.thePlayer.posX, mc.thePlayer.posY + 10.0, mc.thePlayer.posZ, true);
        }
    }
}
