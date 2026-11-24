package myau.module.modules.speedmodes.hypixel;

import myau.events.StrafeEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class HypixelHop extends SpeedMode {
    public HypixelHop() {
        super("HypixelHop");
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid()) {
            return;
        }
        if (player.onGround && MoveUtil.isMoving()) {
            if (player.isUsingItem()) {
                MoveUtil.tryJump();
            } else {
                MoveUtil.tryJump();
                MoveUtil.strafe(0.4F);
            }
        }
    }
}
