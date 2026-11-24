package myau.module.modules.flymodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;

public class Flag extends FlyMode {
    public Flag() {
        super("Flag");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;

        FlyUtil.sendPosition(
                x + mc.thePlayer.motionX * 999.0,
                y + (mc.gameSettings.keyBindJump.isKeyDown() ? 1.5624 : 0.00000001) - (mc.gameSettings.keyBindSneak.isKeyDown() ? 0.0624 : 0.00000002),
                z + mc.thePlayer.motionZ * 999.0,
                true
        );
        FlyUtil.sendPosition(
                x + mc.thePlayer.motionX * 999.0,
                y - 6969.0,
                z + mc.thePlayer.motionZ * 999.0,
                true
        );

        mc.thePlayer.setPosition(x + mc.thePlayer.motionX * 11.0, y, z + mc.thePlayer.motionZ * 11.0);
        mc.thePlayer.motionY = 0.0;
    }
}
