package myau.module.modules.flymodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C00PacketKeepAlive;

public class KeepAlive extends FlyMode {
    public KeepAlive() {
        super("KeepAlive");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        float vanillaSpeed = this.parent.vanillaSpeed.getValue();

        PacketUtil.sendPacket(new C00PacketKeepAlive());
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
