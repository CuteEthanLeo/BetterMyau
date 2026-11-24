package myau.mixin;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A focused vanilla text field (chat, anvil, creative search, server address ...) reports where
 * its caret is, so the input method opens its windows there instead of the window's corner.
 */
@SideOnly(Side.CLIENT)
@Mixin(GuiTextField.class)
public abstract class MixinGuiTextField {
    @Shadow @Final private FontRenderer fontRendererInstance;
    @Shadow public int xPosition;
    @Shadow public int yPosition;
    @Shadow public int width;
    @Shadow public int height;
    @Shadow private String text;
    @Shadow private boolean enableBackgroundDrawing;
    @Shadow private boolean isFocused;
    @Shadow private int lineScrollOffset;
    @Shadow private int cursorPosition;

    @Shadow
    public abstract boolean getVisible();

    @Inject(method = "drawTextBox", at = @At("RETURN"))
    private void bettermyau$reportCaret(CallbackInfo ci) {
        if (!this.isFocused || !this.getVisible() || this.text == null) return;
        int from = Math.max(0, Math.min(this.lineScrollOffset, this.text.length()));
        int to = Math.max(from, Math.min(this.cursorPosition, this.text.length()));
        int inner = this.enableBackgroundDrawing ? this.width - 8 : this.width;
        int x = this.enableBackgroundDrawing ? this.xPosition + 4 : this.xPosition;
        int y = this.enableBackgroundDrawing ? this.yPosition + (this.height - 8) / 2 : this.yPosition;
        int caret = Math.min(inner, this.fontRendererInstance.getStringWidth(this.text.substring(from, to)));
        float scale = myau.ui.glass.Draw.guiScaleFactor();
        myau.util.ime.ImeControl.caret((x + caret) * scale, (y - 1) * scale, 10 * scale);
    }
}
