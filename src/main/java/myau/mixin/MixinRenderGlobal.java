package myau.mixin;

import myau.Myau;
import myau.module.modules.PerformanceOptimizer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@SideOnly(Side.CLIENT)
@Mixin(value = {RenderGlobal.class}, priority = 9999)
public abstract class MixinRenderGlobal {
    /**
     * Shrinks the main-thread time budget for chunk uploads when "async-chunk" is enabled.
     * {@code finishTimeNano} is an absolute deadline, so the remaining budget is scaled and
     * re-anchored to the current time instead of dividing the absolute value.
     */
    @Redirect(method = "updateChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;runChunkUploads(J)Z"))
    private boolean redirectChunkUploads(ChunkRenderDispatcher dispatcher, long finishTimeNano) {
        if (Myau.moduleManager == null) {
            return dispatcher.runChunkUploads(finishTimeNano);
        }
        PerformanceOptimizer opt = (PerformanceOptimizer) Myau.moduleManager.modules.get(PerformanceOptimizer.class);
        if (opt == null || !opt.isEnabled() || !opt.asyncChunk.getValue()) {
            return dispatcher.runChunkUploads(finishTimeNano);
        }
        long now = System.nanoTime();
        long remaining = Math.max(0L, finishTimeNano - now);
        // tier >= 2 keeps a quarter of the budget, otherwise half; the dispatcher always
        // processes at least one queued upload per call, so the queue stays bounded.
        long scaled = opt.getDynamicTier() >= 2 ? remaining / 4L : remaining / 2L;
        return dispatcher.runChunkUploads(now + scaled);
    }
}
