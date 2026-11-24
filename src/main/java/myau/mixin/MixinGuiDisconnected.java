package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import myau.ui.glass.Draw;
import myau.ui.glass.LastPlayed;
import myau.ui.glass.ScreenTransition;
import myau.ui.glass.SmoothFont;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The disconnect screen: a "重新连接" button whenever the server is known (vanilla forgets it as
 * soon as a world is left, so {@link LastPlayed} keeps its own copy), Esc goes back, and in glass
 * the reason and message sit on a card with the buttons under it.
 */
@SideOnly(Side.CLIENT)
@Mixin(GuiDisconnected.class)
public abstract class MixinGuiDisconnected extends GuiScreen {
    @Shadow private String reason;
    @Shadow private List<String> multilineMessage;
    @Shadow @Final private GuiScreen parentScreen;

    @Unique private static final int BETTERMYAU$RECONNECT = 1;
    @Unique private float bettermyau$cardY, bettermyau$cardH, bettermyau$cardW;

    @Inject(method = "initGui", at = @At("RETURN"))
    private void bettermyau$reconnect(CallbackInfo ci) {
        ServerData server = LastPlayed.lastServer();
        GuiButton back = null;
        for (GuiButton button : this.buttonList) if (button.id == 0) back = button;
        if (back == null) return;
        int lines = this.multilineMessage == null ? 0 : this.multilineMessage.size();
        if (HUD.glassScreens()) {
            // Card: reason, message lines, the server; buttons right under it.
            bettermyau$cardW = 300;
            if (this.multilineMessage != null) {
                for (String line : this.multilineMessage) bettermyau$cardW = Math.max(bettermyau$cardW, this.fontRendererObj.getStringWidth(line) + 40);
            }
            bettermyau$cardW = Math.min(this.width - 24, bettermyau$cardW);
            bettermyau$cardH = 40 + lines * 11 + (server != null ? 14 : 0) + 8;
            float total = bettermyau$cardH + 10 + 20;
            bettermyau$cardY = Math.max(8, this.height / 2f - total / 2);
            back.yPosition = (int) (bettermyau$cardY + bettermyau$cardH + 10);
        }
        if (server == null) return;
        if (HUD.glassScreens()) {
            // Back and reconnect side by side (a narrower copy of vanilla's back button).
            GuiButton half = new GuiButton(back.id, this.width / 2 + 2, back.yPosition, 98, 20, back.displayString);
            this.buttonList.set(this.buttonList.indexOf(back), half);
            this.buttonList.add(new GuiButton(BETTERMYAU$RECONNECT, this.width / 2 - 100, back.yPosition, 98, 20, "重新连接"));
        } else {
            this.buttonList.add(new GuiButton(BETTERMYAU$RECONNECT, back.xPosition, back.yPosition + 24, back.width, 20, "重新连接"));
        }
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void bettermyau$doReconnect(GuiButton button, CallbackInfo ci) {
        if (button.id != BETTERMYAU$RECONNECT) return;
        ci.cancel();
        ServerData server = LastPlayed.lastServer();
        if (server == null) return;
        GuiScreen parent = this.parentScreen != null ? this.parentScreen : new GuiMultiplayer(new GuiMainMenu());
        FMLClientHandler.instance().connectToServer(parent, server);
    }

    @Inject(method = "keyTyped", at = @At("HEAD"))
    private void bettermyau$escapeGoesBack(char typed, int key, CallbackInfo ci) {
        if (key == Keyboard.KEY_ESCAPE) this.mc.displayGuiScreen(this.parentScreen);
    }

    @Inject(method = "drawScreen", at = @At("HEAD"), cancellable = true)
    private void bettermyau$drawGlass(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!HUD.glassScreens() || bettermyau$cardW <= 0) return;
        ci.cancel();
        this.drawDefaultBackground();
        Draw.syncGuiScale();
        float w = bettermyau$cardW, h = bettermyau$cardH, x = this.width / 2f - w / 2, y = bettermyau$cardY;
        GlassTheme.panel(x, y, w, h, GlassTheme.R_PANEL, 1.3f);
        ScreenTransition.focus(x, y, w, h, GlassTheme.R_PANEL);
        Draw.rect(x + w / 2 - 14, y + 10, 28, 2, 1, Draw.alpha(GlassTheme.BAD, 0.9f));
        SmoothFont title = SmoothFont.bold(11), body = SmoothFont.regular(8.5f), small = SmoothFont.regular(7.5f);
        String headline = this.reason == null || this.reason.isEmpty() ? "连接已断开" : this.reason;
        title.drawCentered(title.trim(headline, w - 24), x + w / 2, y + 18, GlassTheme.TEXT);
        float ly = y + 38;
        if (this.multilineMessage != null) {
            for (String line : this.multilineMessage) {
                body.drawCentered(body.trim(line, w - 20), x + w / 2, ly, GlassTheme.MUTED);
                ly += 11;
            }
        }
        ServerData server = LastPlayed.lastServer();
        if (server != null) {
            small.drawCentered(small.trim("服务器 · " + server.serverIP, w - 20), x + w / 2, ly + 3, GlassTheme.FAINT);
        }
        for (GuiButton button : this.buttonList) button.drawButton(this.mc, mouseX, mouseY);
    }
}
