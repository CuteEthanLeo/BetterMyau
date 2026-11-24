package myau.events;

import myau.event.events.Event;
import net.minecraft.block.Block;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

/**
 * Fired for every block the local player is collision-tested against (air included).
 * The collision box can be replaced or removed (null).
 */
public class BlockBBEvent implements Event {
    private final BlockPos pos;
    private final Block block;
    private AxisAlignedBB boundingBox;

    public BlockBBEvent(BlockPos pos, Block block, AxisAlignedBB boundingBox) {
        this.pos = pos;
        this.block = block;
        this.boundingBox = boundingBox;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public int getX() {
        return this.pos.getX();
    }

    public int getY() {
        return this.pos.getY();
    }

    public int getZ() {
        return this.pos.getZ();
    }

    public Block getBlock() {
        return this.block;
    }

    public AxisAlignedBB getBoundingBox() {
        return this.boundingBox;
    }

    public void setBoundingBox(AxisAlignedBB boundingBox) {
        this.boundingBox = boundingBox;
    }
}
