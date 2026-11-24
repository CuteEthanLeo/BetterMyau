package myau.module.modules.speedmodes.ncp;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Working on UNCP/NCP and Verus b3896/b3901. Credit: larryngton (LiquidBounce PR 3798).
 */
public class UNCPHopNew extends SpeedMode {
    private static final double SPEED_VALUE = 0.199999999;
    private static final double BOOST_MULTIPLIER = 0.00718;
    private static final float DAMAGE_BOOST_SPEED = 0.5F;

    private int airTick = 0;

    public UNCPHopNew() {
        super("UNCPHopNew");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player.fallDistance > 2.0F) {
            if (MoveUtil.getTimer() > 1.0F) {
                MoveUtil.setTimer(1.0F);
            }
            return;
        }
        if (!MoveUtil.isMoving() || SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (player.onGround) {
            if (parent.uncpLowHop.getValue()) {
                player.motionY = 0.4;
            } else {
                MoveUtil.tryJump();
            }
            this.airTick = 0;
            return;
        } else if (player.hurtTime <= 1) {
            this.airTick++;
            if (this.airTick == parent.uncpOnTick.getValue()) {
                MoveUtil.strafe();
                player.motionY = -0.1523351824467155;
            }
        }
        if (parent.uncpOnHurt.getValue() && player.hurtTime >= 2 && player.hurtTime <= 4 && player.motionY >= 0.0) {
            player.motionY -= 0.1;
        }
        // xw also has an on-ground strafe branch at this point; it can never run because the
        // ground case returns above, so only the air branch is kept.
        if (parent.uncpAirStrafe.getValue()) {
            MoveUtil.strafe(Math.max(SpeedUtil.speed(), (float) this.calculateSpeed(0.2)), 0.7);
        }
        if (parent.uncpTimerBoost.getValue()) {
            if (player.hurtTime <= 1) {
                switch (player.ticksExisted % 5) {
                    case 0:
                        MoveUtil.setTimer(1.025F);
                        break;
                    case 2:
                        MoveUtil.setTimer(1.08F);
                        break;
                    case 4:
                        MoveUtil.setTimer(1.0F);
                        break;
                    default:
                        break;
                }
            } else {
                MoveUtil.setTimer(1.0F);
            }
        }
        if (parent.uncpShouldBoost.getValue()) {
            player.motionX *= 1.0 + BOOST_MULTIPLIER;
            player.motionZ *= 1.0 + BOOST_MULTIPLIER;
        }
        if (parent.uncpDamageBoost.getValue() && player.hurtTime >= 1) {
            MoveUtil.strafe(Math.max(SpeedUtil.speed(), DAMAGE_BOOST_SPEED));
        }
    }

    private double calculateSpeed(double baseValue) {
        return baseValue + SPEED_VALUE * (double) SpeedUtil.speedAmplifier();
    }

    @Override
    public void onDisable() {
        this.airTick = 0;
    }
}
