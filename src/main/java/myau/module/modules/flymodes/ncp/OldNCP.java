package myau.module.modules.flymodes.ncp;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;

public class OldNCP extends FlyMode {
    public OldNCP() {
        super("OldNCP");
    }

    @Override
    public void onEnable() {
        if (!mc.thePlayer.onGround) {
            return;
        }

        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;

        for (int i = 0; i < 4; i++) {
            FlyUtil.sendPosition(x, y + 1.01, z, false);
            FlyUtil.sendPosition(x, y, z, false);
        }

        MoveUtil.tryJump();
        mc.thePlayer.swingItem();
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        double startY = this.parent.getStartY();

        if (startY > mc.thePlayer.posY) {
            mc.thePlayer.motionY = -0.000000000000000000000000000000001;
        }

        if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            mc.thePlayer.motionY = -0.2;
        }

        if (mc.gameSettings.keyBindJump.isKeyDown() && mc.thePlayer.posY < startY - 0.1) {
            mc.thePlayer.motionY = 0.2;
        }

        MoveUtil.strafe();
    }
}
