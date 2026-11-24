package myau.module.modules.speedmodes.aac;

import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class AACHop350 extends SpeedMode {
    public AACHop350() {
        super("AACHop3.5.0");
    }

    /**
     * Runs after the movement packet (MotionEvent POST). In xw this handler lost its event
     * registration and never ran; here it is wired up again as in the LiquidBounce original.
     */
    @Override
    public void onPostMotion() {
        EntityPlayerSP player = mc.thePlayer;
        if (MoveUtil.isMoving() && !SpeedUtil.isInLiquid() && !player.isSneaking()) {
            player.jumpMovementFactor += 0.00208F;
            if (player.fallDistance <= 1.0F) {
                if (player.onGround) {
                    MoveUtil.tryJump();
                    player.motionX *= 1.0118F;
                    player.motionZ *= 1.0118F;
                } else {
                    player.motionY -= 0.0147F;
                    player.motionX *= 1.00138F;
                    player.motionZ *= 1.00138F;
                }
            }
        }
    }

    @Override
    public void onEnable() {
        if (mc.thePlayer.onGround) {
            mc.thePlayer.motionX = 0.0;
            mc.thePlayer.motionZ = 0.0;
        }
    }

    @Override
    public void onDisable() {
        mc.thePlayer.jumpMovementFactor = 0.02F;
    }
}
