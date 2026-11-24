package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.GlassScreens;
import myau.ui.GlassTheme;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Options screen: a glass panel behind the buttons and the title in the glass UI font. */
@SideOnly(Side.CLIENT)
@Mixin(GuiOptions.class)
public abstract class MixinGuiOptions extends GuiScreen {
    @Inject(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiOptions;drawDefaultBackground()V", shift = At.Shift.AFTER), require = 0)
    private void bettermyau$panel(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (HUD.glassScreens()) GlassScreens.buttonPanel(this.buttonList, mouseX, mouseY);
    }

    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiOptions;drawCenteredString(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V"), require = 0)
    private void bettermyau$title(GuiOptions screen, FontRenderer font, String text, int x, int y, int color) {
        if (!HUD.glassScreens()) screen.drawCenteredString(font, text, x, y, color);
        else if (!myau.ui.glass.ScreenTitle.handle(screen, text, x, y, color)) GlassTheme.title(text, x, y - 2);
    }
}
