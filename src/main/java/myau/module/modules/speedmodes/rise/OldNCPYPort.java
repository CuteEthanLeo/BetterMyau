package myau.module.modules.speedmodes.rise;

import myau.events.JumpEvent;
import myau.events.StrafeEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class OldNCPYPort extends SpeedMode {
    private static final double BASE_SPEED = 0.2873;

    private double speed = 0.0;
    private int stage = 0;
    private double lastHorizontalDistance = 0.0;

    public OldNCPYPort() {
        super("OldNCPYPort");
    }

    @Override
    public void onMotion() {
        EntityPlayerSP player = mc.thePlayer;
        double d0 = player.posX - player.lastTickPosX;
        double d1 = player.posZ - player.lastTickPosZ;
        this.lastHorizontalDistance = Math.sqrt(d0 * d0 + d1 * d1);
        if (this.stage == 3) {
            player.motionY += 0.4;
        }
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (!MoveUtil.isMoving()) {
            return;
        }
        switch (this.stage) {
            case 2:
                this.speed *= 2.14;
                this.stage = 3;
                break;
            case 3: {
                this.stage = 2;
                double d0 = 0.66 * (this.lastHorizontalDistance - BASE_SPEED);
                this.speed = this.lastHorizontalDistance - d0;
                break;
            }
            default:
                if (player.isCollidedVertically || SpeedUtil.collides(player.motionY)) {
                    this.stage = 1;
                }
                this.speed = this.lastHorizontalDistance - this.lastHorizontalDistance / 159.0;
                break;
        }
        if (player.isCollidedHorizontally) {
            this.speed = BASE_SPEED;
        }
        if (MoveUtil.airTicks == 1 && player.ticksExisted > 2) {
            this.speed = 0.3873;
        }
        if (!player.onGround) {
            this.stage++;
        }
        MoveUtil.strafe((float) this.speed);
        SpeedUtil.preventDiagonalSpeed();
    }

    @Override
    public void onJump(JumpEvent event) {
        event.setMotion(0.4F);
    }

    @Override
    public void onDisable() {
        this.speed = 0.0;
    }

    @Override
    public void onEnable() {
        this.stage = 2;
        this.speed = BASE_SPEED;
        this.lastHorizontalDistance = 0.0;
    }
}
