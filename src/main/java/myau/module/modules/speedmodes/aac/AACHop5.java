package myau.module.modules.speedmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class AACHop5 extends SpeedMode {
    public AACHop5() {
        super("AACHop5");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (!MoveUtil.isMoving() || SpeedUtil.isInLiquid() || player.isOnLadder() || player.isRiding()) {
            return;
        }
        if (player.onGround) {
            MoveUtil.tryJump();
            MoveUtil.setTimer(0.9385F);
            SpeedUtil.setSpeedInAir(0.0201F);
        }
        if (player.fallDistance < 2.5) {
            if (player.fallDistance > 0.7) {
                if (player.ticksExisted % 3 == 0) {
                    MoveUtil.setTimer(1.925F);
                } else if (player.fallDistance < 1.25) {
                    MoveUtil.setTimer(1.7975F);
                }
            }
            SpeedUtil.setSpeedInAir(0.02F);
        }
    }
}
