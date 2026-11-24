package myau.module.modules.flymodes.aac;

import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C03PacketPlayer;

public class AAC1910 extends FlyMode {
    private double jump = 0.0;

    public AAC1910() {
        super("AAC1.9.10");
    }

    @Override
    public void onEnable() {
        this.jump = 3.8;
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.gameSettings.keyBindJump.isKeyDown()) {
            this.jump += 0.2;
        }

        if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            this.jump -= 0.2;
        }

        if (this.parent.getStartY() + this.jump > mc.thePlayer.posY) {
            PacketUtil.sendPacket(new C03PacketPlayer(true));
            mc.thePlayer.motionY = 0.8;
            MoveUtil.strafe(this.parent.aacSpeed.getValue());
        }

        // Same as the original: this re-strafes with the speed that was just set.
        MoveUtil.strafe();
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        FlyUtil.drawPlatform(this.parent.getStartY() + this.jump, 0, 0, 255, 90, 1.0);
    }
}
