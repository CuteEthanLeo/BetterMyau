package myau.module.modules.speedmodes.verus;

import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class VerusFHop extends SpeedMode {
    public VerusFHop() {
        super("VerusFHop");
    }

    @Override
    public void onMotion() {
        EntityPlayerSP player = mc.thePlayer;
        boolean diagonal = player.movementInput.moveForward != 0.0F && player.movementInput.moveStrafe != 0.0F;
        if (player.onGround) {
            MoveUtil.strafe(diagonal ? 0.4825F : 0.535F);
            MoveUtil.tryJump();
        } else {
            MoveUtil.strafe(diagonal ? 0.334F : 0.3345F);
        }
    }
}
