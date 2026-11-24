package myau.module.modules.speedmodes.hypixel;

import myau.event.types.EventType;
import myau.events.JumpEvent;
import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Ported from LiquidBounce Nextgen SpeedHypixelLowHop (GPLv3).
 * anticheat: Watchdog (NCP), anticheatVersion 25.01.25, tested on hypixel.net
 * (The older xw-based values are kept as "HypixelLowHop".)
 */
public class HypixelLowHop2025 extends SpeedMode {
    public HypixelLowHop2025() {
        super("HypixelLowHop2025");
    }

    @Override
    public void onMoveInput() {
        if (mc.thePlayer.onGround && MoveUtil.isMoving()) {
            mc.thePlayer.movementInput.jump = true;
        }
    }

    /** A block within 0.66 below while falling: safe to strafe. */
    private boolean isGroundExempt() {
        EntityPlayerSP player = mc.thePlayer;
        return player.motionY < 0
                && !mc.theWorld.getCollidingBoundingBoxes(player, player.getEntityBoundingBox().offset(0.0, -0.66, 0.0)).isEmpty();
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player.onGround) {
            MoveUtil.strafe();
            return;
        }
        int airTicks = MoveUtil.airTicks;
        switch (airTicks) {
            case 1:
                MoveUtil.strafe();
                player.motionY += 0.0568;
                break;
            case 3:
                player.motionX *= 0.95;
                player.motionZ *= 0.95;
                player.motionY -= 0.13;
                break;
            case 4:
                player.motionY -= 0.2;
                break;
            case 7:
                if (parent.lowHop2025Glide.getValue() && this.isGroundExempt()) {
                    player.motionY = 0.0;
                }
                break;
            default:
                break;
        }
        if (this.isGroundExempt()) {
            MoveUtil.strafe();
        }
        if (player.hurtTime == 9) {
            MoveUtil.strafe(Math.max(MoveUtil.getSpeed(), 0.281));
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

    @Override
    public void onJump(JumpEvent event) {
        // Nextgen PlayerJumpEvent fires at the head of jump(), before vanilla adds the sprint boost
        if (event.getType() != EventType.PRE) {
            return;
        }
        double atLeast = 0.247 + 0.15 * SpeedUtil.speedAmplifier();
        MoveUtil.strafe(Math.max(MoveUtil.getSpeed(), atLeast));
    }
}
