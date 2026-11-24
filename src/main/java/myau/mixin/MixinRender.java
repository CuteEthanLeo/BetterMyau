package myau.mixin;

import myau.Myau;
import myau.module.modules.PerformanceOptimizer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements the PerformanceOptimizer "entity-shadow-skip" setting: entity shadows
 * (and the fire overlay drawn by the same method) are skipped while it is enabled.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = {Render.class}, priority = 9999)
public abstract class MixinRender {
    @Inject(method = "doRenderShadowAndFire", at = @At("HEAD"), cancellable = true)
    private void onDoRenderShadowAndFire(Entity entity, double x, double y, double z, float yaw, float partialTicks, CallbackInfo ci) {
        if (Myau.moduleManager == null) {
            return;
        }
        PerformanceOptimizer opt = (PerformanceOptimizer) Myau.moduleManager.modules.get(PerformanceOptimizer.class);
        if (opt != null && opt.shouldSkipEntityShadow()) {
            ci.cancel();
        }
    }
}
