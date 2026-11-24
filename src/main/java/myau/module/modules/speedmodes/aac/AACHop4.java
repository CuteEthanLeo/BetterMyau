package myau.module.modules.speedmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class AACHop4 extends SpeedMode {
    public AACHop4() {
        super("AACHop4");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        MoveUtil.setTimer(1.0F);
        if (!MoveUtil.isMoving() || SpeedUtil.isInLiquid() || player.isOnLadder() || player.isRiding()) {
            return;
        }
        if (player.onGround) {
            MoveUtil.tryJump();
        } else if (player.fallDistance <= 0.1) {
            MoveUtil.setTimer(1.5F);
        } else if (player.fallDistance < 1.3) {
            MoveUtil.setTimer(0.7F);
        } else {
            MoveUtil.setTimer(1.0F);
        }
    }
}
