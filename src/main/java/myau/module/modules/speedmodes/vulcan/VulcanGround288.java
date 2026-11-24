package myau.module.modules.speedmodes.vulcan;

import myau.events.JumpEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C03PacketPlayer;

public class VulcanGround288 extends SpeedMode {
    public VulcanGround288() {
        super("VulcanGround2.8.8");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (SpeedUtil.isInLiquid() || SpeedUtil.isInWeb() || player.isOnLadder()) {
            return;
        }
        if (MoveUtil.isMoving() && this.collidesBottom()) {
            boolean isAffectedBySpeed = SpeedUtil.speedLevel() >= 2;
            boolean isMovingSideways = player.moveStrafing != 0.0F;
            float strafe = isAffectedBySpeed ? 0.59F : isMovingSideways ? 0.41F : 0.42F;
            MoveUtil.strafe(strafe);
            player.motionY = 0.005;
        }
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C03PacketPlayer && this.collidesBottom()) {
            C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
            ((IAccessorC03PacketPlayer) packet).setY(packet.getPositionY() + 0.005);
        }
    }

    private boolean collidesBottom() {
        return SpeedUtil.collides(-0.005);
    }

    @Override
    public void onJump(JumpEvent event) {
        event.setCancelled(true);
    }
}
