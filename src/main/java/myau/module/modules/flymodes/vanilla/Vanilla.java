package myau.module.modules.flymodes.vanilla;

import myau.events.MoveEvent;
import myau.mixin.IAccessorEntity;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;

public class Vanilla extends FlyMode {
    public Vanilla() {
        super("Vanilla");
    }

    @Override
    public void onMove(MoveEvent event) {
        if (mc.thePlayer == null) {
            return;
        }
        float vanillaSpeed = this.parent.vanillaSpeed.getValue();

        FlyUtil.strafe(vanillaSpeed, true, event);

        mc.thePlayer.onGround = false;
        ((IAccessorEntity) mc.thePlayer).setIsInWeb(false);

        mc.thePlayer.capabilities.isFlying = false;

        double ySpeed = 0.0;

        if (mc.gameSettings.keyBindJump.isKeyDown()) {
            ySpeed += vanillaSpeed;
        }

        if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            ySpeed -= vanillaSpeed;
        }

        mc.thePlayer.motionY = ySpeed;
        event.setY(ySpeed);

        this.parent.handleVanillaKickBypass();
    }
}
