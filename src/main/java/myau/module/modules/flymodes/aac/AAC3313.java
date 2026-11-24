package myau.module.modules.flymodes.aac;

import myau.events.UpdateEvent;
import myau.mixin.IAccessorMinecraft;
import myau.module.modules.flymodes.FlyMode;
import myau.util.MoveUtil;
import org.lwjgl.input.Keyboard;

public class AAC3313 extends FlyMode {
    private boolean wasDead = false;

    public AAC3313() {
        super("AAC3.3.13");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.isDead) {
            this.wasDead = true;
        }

        if (this.wasDead || mc.thePlayer.onGround) {
            this.wasDead = false;
            mc.thePlayer.motionY = this.parent.aacMotion2.getValue().doubleValue();
            mc.thePlayer.onGround = false;
        }

        MoveUtil.setTimer(1.0F);

        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)) {
            MoveUtil.setTimer(0.2F);
            ((IAccessorMinecraft) mc).setRightClickDelayTimer(0);
        }
    }

    @Override
    public void onDisable() {
        this.wasDead = false;
    }
}
