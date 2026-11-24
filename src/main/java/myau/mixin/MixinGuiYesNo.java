package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import myau.ui.glass.Draw;
import myau.ui.glass.ScreenTransition;
import myau.ui.glass.SmoothFont;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Confirmation dialogs (delete world, open link...) as a glass card: the question as a title, the
 * explanation under it and the buttons inside. Subclasses that draw more on top (the link warning)
 * still do, over the card.
 */
@SideOnly(Side.CLIENT)
@Mixin(GuiYesNo.class)
public abstract class MixinGuiYesNo extends GuiScreen {
    @Shadow protected String messageLine1;
    @Shadow @Final private List<String> field_175298_s;

    @Inject(method = "drawScreen", at = @At("HEAD"), cancellable = true)
    private void bettermyau$drawGlass(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!HUD.glassScreens()) return;
        ci.cancel();
        this.drawDefaultBackground();
        Draw.syncGuiScale();
        SmoothFont title = SmoothFont.bold(11), body = SmoothFont.regular(8.5f);
        float w = 260;
        if (this.messageLine1 != null) w = Math.max(w, title.width(this.messageLine1) + 40);
        if (this.field_175298_s != null) for (String line : this.field_175298_s) w = Math.max(w, body.width(line) + 40);
        w = Math.min(this.width - 16, w);
        float bottom = 70;
        for (GuiButton button : this.buttonList) if (button.visible) bottom = Math.max(bottom, button.yPosition + button.height);
        float x = this.width / 2f - w / 2, y = 52, h = bottom + 12 - y;
        GlassTheme.panel(x, y, w, h, GlassTheme.R_PANEL, 1.3f);
        ScreenTransition.focus(x, y, w, h, GlassTheme.R_PANEL);
        if (this.messageLine1 != null) title.drawCentered(title.trim(this.messageLine1, w - 20), this.width / 2f, 67, GlassTheme.TEXT);
        if (this.field_175298_s != null) {
            int i = 0;
            for (String line : this.field_175298_s) {
                body.drawCentered(body.trim(line, w - 20), this.width / 2f, 90 + i * this.fontRendererObj.FONT_HEIGHT, GlassTheme.MUTED);
                i++;
            }
        }
        for (GuiButton button : this.buttonList) button.drawButton(this.mc, mouseX, mouseY);
    }
}
