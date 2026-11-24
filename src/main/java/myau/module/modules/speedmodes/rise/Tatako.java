package myau.module.modules.speedmodes.rise;

import myau.events.StrafeEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class Tatako extends SpeedMode {
    private int strafeTicks = 0;

    public Tatako() {
        super("Tatako");
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        int airTicks = MoveUtil.airTicks;
        if (MoveUtil.isMoving() && player.onGround) {
            player.jump();
            this.strafeTicks++;
        }
        if (player.onGround) {
            SpeedUtil.moveFlying(0.005);
            SpeedUtil.partialStrafePercent(95.0);
        }
        if (airTicks == 1) {
            SpeedUtil.partialStrafePercent(60.0);
        }
        if (airTicks == 2) {
            SpeedUtil.partialStrafePercent(50.0);
        } else {
            SpeedUtil.partialStrafePercent(1.0);
        }
        if (airTicks == 1) {
            player.motionY = SpeedUtil.predictedMotion(player.motionY + 0.12, 2);
        }
        if (Math.hypot(
                player.motionX - (player.lastTickPosX - player.posX),
                player.motionZ - (player.lastTickPosZ - player.posZ)
        ) < 0.005) {
            MoveUtil.strafe();
        }
        SpeedUtil.moveFlying(0.0008);
        SpeedUtil.preventDiagonalSpeed();
    }

    @Override
    public void onEnable() {
        this.strafeTicks = 0;
    }
}
