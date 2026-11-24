package myau.util;

import org.lwjgl.input.Keyboard;

/**
 * The key whose press opened the current screen, while it is still held. Its repeats are kept
 * from the screen (they would type into a search box); once it is released, or another screen
 * opens, every key goes through again.
 */
public final class OpeningKey {
    private static int key = -1;

    private OpeningKey() {
    }

    /** A key binding (key code, or character + 256 for key 0) just opened a screen. */
    public static void opened(int keyBinding) {
        key = keyBinding > 0 && keyBinding < 256 ? keyBinding : -1;
    }

    /** At the head of a screen's keyboard handling: true when this event is to be dropped. */
    public static boolean drop() {
        if (key < 0) return false;
        int k = Keyboard.getEventKey();
        if (k != key) return false;
        if (!Keyboard.getEventKeyState() || !Keyboard.isRepeatEvent()) {
            key = -1; // released, or pressed afresh: it is meant this time
            return false;
        }
        return true;
    }
}
