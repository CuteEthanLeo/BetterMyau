package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

/**
 * Keys held while a screen was open count once it closes. Vanilla only sees a key go down when it
 * is pressed with no screen open, so after closing the inventory or chat with W still held the
 * player stands still until W is pressed again.
 */
public class SaveMoveKeys extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean screenWasOpen;

    public SaveMoveKeys() {
        super("SaveMoveKeys", false);
    }

    @Override
    public void onEnabled() {
        screenWasOpen = false;
    }

    @Override
    public void onDisabled() {
        screenWasOpen = false;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.currentScreen != null) {
            screenWasOpen = true;
        } else if (screenWasOpen) {
            screenWasOpen = false;
            if (mc.thePlayer == null) return;
            GameSettings s = mc.gameSettings;
            for (KeyBinding key : new KeyBinding[]{s.keyBindForward, s.keyBindBack, s.keyBindLeft, s.keyBindRight,
                    s.keyBindJump, s.keyBindSprint, s.keyBindSneak}) {
                if (key.getKeyCode() != 0) KeyBindUtil.updateKeyState(key.getKeyCode());
            }
        }
    }
}
