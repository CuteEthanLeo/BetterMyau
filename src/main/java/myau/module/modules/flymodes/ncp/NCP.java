package myau.module.modules.flymodes.ncp;

import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;
import net.minecraft.network.play.client.C03PacketPlayer;

public class NCP extends FlyMode {
    public NCP() {
        super("NCP");
    }

    @Override
    public void onEnable() {
        if (!mc.thePlayer.onGround) {
            return;
        }

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
        mc.thePlayer.motionY = mc.gameSettings.keyBindSneak.isKeyDown() ? -0.5 : -this.parent.ncpMotion.getValue().doubleValue();

        MoveUtil.strafe();
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C03PacketPlayer) {
            ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(true);
        }
    }
}
