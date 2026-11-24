package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import myau.ui.glass.DesignKit;
import myau.ui.glass.Draw;
import myau.ui.glass.LastPlayed;
import myau.ui.glass.ScreenTransition;
import myau.ui.glass.SmoothFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import myau.via.ViaHooks;

import java.io.IOException;

/**
 * The connecting screen: remembers every connection (for the main menu's resume entry and the
 * disconnect screen's reconnect), and in glass draws a card with a spinner, the step it is on, the
 * server and how long it has taken, with Cancel under it; Esc cancels too.
 */
@SideOnly(Side.CLIENT)
@Mixin(GuiConnecting.class)
public abstract class MixinGuiConnecting extends GuiScreen {
    @Shadow private NetworkManager networkManager;

    @Unique private long bettermyau$startedAt;

    @Inject(method = "<init>(Lnet/minecraft/client/gui/GuiScreen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/ServerData;)V",
            at = @At("RETURN"), require = 0)
    private void bettermyau$rememberServer(GuiScreen parent, Minecraft mc, ServerData data, CallbackInfo ci) {
        bettermyau$startedAt = System.currentTimeMillis();
        try {
            LastPlayed.server(data);
        } catch (RuntimeException ignored) {
            // Never let bookkeeping stop a connection.
        }
    }

    @Inject(method = "<init>(Lnet/minecraft/client/gui/GuiScreen;Lnet/minecraft/client/Minecraft;Ljava/lang/String;I)V",
            at = @At("RETURN"), require = 0)
    private void bettermyau$rememberAddress(GuiScreen parent, Minecraft mc, String host, int port, CallbackInfo ci) {
        bettermyau$startedAt = System.currentTimeMillis();
        try {
            LastPlayed.server(host, port);
        } catch (RuntimeException ignored) {
        }
    }

    /** Cross-version: which saved server this screen connects to (the host/port constructor has none). */
    @Redirect(method = "<init>(Lnet/minecraft/client/gui/GuiScreen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/ServerData;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setServerData(Lnet/minecraft/client/multiplayer/ServerData;)V"),
            require = 0)
    private void bettermyau$noteServerForVia(Minecraft mc, ServerData data) {
        mc.setServerData(data);
        ViaHooks.noteServerData(data);
    }

    /** Cross-version: the connector thread decides the version for this host:port (see ViaConnect). */
    @Inject(method = "connect", at = @At("HEAD"))
    private void bettermyau$prepareVia(String ip, int port, CallbackInfo ci) {
        ViaHooks.prepare(this, ip, port);
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void bettermyau$drawViaVanilla(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (HUD.glassScreens()) return;
        String via = ViaHooks.connectingLine(this.networkManager != null);
        if (!via.isEmpty()) this.drawCenteredString(this.fontRendererObj, via, this.width / 2, this.height / 2 - 35, 0xAAAAAA);
    }

    @Inject(method = "initGui", at = @At("RETURN"))
    private void bettermyau$placeCancel(CallbackInfo ci) {
        if (!HUD.glassScreens()) return;
        for (GuiButton button : this.buttonList) {
            if (button.id == 0) button.yPosition = this.height / 2 + 26;
        }
    }

    @Inject(method = "keyTyped", at = @At("HEAD"))
    private void bettermyau$escapeCancels(char typed, int key, CallbackInfo ci) throws IOException {
        if (key != Keyboard.KEY_ESCAPE) return;
        for (GuiButton button : this.buttonList) {
            if (button.id == 0 && button.enabled) {
                this.actionPerformed(button);
                return;
            }
        }
    }

    @Inject(method = "drawScreen", at = @At("HEAD"), cancellable = true)
    private void bettermyau$drawGlass(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!HUD.glassScreens()) return;
        ci.cancel();
        this.drawDefaultBackground();
        Draw.syncGuiScale();
        float w = 240, h = 74, x = this.width / 2f - w / 2, y = this.height / 2f - h - 4;
        GlassTheme.panel(x, y, w, h, GlassTheme.R_PANEL, 1.3f);
        ScreenTransition.focus(x, y, w, h, GlassTheme.R_PANEL);
        int accent = GlassTheme.vividAccent();
        DesignKit.spinner(x + 26, y + h / 2, 9, 1.8f, accent);

        String viaStage = ViaHooks.connectingStage();
        // Once connected: logging in, then (1.20.2+ through Via) the server configuring the player.
        String loginStage = this.networkManager == null ? null : ViaHooks.loginStage(this.networkManager);
        String step = this.networkManager == null ? (viaStage != null ? viaStage : "正在连接服务器…")
                : loginStage != null ? loginStage : "正在登录…";
        SmoothFont title = SmoothFont.bold(10.5f), small = SmoothFont.regular(8);
        float tx = x + 48, tw = w - 48 - 14;
        title.draw(step, tx, y + 16, GlassTheme.TEXT);
        ServerData server = LastPlayed.lastServer();
        if (server != null) {
            String name = server.serverName == null || server.serverName.equals(server.serverIP) ? server.serverIP
                    : server.serverName + "  ·  " + server.serverIP;
            small.draw(small.trim(name, tw), tx, y + 32, GlassTheme.MUTED);
        }
        long seconds = (System.currentTimeMillis() - bettermyau$startedAt) / 1000L;
        String via = ViaHooks.connectingLine(true);
        if (!via.isEmpty()) via = "  ·  " + via;
        small.draw(small.trim("已用时 " + seconds + " 秒" + via + "  ·  Esc 取消", tw), tx, y + 47, GlassTheme.FAINT);

        for (GuiButton button : this.buttonList) button.drawButton(this.mc, mouseX, mouseY);
    }
}
