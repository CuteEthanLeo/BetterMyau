package myau.module.modules.speedmodes.ncp;

import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;
import myau.util.TickTimer;

public class Frame extends SpeedMode {
    private int motionTicks = 0;
    private boolean move = false;
    private final TickTimer tickTimer = new TickTimer();

    public Frame() {
        super("Frame");
    }

    @Override
    public void onMotion() {
        if (MoveUtil.isMoving()) {
            double speed = 4.25;
            if (mc.thePlayer.onGround) {
                MoveUtil.tryJump();
                if (this.motionTicks == 1) {
                    this.tickTimer.reset();
                    if (this.move) {
                        mc.thePlayer.motionX = 0.0;
                        mc.thePlayer.motionZ = 0.0;
                        this.move = false;
                    }
                    this.motionTicks = 0;
                } else {
                    this.motionTicks = 1;
                }
            } else if (!this.move && this.motionTicks == 1 && this.tickTimer.hasTimePassed(5)) {
                mc.thePlayer.motionX *= speed;
                mc.thePlayer.motionZ *= speed;
                this.move = true;
            }
            if (!mc.thePlayer.onGround) {
                MoveUtil.strafe();
            }
            this.tickTimer.update();
        }
    }
}
