package myau.module.modules.speedmodes.other;

import myau.events.MoveEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;
import myau.util.TimerUtil;

public class TeleportCubeCraft extends SpeedMode {
    private final TimerUtil timer = new TimerUtil();

    public TeleportCubeCraft() {
        super("TeleportCubeCraft");
    }

    @Override
    public void onMove(MoveEvent event) {
        if (MoveUtil.isMoving() && mc.thePlayer.onGround && this.timer.hasTimeElapsed(300L)) {
            double yaw = MoveUtil.getDirection();
            float length = parent.cubecraftPortLength.getValue();
            event.setX(-Math.sin(yaw) * (double) length);
            event.setZ(Math.cos(yaw) * (double) length);
            this.timer.reset();
        }
    }
}
