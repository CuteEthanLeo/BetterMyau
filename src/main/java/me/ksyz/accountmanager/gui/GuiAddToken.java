package me.ksyz.accountmanager.gui;

import myau.ui.GlassButton;
import myau.ui.GlassTheme;

import me.ksyz.accountmanager.AccountManager;
import me.ksyz.accountmanager.auth.Account;
import me.ksyz.accountmanager.auth.MicrosoftAuth;
import me.ksyz.accountmanager.auth.SessionManager;
import me.ksyz.accountmanager.utils.Notification;
import me.ksyz.accountmanager.utils.TextFormatting;
import net.minecraft.client.gui.*;
import org.lwjgl.input.Keyboard;

import java.util.NoSuchElementException;
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
public class GuiAddToken extends GuiScreen {
    private final GuiScreen previousScreen;
    private final String state;

    private GuiButton openButton = null;
    private boolean openButtonEnabled = true;
    private GuiButton cancelButton = null;
    private String status = null;
    private String cause = null;
    private ExecutorService executor = null;
    private CompletableFuture<Void> task = null;
    private boolean success = false;
    private GuiTextField tokenField;

    public GuiAddToken(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
        this.state = MicrosoftAuth.generateState();
    }

    @Override
    public void initGui() {
        buttonList.clear();
        Keyboard.enableRepeatEvents(true);
        ScaledResolution sr = new ScaledResolution(mc);
        tokenField = new myau.ui.GlassTextField(1, mc.fontRendererObj, sr.getScaledWidth() / 2 - 100, sr.getScaledHeight() / 2, 200, 20);
        tokenField.setEnableBackgroundDrawing(false);
        tokenField.setMaxStringLength(32767);
        tokenField.setFocused(true);

        buttonList.add(new GlassButton(998, sr.getScaledWidth() / 2 - 100, sr.getScaledHeight() / 2 + 30, 200, 20, "Add"));
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
            Notification done = new Notification(
                    TextFormatting.translate(String.format(
                            "&a登录成功：%s&r",
                            SessionManager.get().getUsername()
                    )),
                    5000L
            );
            // Opened from the account manager: go back to that same screen instead of stacking a new one.
            if (previousScreen instanceof GuiAccountManager) {
                ((GuiAccountManager) previousScreen).notice(done);
                mc.displayGuiScreen(previousScreen);
            } else {
                mc.displayGuiScreen(new GuiAccountManager(previousScreen, done));
            }
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

        GlassTheme.title("Add Token", width / 2f, height / 2f - 44);
        GlassTheme.field(width / 2f - 104, height / 2f - 6, 208, 20, 6, 1);
        tokenField.drawTextBox();

        if (status != null) {
            GlassTheme.caption(TextFormatting.translate(status), width / 2f, height / 2f - 26);
        }

        if (cause != null) {
            GlassTheme.toast(TextFormatting.translate(cause), width / 2f, height - 26);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        tokenField.textboxKeyTyped(typedChar, keyCode);
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if(task == null || task.isDone() || task.isCancelled() || task.isCompletedExceptionally()){
                mc.displayGuiScreen(previousScreen);
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null) {
            return;
        }

        if (button.enabled) {
            if (button.id == 998) {
                if (task == null) {
                    if (executor == null) {
                        executor = Executors.newSingleThreadExecutor();
                    }
                    AtomicReference<String> refreshToken = new AtomicReference<>("");
                    AtomicReference<String> accessToken = new AtomicReference<>("");
                    MicrosoftAuth.CLIENT_ID = "00000000402b5328";
                    MicrosoftAuth.SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
                    task = MicrosoftAuth.login(tokenField.getText(), executor)
                            .handle((session, error) -> session != null)
                            .thenComposeAsync(completed -> {
                                if (completed) {
                                    throw new NoSuchElementException();
                                }
                                status = "&7Refreshing Microsoft access tokens...&r";
                                return MicrosoftAuth.refreshMSAccessTokens(tokenField.getText(), executor);
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
                                        refreshToken.get(), accessToken.get(), session.getUsername(),"00000000402b5328","service::user.auth.xboxlive.com::MBI_SSL"
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
                                task.cancel(true);
                                task = null;
                                return null;
                            });
                }
            }
        }
    }
}
