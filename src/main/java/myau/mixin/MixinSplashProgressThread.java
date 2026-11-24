package myau.mixin;

import myau.ui.glass.BootScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge's loading screen is drawn by an anonymous {@code Runnable} on its own thread
 * ({@code SplashProgress$3} in Forge 11.15.1.2318). Each pass of its loop ends by taking a
 * mutex and swapping buffers; just before that, {@link BootScreen} paints its own frame over
 * what Forge drew. Forge keeps doing all the thread and context handling.
 *
 * <p>If another Forge build numbers the class differently, the target is simply not found and
 * Forge's splash shows unchanged.
 */
@Pseudo
@Mixin(targets = "net.minecraftforge.fml.client.SplashProgress$3", remap = false)
public abstract class MixinSplashProgressThread {
    @Inject(method = "run", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/Semaphore;acquireUninterruptibly()V", remap = false), require = 0, remap = false)
    private void bettermyau$bootFrame(CallbackInfo ci) {
        BootScreen.splashFrame();
    }
}
