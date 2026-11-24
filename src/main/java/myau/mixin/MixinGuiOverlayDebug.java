package myau.mixin;

import myau.via.ViaHooks;
import net.minecraft.client.gui.GuiOverlayDebug;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Cross-version: F3 shows the version a translated connection talks to. */
@SideOnly(Side.CLIENT)
@Mixin({GuiOverlayDebug.class})
public abstract class MixinGuiOverlayDebug {
    @Inject(method = "getDebugInfoRight", at = @At("RETURN"))
    private void bettermyau$viaVersion(CallbackInfoReturnable<List<String>> cir) {
        String line = ViaHooks.debugLine();
        if (line == null || cir.getReturnValue() == null) return;
        try {
            cir.getReturnValue().add("");
            cir.getReturnValue().add(line);
        } catch (UnsupportedOperationException ignored) {
        }
    }
}
