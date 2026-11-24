package myau.module.modules.speedmodes.ncp;

import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class OnGround extends SpeedMode {
    public OnGround() {
        super("OnGround");
    }

    @Override
    public void onMotion() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || !MoveUtil.isMoving()) {
            return;
        }
        if (player.fallDistance > 3.994) {
            return;
        }
        if (player.isInWater() || player.isOnLadder() || player.isCollidedHorizontally) {
            return;
        }
        player.posY -= 0.3993000090122223;
        player.motionY = -1000.0;
        player.cameraPitch = 0.3F;
        player.distanceWalkedModified = 44.0F;
        MoveUtil.setTimer(1.0F);
        if (player.onGround) {
            player.posY += 0.3993000090122223;
            player.motionY = 0.3993000090122223;
            player.distanceWalkedOnStepModified = 44.0F;
            player.motionX *= 1.590000033378601;
            player.motionZ *= 1.590000033378601;
            player.cameraPitch = 0.0F;
            MoveUtil.setTimer(1.199F);
        }
    }
}
