package myau.module.modules.speedmodes.spartan;

import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;

public class SpartanYPort extends SpeedMode {
    private int airMoves = 0;

    public SpartanYPort() {
        super("SpartanYPort");
    }

    @Override
    public void onMotion() {
        if (mc.gameSettings.keyBindForward.isKeyDown()) {
            if (mc.thePlayer.onGround) {
                MoveUtil.tryJump();
                this.airMoves = 0;
            } else {
                MoveUtil.setTimer(1.08F);
                if (this.airMoves >= 3) {
                    mc.thePlayer.jumpMovementFactor = 0.0275F;
                }
                if (this.airMoves >= 4 && this.airMoves % 2 == 0) {
                    mc.thePlayer.motionY = -0.32 - 0.009 * Math.random();
                    mc.thePlayer.jumpMovementFactor = 0.0238F;
                }
                this.airMoves++;
            }
        }
    }
}
