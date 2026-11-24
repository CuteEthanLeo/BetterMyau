package myau.util;

import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.opengl.Display;

/**
 * Small fixes to how vanilla 1.8.9 treats the keyboard, run once per rendered frame.
 *
 * <ul>
 *     <li>Losing the window's focus releases every key binding. Vanilla only does so when it
 *     pauses for the lost focus; with that off, or with a screen open while keys are held
 *     (InvWalk), the key-up happens in another window and the player keeps walking.</li>
 *     <li>The input method follows what can take text ({@link myau.util.ime.ImeControl}).</li>
 * </ul>
 */
public final class InputFixes {
    private static boolean active = true;

    private InputFixes() {
    }

    public static void endFrame() {
        boolean now = Display.isCreated() && Display.isActive();
        if (active && !now) KeyBinding.unPressAllKeys();
        active = now;
        myau.util.ime.ImeControl.endFrame();
    }

    /** False while another window has the keyboard. */
    public static boolean windowActive() {
        return active;
    }
}
