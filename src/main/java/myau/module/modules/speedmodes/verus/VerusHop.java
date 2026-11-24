package myau.module.modules.speedmodes.verus;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class VerusHop extends SpeedMode {
    private float speed = 0.0F;

    public VerusHop() {
        super("VerusHop");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving()) {
            if (player.onGround) {
                this.speed = SpeedUtil.speedLevel() >= 2 ? 0.46F : 0.34F;
                MoveUtil.tryJump();
            } else {
                this.speed *= 0.98F;
            }
            MoveUtil.strafe(this.speed);
        }
    }
}
