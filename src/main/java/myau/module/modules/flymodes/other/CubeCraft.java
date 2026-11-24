package myau.module.modules.flymodes.other;

import myau.events.MoveEvent;
import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.util.MoveUtil;
import myau.util.TickTimer;

public class CubeCraft extends FlyMode {
    private final TickTimer tickTimer = new TickTimer();

    public CubeCraft() {
        super("CubeCraft");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        MoveUtil.setTimer(0.6F);
        this.tickTimer.update();
    }

    @Override
    public void onMove(MoveEvent event) {
        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);

        if (this.tickTimer.hasTimePassed(2)) {
            event.setX(-Math.sin(yaw) * 2.4);
            event.setZ(Math.cos(yaw) * 2.4);
            this.tickTimer.reset();
        } else {
            event.setX(-Math.sin(yaw) * 0.2);
            event.setZ(Math.cos(yaw) * 0.2);
        }
    }
}
