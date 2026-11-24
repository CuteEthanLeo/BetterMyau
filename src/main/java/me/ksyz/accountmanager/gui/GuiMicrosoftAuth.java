package me.ksyz.accountmanager.gui;

import myau.ui.GlassButton;
import myau.ui.GlassTheme;

import me.ksyz.accountmanager.AccountManager;
import me.ksyz.accountmanager.auth.Account;
import me.ksyz.accountmanager.auth.MicrosoftAuth;
import me.ksyz.accountmanager.auth.SessionManager;
import me.ksyz.accountmanager.utils.Notification;
import me.ksyz.accountmanager.utils.SystemUtils;
import me.ksyz.accountmanager.utils.TextFormatting;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/*
 * This file is derived from https://github.com/ksyzov/AccountManager.
 * Originally licensed under the GNU LGPL.
 *
 * This modified version is licensed under the GNU GPL v3.
 */
public class GuiMicrosoftAuth extends GuiScreen {
    private final GuiScreen previousScreen;
    private final String state;
    private final String codeVerifier;

    private GuiButton openButton = null;
    private boolean openButtonEnabled = true;
    private GuiButton cancelButton = null;
    private String status = null;
    private String cause = null;
    private ExecutorService executor = null;
    private CompletableFuture<Void> task = null;
    private boolean success = false;

    public GuiMicrosoftAuth(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
        this.state = MicrosoftAuth.generateState();
        this.codeVerifier = MicrosoftAuth.generateCodeVerifier();
    }

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(openButton = new GlassButton(
                0,
                width / 2 - 75 - 2,
                height / 2 + fontRendererObj.FONT_HEIGHT / 2 + fontRendererObj.FONT_HEIGHT,
                75,
                20,
                "Open"
        ));
        buttonList.add(cancelButton = new GlassButton(
                1,
                width / 2 + 2,
                height / 2 + fontRendererObj.FONT_HEIGHT / 2 + fontRendererObj.FONT_HEIGHT,
                75,
                20,
                "Cancel"
        ));

        if (task == null) {
            MicrosoftAuth.CLIENT_ID = "42a60a84-599d-44b2-a7c6-b00cdef1d6a2";
            MicrosoftAuth.SCOPE = "XboxLive.signin XboxLive.offline_access";
            URI url = MicrosoftAuth.getMSAuthLink(state, MicrosoftAuth.codeChallenge(codeVerifier));
            SystemUtils.setClipboard(url != null ? url.toString() : "");
            status = "&fLogin link has been copied to the clipboard!&r";

            if (executor == null) {
                executor = Executors.newSingleThreadExecutor();
            }
            AtomicReference<String> refreshToken = new AtomicReference<>("");
            AtomicReference<String> accessToken = new AtomicReference<>("");
            task = MicrosoftAuth.acquireMSAuthCode(state, executor)
                    .thenComposeAsync(msAuthCode -> {
                        openButtonEnabled = false;
                        status = "&fAcquiring Microsoft access tokens&r";
                        return MicrosoftAuth.acquireMSAccessTokens(msAuthCode, codeVerifier, executor);
                    })
                    .thenComposeAsync(msAccessTokens -> {
                        status = "&fAcquiring Xbox access token&r";
                        refreshToken.set(msAccessTokens.get("refresh_token"));
                        return MicrosoftAuth.acquireXboxAccessToken(msAccessTokens.get("access_token"), executor);
                    })
                    .thenComposeAsync(xboxAccessToken -> {
                        status = "&fAcquiring Xbox XSTS token&r";
                        return MicrosoftAuth.acquireXboxXstsToken(xboxAccessToken, executor);
                    })
                    .thenComposeAsync(xboxXstsData -> {
                        status = "&fAcquiring Minecraft access token&r";
                        return MicrosoftAuth.acquireMCAccessToken(
                                xboxXstsData.get("Token"), xboxXstsData.get("uhs"), executor
                        );
                    })
                    .thenComposeAsync(mcToken -> {
                        status = "&fFetching your Minecraft profile&r";
                        accessToken.set(mcToken);
                        return MicrosoftAuth.login(mcToken, executor);
                    })
                    .thenAccept(session -> mc.addScheduledTask(() -> {
                        if (mc.currentScreen != this) return;
                        status = null;
                        Account acc = new Account(
                                refreshToken.get(), accessToken.get(), session.getUsername(),"42a60a84-599d-44b2-a7c6-b00cdef1d6a2","XboxLive.signin XboxLive.offline_access"
                        );
                        for (Account account : AccountManager.accounts) {
                            if (acc.getUsername().equals(account.getUsername())) {
                                acc.setUnban(account.getUnban());
                                break;
                            }
                        }
                        AccountManager.accounts.add(acc);
                        AccountManager.save();
                        SessionManager.set(session);
                        success = true;
                    }))
                    .exceptionally(error -> {
                        openButtonEnabled = false;
                        status = String.format("&c%s&r", error.getMessage());
                        cause = String.format("&c%s&r", error.getCause().getMessage());
                        return null;
                    });
        }
    }

    @Override
    public void onGuiClosed() {
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public void updateScreen() {
        if (success) {
            mc.displayGuiScreen(new GuiAccountManager(
                    previousScreen,
                    new Notification(
                            TextFormatting.translate(String.format(
                                    "&aSuccessful login! (%s)&r",
                                    SessionManager.get().getUsername()
                            )),
                            5000L
                    )
            ));
            success = false;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (openButton != null) {
            openButton.enabled = openButtonEnabled;
        }
        drawDefaultBackground();
        GlassTheme.backdrop(width, height);
        super.drawScreen(mouseX, mouseY, partialTicks);

        GlassTheme.title("Microsoft Authentication", width / 2f, height / 2f - 34);

        if (status != null) {
            GlassTheme.caption(TextFormatting.translate(status), width / 2f, height / 2f - 12);
        }

        if (cause != null) {
            GlassTheme.toast(TextFormatting.translate(cause), width / 2f, height - 26);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            actionPerformed(cancelButton);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null) {
            return;
        }

        if (button.enabled) {
            switch (button.id) {
                case 0: { // Open
                    SystemUtils.openWebLink(MicrosoftAuth.getMSAuthLink(state, MicrosoftAuth.codeChallenge(codeVerifier)));
                }
                break;
                case 1: { // Cancel
                    mc.displayGuiScreen(new GuiAccountManager(previousScreen));
                }
                break;
            }
        }
    }
}
