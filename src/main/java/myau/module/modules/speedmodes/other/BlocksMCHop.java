package myau.module.modules.speedmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class BlocksMCHop extends SpeedMode {
    public BlocksMCHop() {
        super("BlocksMCHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (!MoveUtil.isMoving()) {
            return;
        }
        if (player.onGround) {
            MoveUtil.tryJump();
            return;
        }
        int airTicks = MoveUtil.airTicks;
        if (parent.bmcFullStrafe.getValue()) {
            MoveUtil.strafe(SpeedUtil.speed() - 0.004F);
        } else if (airTicks >= 6) {
            MoveUtil.strafe();
        }
        if (SpeedUtil.speedAmplifier() > 0 && airTicks == 3) {
            player.motionX *= 1.12;
            player.motionZ *= 1.12;
        }
        if (parent.bmcLowHop.getValue() && airTicks == 4) {
            if (parent.bmcSafeY.getValue()) {
                if (player.posY % 1.0 == 0.16610926093821377) {
                    player.motionY = -0.09800000190734863;
                }
            } else {
                player.motionY = -0.09800000190734863;
            }
        }
        if (player.hurtTime == 9 && parent.bmcDamageBoost.getValue()) {
            MoveUtil.strafe(Math.max(SpeedUtil.speed(), 0.7F));
        }
        if (parent.bmcDamageLowHop.getValue() && player.hurtTime >= 1) {
            if (player.motionY > 0.0) {
                player.motionY -= 0.15;
            }
        }
    }
}
