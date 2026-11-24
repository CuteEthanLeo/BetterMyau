package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * The default background of vanilla screens (pause menu, options, containers, lists) becomes
 * the blurred and dimmed scene instead of a flat gradient or the dirt texture, and hover text
 * becomes a glass card.
 */
@SideOnly(Side.CLIENT)
@Mixin(GuiScreen.class)
public abstract class MixinGuiScreen {
    @Shadow public int width;
    @Shadow public int height;

    @Shadow
    protected abstract void keyTyped(char typedChar, int keyCode) throws IOException;

    /**
     * Characters committed by an input method (Chinese, Japanese, ...) reach LWJGL 2 as WM_CHAR
     * without a key press before them, and it reports those as key 0 in the released state.
     * Vanilla only types on pressed events, so every character after the first of a commit was
     * lost. Later versions of the game type these too; the pressed ones still go the vanilla way.
     */
    /** Holding down the key that opened this screen must not type it once key repeat starts. */
    @Inject(method = "handleKeyboardInput", at = @At("HEAD"), cancellable = true)
    private void bettermyau$skipOpeningKey(CallbackInfo ci) {
        if (myau.util.OpeningKey.drop()) ci.cancel();
    }

    @Inject(method = "handleKeyboardInput", at = @At("HEAD"))
    private void bettermyau$typeCommittedCharacters(CallbackInfo ci) throws IOException {
        if (Keyboard.getEventKeyState() || Keyboard.getEventKey() != 0) return;
        char typed = Keyboard.getEventCharacter();
        // Surrogates come one half at a time and no font here can draw them.
        if (typed >= ' ' && typed != 127 && !Character.isSurrogate(typed)) this.keyTyped(typed, 0);
    }

    @Inject(method = "drawWorldBackground", at = @At("HEAD"), cancellable = true)
    private void bettermyau$glassBackground(int tint, CallbackInfo ci) {
        if (!HUD.glassScreens()) return;
        GlassTheme.screenBackdrop(this.width, this.height);
        ci.cancel();
    }

    /** Hover text (items, buttons, chat components) as a glass card. The three-argument overload calls this one. */
    @Inject(method = "drawHoveringText(Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void bettermyau$glassTooltip(java.util.List<String> lines, int x, int y, net.minecraft.client.gui.FontRenderer font, CallbackInfo ci) {
        if (lines == null || lines.isEmpty() || !HUD.glassScreens() || !HUD.glassTooltips()) return;
        myau.ui.glass.GlassTooltip.draw(lines, x, y, this.width, this.height);
        ci.cancel();
    }
}
