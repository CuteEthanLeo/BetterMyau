package myau.module.modules.flymodes.aac;

import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorMinecraft;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;
import org.lwjgl.input.Keyboard;

public class AAC3312 extends FlyMode {
    public AAC3312() {
        super("AAC3.3.12");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.posY < -70.0) {
            mc.thePlayer.motionY = this.parent.aacMotion.getValue().doubleValue();
        }

        MoveUtil.setTimer(1.0F);

        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)) {
            MoveUtil.setTimer(0.2F);
            ((IAccessorMinecraft) mc).setRightClickDelayTimer(0);
        }
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        FlyUtil.drawPlatform(-70.0, 0, 0, 255, 90, 1.0);
    }
}
