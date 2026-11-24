package myau.module.modules.flymodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;

public class AAC316 extends FlyMode {
    private int tick = 0;
    private boolean noFlag = false;

    public AAC316() {
        super("AAC3.1.6-Gomme");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.capabilities.isFlying = true;

        if (this.tick == 2) {
            mc.thePlayer.motionY += 0.05;
        } else if (this.tick > 2) {
            mc.thePlayer.motionY -= 0.05;
            this.tick = 0;
        }

        this.tick++;

        if (!this.noFlag) {
            FlyUtil.sendPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.onGround);
        }

        if (mc.thePlayer.posY <= 0.0) {
            this.noFlag = true;
        }
    }

    @Override
    public void onDisable() {
        this.noFlag = false;
    }
}
