package myau.module.modules.speedmodes.ncp;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class UNCPHop extends SpeedMode {
    private float speed = 0.0F;
    private int tick = 0;

    public UNCPHop() {
        super("UNCPHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving()) {
            if (player.onGround) {
                this.speed = SpeedUtil.speedLevel() >= 2 ? 0.4563F : 0.3385F;
                MoveUtil.tryJump();
            } else {
                this.speed *= 0.98F;
            }
            if (player.isAirBorne && player.fallDistance > 2.0F) {
                MoveUtil.setTimer(1.0F);
                return;
            }
            MoveUtil.strafe(this.speed);
            if (!player.onGround && ++this.tick % 3 == 0) {
                MoveUtil.setTimer(1.0815F);
                this.tick = 0;
            } else {
                MoveUtil.setTimer(0.9598F);
            }
        } else {
            MoveUtil.setTimer(1.0F);
        }
    }

    @Override
    public void onDisable() {
        MoveUtil.setTimer(1.0F);
    }
}
