package myau.mixin;

import myau.Myau;
import myau.module.modules.PerformanceOptimizer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = {TileEntityRendererDispatcher.class}, priority = 9999)
public abstract class MixinTileEntityRendererDispatcher {
    @Inject(method = "renderTileEntityAt(Lnet/minecraft/tileentity/TileEntity;DDDFI)V", at = @At("HEAD"), cancellable = true)
    private void onRenderTileEntity(TileEntity tileEntityIn, double x, double y, double z, float partialTicks, int destroyStage, CallbackInfo ci) {
        PerformanceOptimizer opt = (PerformanceOptimizer) Myau.moduleManager.modules.get(PerformanceOptimizer.class);
        if (opt != null && !opt.shouldRenderTileEntity(tileEntityIn)) {
            ci.cancel(); // 剔除盲区箱子、告示牌，绝杀大厅掉帧 Bug
        }
    }
}
