package myau.util.ime;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import myau.module.modules.HUD;
import myau.util.GameWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiEditSign;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Switches the input method (Chinese, Japanese, Korean ...) on only while something takes text.
 * Vanilla leaves it on all the time, so in game W, A, S and D start a composition instead of
 * moving and the key-up never arrives. With nothing to type the window's input context is taken
 * away (the IME cannot open at all); a chat line, sign, book or focused text field gives it back.
 * While it is on, the composition and candidate windows are placed at the text caret.
 *
 * <p>Runs once per frame on the client thread, which owns the window. Windows only.
 */
public final class ImeControl {
    private static final Logger LOGGER = LogManager.getLogger("BetterMyau/IME");

    private interface Imm32 extends StdCallLibrary {
        boolean ImmAssociateContextEx(Pointer window, Pointer context, int flags);
        Pointer ImmGetContext(Pointer window);
        boolean ImmReleaseContext(Pointer window, Pointer context);
        /** COMPOSITIONFORM: style, point x, y, rect left, top, right, bottom. */
        boolean ImmSetCompositionWindow(Pointer context, int[] form);
        /** CANDIDATEFORM: index, style, point x, y, rect left, top, right, bottom. */
        boolean ImmSetCandidateWindow(Pointer context, int[] form);
        boolean ImmNotifyIME(Pointer context, int action, int index, int value);
    }

    private static final int IACE_DEFAULT = 0x0010;
    private static final int CFS_POINT = 0x0002, CFS_EXCLUDE = 0x0080;
    private static final int NI_COMPOSITIONSTR = 0x0015, CPS_CANCEL = 0x0004;

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static Imm32 imm;
    private static boolean failed;
    /** null: not managed (the window has its default context); otherwise what was applied. */
    private static Boolean applied;
    private static Pointer appliedWindow;

    // The caret drawn this frame, in window pixels (top-left origin); the one placed last.
    private static boolean caretSeen;
    private static int caretX, caretY, caretH;
    private static int placedX = Integer.MIN_VALUE, placedY, placedH;

    private static final Map<Class<?>, Field[]> TEXT_FIELDS = new HashMap<>();

    private ImeControl() {
    }

    /** A focused text field drew its caret here this frame (window pixels, y down). */
    public static void caret(float x, float y, float height) {
        caretSeen = true;
        caretX = Math.round(x);
        caretY = Math.round(y);
        caretH = Math.max(1, Math.round(height));
    }

    /** End of a rendered frame: bring the input method in line with the current screen. */
    public static void endFrame() {
        boolean seen = caretSeen;
        caretSeen = false;
        if (failed || !GameWindow.WINDOWS) return;
        try {
            Boolean wanted = HUD.imeAutoSwitch() ? wantsText(mc.currentScreen) : null;
            Pointer window = GameWindow.hwnd();
            if (window == null) return;
            if (!window.equals(appliedWindow)) {
                // A new window (fullscreen toggled) starts with the default context again.
                appliedWindow = window;
                applied = null;
                placedX = Integer.MIN_VALUE;
            }
            if (wanted == null) {
                if (applied != null && !applied) associate(window, true);
                applied = null;
                return;
            }
            if (!wanted.equals(applied)) {
                if (!wanted) cancelComposition(window);
                associate(window, wanted);
                applied = wanted;
                placedX = Integer.MIN_VALUE;
                LOGGER.info("Input method {} ({})", wanted ? "on" : "off",
                        mc.currentScreen == null ? "in game" : mc.currentScreen.getClass().getSimpleName());
            }
            if (wanted && seen && (caretX != placedX || caretY != placedY || caretH != placedH)) {
                place(window, caretX, caretY, caretH);
                placedX = caretX;
                placedY = caretY;
                placedH = caretH;
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("Input method control stopped working; the IME is left as Windows has it", t);
        }
    }

    /** True when typing in this screen produces text. */
    public static boolean wantsText(GuiScreen screen) {
        if (screen == null) return false;
        if (screen instanceof TextInputScreen) return ((TextInputScreen) screen).wantsTextInput();
        if (screen instanceof GuiChat || screen instanceof GuiEditSign || screen instanceof GuiScreenBook) return true;
        for (Field field : textFields(screen.getClass())) {
            try {
                GuiTextField text = (GuiTextField) field.get(screen);
                if (text != null && text.isFocused() && text.getVisible()) return true;
            } catch (IllegalAccessException ignored) {
            }
        }
        return false;
    }

    private static Field[] textFields(Class<?> type) {
        Field[] cached = TEXT_FIELDS.get(type);
        if (cached != null) return cached;
        List<Field> found = new ArrayList<>();
        for (Class<?> c = type; c != null && c != GuiScreen.class && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !GuiTextField.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    found.add(field);
                } catch (RuntimeException ignored) {
                }
            }
        }
        cached = found.toArray(new Field[0]);
        TEXT_FIELDS.put(type, cached);
        return cached;
    }

    private static Imm32 imm() {
        if (imm == null) imm = (Imm32) Native.loadLibrary("imm32", Imm32.class);
        return imm;
    }

    private static void associate(Pointer window, boolean on) {
        // A null context with no flags detaches the IME; IACE_DEFAULT restores the window's own one.
        if (!imm().ImmAssociateContextEx(window, null, on ? IACE_DEFAULT : 0)) {
            LOGGER.debug("ImmAssociateContextEx({}) failed", on);
        }
    }

    /** Throw away a half-typed composition so it does not come back later. */
    private static void cancelComposition(Pointer window) {
        Pointer context = imm().ImmGetContext(window);
        if (context == null) return;
        try {
            imm.ImmNotifyIME(context, NI_COMPOSITIONSTR, CPS_CANCEL, 0);
        } finally {
            imm.ImmReleaseContext(window, context);
        }
    }

    /** Composition window at the caret, candidate list next to it without covering the line. */
    private static void place(Pointer window, int x, int y, int h) {
        Pointer context = imm().ImmGetContext(window);
        if (context == null) return;
        try {
            imm.ImmSetCompositionWindow(context, new int[]{CFS_POINT, x, y, 0, 0, 0, 0});
            imm.ImmSetCandidateWindow(context, new int[]{0, CFS_EXCLUDE, x, y + h, x, y, x + 1, y + h});
        } finally {
            imm.ImmReleaseContext(window, context);
        }
    }
}
