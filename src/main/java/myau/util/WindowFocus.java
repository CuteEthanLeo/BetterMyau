package myau.util;

import com.sun.jna.Pointer;
import org.apache.logging.log4j.LogManager;

/**
 * Restores the game window and brings it to the front. A launcher can hand the process a
 * minimised show state, and Windows honours it for the first window; nothing would be seen of
 * the boot screen until the taskbar button was clicked.
 *
 * <p>Windows only; anything going wrong leaves the window as it was.
 */
public final class WindowFocus {
    private static final int SW_RESTORE = 9;
    /** SWP_NOSIZE | SWP_NOMOVE | SWP_SHOWWINDOW */
    private static final int SWP_KEEP_PLACE = 0x1 | 0x2 | 0x40;
    private static final int VK_MENU = 0x12, KEYEVENTF_KEYUP = 0x2;

    private WindowFocus() {
    }

    /** Call on the thread that created the window, once it exists. */
    public static void bringToFront() {
        if (!GameWindow.WINDOWS) return;
        try {
            GameWindow.User32 user = GameWindow.user();
            GameWindow.Kernel32 kernel = GameWindow.kernel();
            if (user == null || kernel == null) return;

            GameWindow.invalidate();
            Pointer window = GameWindow.hwnd();
            if (window == null) return;
            boolean minimised = user.IsIconic(window);
            if (minimised) user.ShowWindow(window, SW_RESTORE);
            // Above every other window even if focus is refused below: topmost for an instant.
            user.SetWindowPos(window, Pointer.createConstant(-1), 0, 0, 0, 0, SWP_KEEP_PLACE);
            user.SetWindowPos(window, Pointer.createConstant(-2), 0, 0, 0, 0, SWP_KEEP_PLACE);
            user.BringWindowToTop(window);
            user.SetForegroundWindow(window);

            // Windows refuses focus to a process the user did not just interact with; sharing the
            // input queue of whichever window has it now lifts that for one call.
            Pointer front = user.GetForegroundWindow();
            if (!window.equals(front) && front != null) {
                int frontThread = user.GetWindowThreadProcessId(front, null), self = kernel.GetCurrentThreadId();
                if (frontThread != 0 && frontThread != self && user.AttachThreadInput(self, frontThread, true)) {
                    try {
                        user.BringWindowToTop(window);
                        user.SetForegroundWindow(window);
                    } finally {
                        user.AttachThreadInput(self, frontThread, false);
                    }
                }
            }
            // Last resort: a process that has just received input may take focus, and a tap of Alt counts.
            if (!window.equals(user.GetForegroundWindow())) {
                user.keybd_event((byte) VK_MENU, (byte) 0, 0, null);
                user.keybd_event((byte) VK_MENU, (byte) 0, KEYEVENTF_KEYUP, null);
                user.SetForegroundWindow(window);
            }
            LogManager.getLogger("BetterMyau").info("Window brought forward (was minimised: {}, has focus: {})",
                    minimised, window.equals(user.GetForegroundWindow()));
        } catch (Throwable t) {
            // Not Myau.LOGGER: this runs before the client class is initialised.
            LogManager.getLogger("BetterMyau").warn("Could not bring the window to the front", t);
        }
    }
}
