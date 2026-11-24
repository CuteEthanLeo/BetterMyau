package myau.module.modules.flymodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.TimerUtil;

public class MineSecure extends FlyMode {
    private final TimerUtil timer = new TimerUtil();

    public MineSecure() {
        super("MineSecure");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        mc.thePlayer.capabilities.isFlying = false;

        mc.thePlayer.motionY = mc.gameSettings.keyBindSneak.isKeyDown() ? 0.0 : -0.01;

        FlyUtil.strafe(this.parent.vanillaSpeed.getValue(), true);

        if (!this.timer.hasTimeElapsed(150L) || !mc.gameSettings.keyBindJump.isKeyDown()) {
            return;
        }

        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;

        FlyUtil.sendPosition(x, y + 5.0, z, false);
        FlyUtil.sendPosition(0.5, -1000.0, 0.5, false);

        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);

        mc.thePlayer.setPosition(x - Math.sin(yaw) * 0.4, y, z + Math.cos(yaw) * 0.4);
        this.timer.reset();
    }
}
