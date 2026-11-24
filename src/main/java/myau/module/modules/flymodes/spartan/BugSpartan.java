package myau.module.modules.flymodes.spartan;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;

public class BugSpartan extends FlyMode {
    public BugSpartan() {
        super("BugSpartan");
    }

    @Override
    public void onEnable() {
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;

        for (int i = 0; i < 65; i++) {
            FlyUtil.sendPosition(x, y + 0.049, z, false);
            FlyUtil.sendPosition(x, y, z, false);
        }

        FlyUtil.sendPosition(x, y + 0.1, z, true);

        mc.thePlayer.motionX *= 0.1;
        mc.thePlayer.motionZ *= 0.1;
        mc.thePlayer.swingItem();
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        float vanillaSpeed = this.parent.vanillaSpeed.getValue();

        mc.thePlayer.capabilities.isFlying = false;

        if (mc.gameSettings.keyBindJump.isKeyDown()) {
            mc.thePlayer.motionY = vanillaSpeed;
        } else if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            mc.thePlayer.motionY = -vanillaSpeed;
        } else {
            mc.thePlayer.motionY = 0.0;
        }

        FlyUtil.strafe(vanillaSpeed, true);
    }
}
