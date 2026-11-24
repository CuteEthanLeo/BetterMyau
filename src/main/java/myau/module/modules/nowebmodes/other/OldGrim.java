package myau.module.modules.nowebmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.nowebmodes.NoWebMode;
import myau.util.PacketUtil;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.List;

public class OldGrim extends NoWebMode {
    private static final int RADIUS = 2;

    public OldGrim() {
        super("OldGrim");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        // xw BlockUtils.searchBlocks(2, setOf(web)): offsets 2 down to -1 around the truncated player position
        List<BlockPos> webs = new ArrayList<>();
        int baseX = (int) mc.thePlayer.posX;
        int baseY = (int) mc.thePlayer.posY;
        int baseZ = (int) mc.thePlayer.posZ;
        for (int x = RADIUS; x >= -RADIUS + 1; x--) {
            for (int y = RADIUS; y >= -RADIUS + 1; y--) {
                for (int z = RADIUS; z >= -RADIUS + 1; z--) {
                    BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                    if (mc.theWorld.getBlockState(pos).getBlock() == Blocks.web) {
                        webs.add(pos);
                    }
                }
            }
        }
        clearWeb();
        for (BlockPos pos : webs) {
            PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, EnumFacing.DOWN));
        }
    }
}
