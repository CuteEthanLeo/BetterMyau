package myau.module.modules.flymodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.util.MoveUtil;

public class WatchCat extends FlyMode {
    public WatchCat() {
        super("WatchCat");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        double startY = this.parent.getStartY();

        MoveUtil.strafe(0.15F);
        mc.thePlayer.setSprinting(true);

        if (mc.thePlayer.posY < startY + 2.0) {
            mc.thePlayer.motionY = 0.5 * Math.random();
            return;
        }

        if (startY > mc.thePlayer.posY) {
            MoveUtil.stopXZ();
        }
    }
}
