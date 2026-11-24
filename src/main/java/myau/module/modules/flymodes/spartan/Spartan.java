package myau.module.modules.flymodes.spartan;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.TickTimer;

public class Spartan extends FlyMode {
    private final TickTimer timer = new TickTimer();

    public Spartan() {
        super("Spartan");
    }

    @Override
    public void onEnable() {
        this.timer.reset();
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.motionY = 0.0;

        this.timer.update();
        if (this.timer.hasTimePassed(12)) {
            FlyUtil.sendPosition(mc.thePlayer.posX, mc.thePlayer.posY + 8.0, mc.thePlayer.posZ, true);
            FlyUtil.sendPosition(mc.thePlayer.posX, mc.thePlayer.posY - 8.0, mc.thePlayer.posZ, true);
            this.timer.reset();
        }
    }
}
