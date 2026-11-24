package myau.module.modules.speedmodes.verus;

import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;

public class VerusLowHopNew extends SpeedMode {
    private float speed = 0.0F;

    public VerusLowHopNew() {
        super("VerusLowHopNew");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving()) {
            if (player.onGround) {
                MoveUtil.tryJump();
                // Speed potion effect 1 and 2+
                if (player.isPotionActive(Potion.moveSpeed)) {
                    int amplifier = player.getActivePotionEffect(Potion.moveSpeed).getAmplifier();
                    if (amplifier == 1) {
                        this.speed = 0.55F;
                    } else if (amplifier >= 2) {
                        this.speed = 0.7F;
                    } else {
                        this.speed = 0.33F;
                    }
                }
                // Slowness potion effect. As in xw (and upstream LiquidBounce) this assignment
                // always replaces the Speed potion value chosen above.
                if (player.isPotionActive(Potion.moveSlowdown)
                        && player.getActivePotionEffect(Potion.moveSlowdown).getAmplifier() == 1) {
                    this.speed = 0.3F;
                } else {
                    this.speed = 0.33F;
                }
            } else {
                if (MoveUtil.airTicks <= 1) {
                    player.motionY = -0.09800000190734863;
                }
                this.speed *= 0.99F;
            }
            MoveUtil.strafe(this.speed);
        }
    }
}
