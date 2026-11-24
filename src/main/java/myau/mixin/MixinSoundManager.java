package myau.mixin;

import myau.ui.glass.AlOutput;
import net.minecraft.client.audio.SoundManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The interface sounds share Minecraft's OpenAL context ({@link AlOutput}). Every time the sound
 * engine shuts down (resource reloads, quitting) that context is destroyed, so the interface
 * source is released first and nothing is written until the engine is loaded again.
 */
@SideOnly(Side.CLIENT)
@Mixin({SoundManager.class})
public abstract class MixinSoundManager {
    @Inject(method = "unloadSoundSystem", at = @At("HEAD"))
    private void bettermyau$releaseUiSound(CallbackInfo callbackInfo) {
        AlOutput.suspend();
    }

    @Inject(method = "unloadSoundSystem", at = @At("RETURN"))
    private void bettermyau$resumeUiSound(CallbackInfo callbackInfo) {
        AlOutput.resume();
    }
}
