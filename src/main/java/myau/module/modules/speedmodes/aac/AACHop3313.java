package myau.module.modules.speedmodes.aac;

import myau.event.EventManager;
import myau.event.types.EventType;
import myau.events.JumpEvent;
import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.block.BlockCarpet;
import net.minecraft.client.entity.EntityPlayerSP;

public class AACHop3313 extends SpeedMode {
    public AACHop3313() {
        super("AACHop3.3.13");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (!MoveUtil.isMoving() || SpeedUtil.isInLiquid() || player.isOnLadder() || player.isRiding() || player.hurtTime > 0) {
            return;
        }
        if (player.onGround && player.isCollidedVertically) {
            // MotionXYZ
            float yawRad = player.rotationYaw * 0.017453292F;
            player.motionX -= (float) Math.sin(yawRad) * 0.202F;
            player.motionZ += (float) Math.cos(yawRad) * 0.202F;
            player.motionY = 0.405;
            EventManager.call(new JumpEvent(EventType.PRE, 0.405F));
            MoveUtil.strafe();
        } else if (player.fallDistance < 0.31F) {
            if (mc.theWorld.getBlockState(player.getPosition()).getBlock() instanceof BlockCarpet) {
                return;
            }
            // Motion XZ
            player.jumpMovementFactor = player.moveStrafing == 0.0F ? 0.027F : 0.021F;
            player.motionX *= 1.001;
            player.motionZ *= 1.001;
            // Motion Y
            if (!player.isCollidedHorizontally) {
                player.motionY -= 0.014999993F;
            }
        } else {
            player.jumpMovementFactor = 0.02F;
        }
    }

    @Override
    public void onDisable() {
        mc.thePlayer.jumpMovementFactor = 0.02F;
    }
}
