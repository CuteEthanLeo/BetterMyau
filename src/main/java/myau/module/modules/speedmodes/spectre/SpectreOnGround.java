package myau.module.modules.speedmodes.spectre;

import myau.events.MoveEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;

public class SpectreOnGround extends SpeedMode {
    private int speedUp = 0;

    public SpectreOnGround() {
        super("SpectreOnGround");
    }

    @Override
    public void onMove(MoveEvent event) {
        if (!MoveUtil.isMoving() || mc.thePlayer.movementInput.jump) {
            return;
        }
        if (this.speedUp >= 10) {
            if (mc.thePlayer.onGround) {
                mc.thePlayer.motionX = 0.0;
                mc.thePlayer.motionZ = 0.0;
                this.speedUp = 0;
            }
            return;
        }
        if (mc.thePlayer.onGround && mc.gameSettings.keyBindForward.isKeyDown()) {
            float f = mc.thePlayer.rotationYaw * 0.017453292F;
            mc.thePlayer.motionX -= (float) Math.sin(f) * 0.145F;
            mc.thePlayer.motionZ += (float) Math.cos(f) * 0.145F;
            event.setX(mc.thePlayer.motionX);
            event.setY(0.005);
            event.setZ(mc.thePlayer.motionZ);
            this.speedUp++;
        }
    }
}
