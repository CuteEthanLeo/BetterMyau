package myau.module.modules.speedmodes.rise;

import myau.event.types.EventType;
import myau.events.JumpEvent;
import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;

public class Polar extends SpeedMode {
    private int jumps = 0;

    public Polar() {
        super("Polar");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        MoveUtil.setTimer(1.009F);
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        if (MoveUtil.airTicks == 5 && this.jumps % 2 != 0) {
            mc.thePlayer.motionY -= 0.03;
        }
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
        }
        SpeedUtil.moveFlying(0.002);
    }

    /**
     * Counts each jump once. xw counted the PRE and the POST event, so the counter was always
     * even and the "every second jump" dip above never ran.
     */
    @Override
    public void onJump(JumpEvent event) {
        if (event.getType() == EventType.PRE) {
            this.jumps++;
        }
    }

    @Override
    public void onDisable() {
        MoveUtil.setTimer(1.0F);
    }
}
