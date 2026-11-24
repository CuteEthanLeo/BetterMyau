package myau.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.RegionRenderCache;
import net.minecraft.util.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps chunk rebuilding from crashing the game when a block lookup lands outside the
 * render cache.
 * <p>
 * {@link RegionRenderCache} keeps a 20x20x20 array: the 16-block section plus a 2-block
 * margin. OptiFine connected textures (CTM) on a glass pane look at the neighbouring
 * pane's actual state; Forge's {@code BlockPane.canPaneConnectTo} then asks the stairs
 * next to it whether its side is solid, and the stairs look at their own neighbours.
 * That walks 3 blocks past the section edge, the array index overflows
 * (ArrayIndexOutOfBoundsException 8143) and the whole client dies on the chunk render
 * thread. The Hypixel Duels lobby triggers it at (47,128,21) as soon as you walk near.
 * <p>
 * For a position outside the cached cube we skip the array and read straight from the
 * chunks ({@link ChunkCache#getBlockState} has its own bounds check and the 3x3 chunks
 * around the section are loaded), so the real block comes back and CTM stays correct.
 * In-range lookups are untouched.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = {RegionRenderCache.class})
public abstract class MixinRegionRenderCache extends ChunkCache {
    // No fields of our own here: the inject edition redefines already-loaded classes,
    // and HotSpot refuses redefinitions that add fields.

    @Shadow
    @Final
    private BlockPos position;

    private MixinRegionRenderCache(World world, BlockPos from, BlockPos to, int sub) {
        super(world, from, to, sub);
    }

    @Inject(
            method = {"getBlockState(Lnet/minecraft/util/BlockPos;)Lnet/minecraft/block/state/IBlockState;"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void bettermyau$guardOutOfRange(BlockPos pos, CallbackInfoReturnable<IBlockState> callbackInfoReturnable) {
        int dx = pos.getX() - this.position.getX();
        int dy = pos.getY() - this.position.getY();
        int dz = pos.getZ() - this.position.getZ();
        // 20 = edge of the cached cube (16-block section + 2 blocks on each side).
        if (dx < 0 || dx >= 20 || dy < 0 || dy >= 20 || dz < 0 || dz >= 20) {
            callbackInfoReturnable.setReturnValue(super.getBlockState(pos));
        }
    }
}
