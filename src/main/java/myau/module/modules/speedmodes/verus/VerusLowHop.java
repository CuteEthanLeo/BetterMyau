package myau.module.modules.speedmodes.verus;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class VerusLowHop extends SpeedMode {
    private float speed = 0.0F;

    public VerusLowHop() {
        super("VerusLowHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving()) {
            if (player.onGround) {
                this.speed = SpeedUtil.speedLevel() >= 2 ? 0.5F : 0.36F;
                MoveUtil.tryJump();
            } else {
                if (MoveUtil.airTicks <= 1) {
                    player.motionY = -0.09800000190734863;
                }
                this.speed *= 0.98F;
            }
            MoveUtil.strafe(this.speed);
        }
    }
}
