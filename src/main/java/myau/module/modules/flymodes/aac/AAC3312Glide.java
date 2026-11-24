package myau.module.modules.flymodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.util.MoveUtil;

public class AAC3312Glide extends FlyMode {
    private int tick = 0;

    public AAC3312Glide() {
        super("AAC3.3.12-Glide");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!mc.thePlayer.onGround) {
            this.tick++;
        }

        if (this.tick == 2) {
            MoveUtil.setTimer(1.0F);
        } else if (this.tick == 12) {
            MoveUtil.setTimer(0.1F);
        } else if (this.tick >= 12 && !mc.thePlayer.onGround) {
            this.tick = 0;
            mc.thePlayer.motionY = 0.015;
        }
    }
}
