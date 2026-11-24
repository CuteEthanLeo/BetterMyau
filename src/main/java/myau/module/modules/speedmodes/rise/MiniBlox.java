package myau.module.modules.speedmodes.rise;

import myau.events.StrafeEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockSnow;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

public class MiniBlox extends SpeedMode {
    private double moveSpeed = 0.0;
    private boolean payloadSent = false;
    private int payloadTick = 0;

    public MiniBlox() {
        super("MiniBlox");
    }

    @Override
    public void onMotion() {
        EntityPlayerSP player = mc.thePlayer;
        if (this.payloadSent && player.ticksExisted - this.payloadTick >= 24) {
            this.payloadSent = false;
        }
        if (player.ticksExisted % 24 == 0) {
            BlockPos blockpos = this.findNearestSolidBlock(500);
            if (blockpos != null) {
                this.sendPayload(blockpos);
            } else {
                PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(
                        player.posX * 9.5, player.posY, player.posZ * 9.5, player.onGround
                ));
            }
        }
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        SpeedUtil.useDiagonalSpeed();
        if (player.onGround && MoveUtil.isMoving()) {
            player.jump();
            player.motionY = 0.399;
        }
        if (this.payloadSent && player.ticksExisted > 0) {
            MoveUtil.strafe((float) (MoveUtil.getNcpBaseSpeed() * (double) parent.miniBloxSpeed.getValue()));
        } else if (player.ticksExisted > 0) {
            MoveUtil.strafe((float) (MoveUtil.getNcpBaseSpeed() * 1.24));
        }
    }

    @Override
    public void onEnable() {
        BlockPos blockpos = this.findNearestSolidBlock(500);
        if (blockpos != null) {
            this.sendPayload(blockpos);
        }
        this.moveSpeed = 0.0;
    }

    @Override
    public void onDisable() {
        MoveUtil.stopXZ();
    }

    private void sendPayload(BlockPos blockpos) {
        EntityPlayerSP player = mc.thePlayer;
        this.payloadSent = true;
        this.payloadTick = player.ticksExisted;
        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(
                (double) blockpos.getX() + 0.5, (double) blockpos.getY(), (double) blockpos.getZ() + 0.5, player.onGround
        ));
        PacketUtil.sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(
                player.posX + player.motionX,
                player.posY + player.motionY,
                player.posZ + player.motionZ,
                player.onGround
        ));
    }

    /** Nearest full solid block on the player's Y level within {@code range} blocks (same scan as xw). */
    private BlockPos findNearestSolidBlock(int range) {
        EntityPlayerSP player = mc.thePlayer;
        int baseX = MathHelper.floor_double(player.posX);
        int y = MathHelper.floor_double(player.posY);
        int baseZ = MathHelper.floor_double(player.posZ);
        double best = Double.MAX_VALUE;
        BlockPos result = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int j = -range; j <= range; j++) {
            for (int k = -range; k <= range; k++) {
                int x = baseX + j;
                int z = baseZ + k;
                Block block = mc.theWorld.getBlockState(pos.set(x, y, z)).getBlock();
                if (block.getMaterial().isSolid() && block.isFullBlock()
                        && !(block instanceof BlockLeaves) && !(block instanceof BlockSnow)) {
                    double distance = player.getDistance((double) x + 0.5, (double) y, (double) z + 0.5);
                    if (distance < best) {
                        best = distance;
                        result = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return result;
    }
}
