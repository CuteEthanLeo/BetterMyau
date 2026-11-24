package me.ksyz.accountmanager.gui;

import me.ksyz.accountmanager.utils.Notification;
import me.ksyz.accountmanager.utils.TextFormatting;
import myau.ui.GlassButton;
import myau.ui.GlassTheme;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.ksyz.accountmanager.auth.SessionManager;
import me.ksyz.accountmanager.utils.SSLUtils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.Session;
import org.lwjgl.input.Keyboard;

import javax.net.ssl.HttpsURLConnection;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/*
 * This file is derived from https://github.com/ksyzov/AccountManager.
 * Originally licensed under the GNU LGPL.
 *
 * This modified version is licensed under the GNU GPL v3.
 */
public class GuiSessionLogin extends GuiScreen {
    private static final int TIMEOUT_MS = 15_000;

    private final GuiScreen previousScreen;

    private volatile String status = "Session Login";
    private GuiTextField sessionField;
    private GuiButton loginButton;
    private ScaledResolution sr;
    private ExecutorService executor;
    private Future<?> task;

    public GuiSessionLogin(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        sr = new ScaledResolution(mc);

        sessionField = new myau.ui.GlassTextField(1, mc.fontRendererObj, sr.getScaledWidth() / 2 - 100, sr.getScaledHeight() / 2, 200, 20);
        sessionField.setEnableBackgroundDrawing(false);
        sessionField.setMaxStringLength(32767);
        sessionField.setFocused(true);

        buttonList.add(loginButton = new GlassButton(998, sr.getScaledWidth() / 2 - 100, sr.getScaledHeight() / 2 + 30, 200, 20, "Login"));
        loginButton.enabled = task == null || task.isDone();

        super.initGui();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        super.onGuiClosed();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GlassTheme.backdrop(width, height);

        String text = status;
        GlassTheme.title("Session Login", width / 2f, height / 2f - 44);
        if (text != null) GlassTheme.caption(text, width / 2f, height / 2f - 26);
        GlassTheme.field(width / 2f - 104, height / 2f - 6, 208, 20, 6, 1);
        sessionField.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 998 && (task == null || task.isDone())) {
            String input = sessionField.getText().trim();
            if (input.isEmpty()) {
                status = "§cEnter a session token or ign:uuid:token";
                return;
            }
            loginButton.enabled = false;
            status = "Validating session...";
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "BetterMyau-session-login");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            // Network work runs off the game thread; the session and screen switch are applied
            // back on the Minecraft thread.
            task = executor.submit(() -> {
                try {
                    Session session = resolve(input);
                    mc.addScheduledTask(() -> {
                        if (mc.currentScreen != this) return;
                        SessionManager.set(session);
                        if (previousScreen instanceof GuiAccountManager) {
                            ((GuiAccountManager) previousScreen).notice(new Notification(
                                    TextFormatting.translate("&a已切换到会话：" + session.getUsername() + "&r"), 5000L));
                        }
                        mc.displayGuiScreen(previousScreen);
                    });
                } catch (Exception e) {
                    String message = e.getMessage() != null && e.getMessage().contains("401")
                            ? "§cError: Invalid session."
                            : "§cError: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    mc.addScheduledTask(() -> {
                        if (mc.currentScreen != this) return;
                        status = message;
                        loginButton.enabled = true;
                    });
                }
            });
        }

        super.actionPerformed(button);
    }

    private static Session resolve(String input) throws IOException {
        if (input.contains(":")) {
            String[] parts = input.split(":");
            if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new IOException("Expected ign:uuid:token");
            }
            return new Session(parts[0], parts[1], parts[2], "mojang");
        }

        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://api.minecraftservices.com/minecraft/profile").openConnection();
        connection.setSSLSocketFactory(SSLUtils.getSSLContext().getSocketFactory());
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + input);
        try {
            int code = connection.getResponseCode();
            if (code == 401) {
                throw new IOException("401 Unauthorized");
            }
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
                return new Session(json.get("name").getAsString(), json.get("id").getAsString(), input, "mojang");
            }
        } finally {
            connection.disconnect();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        sessionField.textboxKeyTyped(typedChar, keyCode);

        if (Keyboard.KEY_ESCAPE == keyCode) {
            mc.displayGuiScreen(previousScreen);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }
}
