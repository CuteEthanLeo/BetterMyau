package myau.module.modules.longjumpmodes.other;

import myau.Myau;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntity;
import myau.module.modules.longjumpmodes.LongJumpMode;
import myau.util.ChatUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C03PacketPlayer;

public class VerusDamage extends LongJumpMode {
    public VerusDamage() {
        super("VerusDamage");
    }

    @Override
    public void onEnable() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        // Otherwise you will get flagged.
        if (!MoveUtil.isMoving()) {
            ChatUtil.sendFormatted(String.format(
                    "%s%s: &7Pls move while toggling LongJump. Using AutoJump option is recommended.&r",
                    Myau.clientName, this.parent.getName()
            ));
            return;
        }
        // Note: this flags once for Fly(G) | Loyisa Test Server
        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(player.posX, player.posY + 3.0001, player.posZ, false));
        PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(
                player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch, false
        ));
        PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(
                player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch, true
        ));
        this.parent.verusDamaged = true;
    }

    @Override
    public void onDisable() {
        this.parent.verusDamaged = false;
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        if (MoveUtil.isInLiquid() || ((IAccessorEntity) player).getIsInWeb() || player.isOnLadder()) {
            this.parent.setEnabled(false);
            return;
        }
        // Long jumps of 13-14+ blocks are possible
        if (this.parent.verusDamaged && MoveUtil.isMoving()) {
            player.jumpMovementFactor = 0.15F;
            player.motionY += 0.015F;
            // onGround checks do not work because of the spoofed ground packet, so motionY is used instead.
            if (this.parent.autoDisable.getValue() && player.motionY <= -0.4330104027478734) {
                MoveUtil.stopXZ();
                this.parent.setEnabled(false);
            }
        } else if (this.parent.autoDisable.getValue()) {
            this.parent.setEnabled(false);
        }
    }
}
