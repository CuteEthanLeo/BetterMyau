package myau.ai;

import myau.Myau;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * AI advisor hotkeys in the vanilla Controls menu, category "BetterMyau AI 军师" (unbound by
 * default). Same approach as {@link myau.music.ui.MusicKeys}: registered after options.txt was read,
 * so the saved keys are read back here.
 */
public final class AIKeys {
    private static final String CATEGORY = "BetterMyau AI 军师";
    private static KeyBinding quickAsk;
    private static KeyBinding openChat;
    private static boolean registered;

    private AIKeys() {
    }

    public static void init() {
        if (registered) return;
        registered = true;
        try {
            quickAsk = bind("AI 军师：现在先打谁");
            openChat = bind("AI 军师：打开对话");
            restoreSavedKeys();
        } catch (RuntimeException | LinkageError e) {
            Myau.LOGGER.warn("[AI] Could not register hotkeys", e);
        }
    }

    /** True once per press of the quick-ask key. */
    public static boolean quickAskPressed() {
        return quickAsk != null && quickAsk.isPressed();
    }

    public static boolean openChatPressed() {
        return openChat != null && openChat.isPressed();
    }

    private static KeyBinding bind(String name) {
        KeyBinding kb = new KeyBinding(name, 0, CATEGORY);
        ClientRegistry.registerKeyBinding(kb);
        return kb;
    }

    private static void restoreSavedKeys() {
        File options = new File(Minecraft.getMinecraft().mcDataDir, "options.txt");
        if (!options.isFile()) return;
        KeyBinding[] ours = {quickAsk, openChat};
        try (BufferedReader in = new BufferedReader(new FileReader(options))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.startsWith("key_")) continue;
                int colon = line.lastIndexOf(':');
                if (colon < 0) continue;
                String name = line.substring(4, colon);
                for (KeyBinding kb : ours) {
                    if (kb.getKeyDescription().equals(name)) {
                        try {
                            kb.setKeyCode(Integer.parseInt(line.substring(colon + 1).trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (IOException e) {
            Myau.LOGGER.warn("[AI] Could not read hotkeys from options.txt: {}", e.toString());
        }
        KeyBinding.resetKeyBindingArrayAndHash();
    }
}
