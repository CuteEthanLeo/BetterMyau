package myau.mixin;

import myau.Myau;
import myau.module.modules.PerformanceOptimizer;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Particle limiter. Particles are dropped at the spawn entry point, either because
 * particles are disabled outright or because the configured particle cap is reached.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = {EffectRenderer.class}, priority = 9999)
public abstract class MixinEffectRenderer {
    @Shadow
    private List<EntityFX>[][] fxLayers;

    @Inject(method = "addEffect", at = @At("HEAD"), cancellable = true)
    private void onAddEffect(EntityFX effect, CallbackInfo ci) {
        if (Myau.moduleManager == null) {
            return;
        }
        PerformanceOptimizer opt = (PerformanceOptimizer) Myau.moduleManager.modules.get(PerformanceOptimizer.class);
        if (opt != null && opt.isEnabled() && opt.shouldDropParticle(this.countParticles())) {
            ci.cancel();
        }
    }

    private int countParticles() {
        int total = 0;
        if (this.fxLayers == null) {
            return 0;
        }
        for (List<EntityFX>[] layer : this.fxLayers) {
            if (layer == null) {
                continue;
            }
            for (List<EntityFX> list : layer) {
                if (list != null) {
                    total += list.size();
                }
            }
        }
        return total;
    }
}
