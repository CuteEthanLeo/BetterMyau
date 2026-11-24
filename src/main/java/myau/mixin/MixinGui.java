package myau.mixin;

import myau.ui.glass.ScreenTitle;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Screen titles outside a world in the menus' style (see {@link ScreenTitle}). */
@SideOnly(Side.CLIENT)
@Mixin(Gui.class)
public abstract class MixinGui {
    @Inject(method = "drawCenteredString", at = @At("HEAD"), cancellable = true)
    private void bettermyau$screenTitle(FontRenderer font, String text, int x, int y, int color, CallbackInfo ci) {
        if (ScreenTitle.handle(this, text, x, y, color)) ci.cancel();
    }
}
