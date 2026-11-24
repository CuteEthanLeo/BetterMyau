package myau.module.modules.speedmodes.ncp;

import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class Boost extends SpeedMode {
    private int motionDelay = 0;
    private float ground = 0.0F;

    public Boost() {
        super("Boost");
    }

    @Override
    public void onMotion() {
        EntityPlayerSP player = mc.thePlayer;
        double speed = 3.1981;
        double offset = 4.69;
        boolean shouldOffset = true;
        if (!mc.theWorld.getCollidingBoundingBoxes(
                player, player.getEntityBoundingBox().offset(player.motionX / offset, 0.0, player.motionZ / offset)
        ).isEmpty()) {
            shouldOffset = false;
        }
        if (player.onGround && this.ground < 1.0F) {
            this.ground += 0.2F;
        }
        if (!player.onGround) {
            this.ground = 0.0F;
        }
        if (this.ground == 1.0F && this.shouldSpeedUp()) {
            if (!player.isSprinting()) {
                offset += 0.8;
            }
            if (player.moveStrafing != 0.0F) {
                speed -= 0.1;
                offset += 0.5;
            }
            if (player.isInWater()) {
                speed -= 0.1;
            }
            this.motionDelay += 1;
            switch (this.motionDelay) {
                case 1:
                    player.motionX *= speed;
                    player.motionZ *= speed;
                    break;
                case 2:
                    player.motionX /= 1.458;
                    player.motionZ /= 1.458;
                    break;
                case 4:
                    if (shouldOffset) {
                        player.setPosition(
                                player.posX + player.motionX / offset,
                                player.posY,
                                player.posZ + player.motionZ / offset
                        );
                    }
                    this.motionDelay = 0;
                    break;
                default:
                    break;
            }
        }
    }

    private boolean shouldSpeedUp() {
        return !mc.thePlayer.isInLava() && !mc.thePlayer.isOnLadder() && !mc.thePlayer.isSneaking() && MoveUtil.isMoving();
    }
}
