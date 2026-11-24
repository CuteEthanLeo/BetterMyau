package myau.module.modules.speedmodes.hypixel;

import myau.events.JumpEvent;
import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Working on Hypixel (Watchdog). Credit: LiquidSquid / Nextgen.
 */
public class HypixelLowHop extends SpeedMode {
    public HypixelLowHop() {
        super("HypixelLowHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (!MoveUtil.isMoving() || player.fallDistance > 1.2) {
            return;
        }
        if (player.onGround) {
            MoveUtil.tryJump();
            MoveUtil.strafe();
            return;
        }
        boolean glide = parent.hypixelGlide.getValue();
        int airTicks = MoveUtil.airTicks;
        switch (airTicks) {
            case 1:
                MoveUtil.strafe();
                break;
            case 5:
                player.motionY -= 0.1905189780583944;
                break;
            case 4:
                player.motionY -= 0.03;
                break;
            case 6:
                player.motionY *= 1.01;
                break;
            case 7:
                if (glide) {
                    player.motionY /= 1.5;
                }
                break;
            default:
                break;
        }
        if (airTicks >= 7 && glide) {
            MoveUtil.strafe(Math.max(SpeedUtil.speed(), 0.281F), 0.7);
        }
        if (player.hurtTime == 9) {
            MoveUtil.strafe();
        }
        if (SpeedUtil.speedAmplifier() == 2) {
            switch (airTicks) {
                case 1:
                case 2:
                case 5:
                case 6:
                case 8:
                    player.motionX *= 1.2;
                    player.motionZ *= 1.2;
                    break;
                default:
                    break;
            }
        }
    }

    /** Called for both the PRE and the POST jump event, exactly like in xw. */
    @Override
    public void onJump(JumpEvent event) {
        if (!MoveUtil.isMoving()) {
            return;
        }
        float atLeast = 0.281F + 0.13F * (float) SpeedUtil.speedAmplifier();
        MoveUtil.strafe(Math.max(SpeedUtil.speed(), atLeast));
    }
}
