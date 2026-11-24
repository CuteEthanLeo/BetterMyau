package myau.module.modules.speedmodes.rise;

import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class KoksCraft extends SpeedMode {
    private int jumps = 0;

    public KoksCraft() {
        super("KoksCraft");
    }

    @Override
    public void onMotion() {
        EntityPlayerSP player = mc.thePlayer;
        if (player.onGround) {
            if (player.hurtTime == 0) {
                MoveUtil.strafe((float) SpeedUtil.getAllowedHorizontalDistance() * 0.99F);
            }
            player.jump();
            this.jumps++;
        }
        if (MoveUtil.airTicks == 1 && player.hurtTime == 0) {
            player.motionY = SpeedUtil.predictedMotion(player.motionY, this.jumps % 2 == 0 ? 2 : 4);
        }
    }

    @Override
    public void onEnable() {
        this.jumps = 0;
    }
}
