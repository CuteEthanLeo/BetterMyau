package myau.util;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * The game's native window on Windows. LWJGL 2 keeps its handle private, so it is looked up by
 * the LWJGL window class and this process' id (another instance of the game may be running).
 * The window is destroyed and created again when the display mode or fullscreen state changes;
 * {@link #invalidate()} after that, or {@link #hwnd()} notices on its own a moment later.
 *
 * <p>Call from the thread that created the window. Everything returns null / false off Windows
 * or when JNA is missing.
 */
public final class GameWindow {
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    interface User32 extends StdCallLibrary {
        Pointer FindWindowExW(Pointer parent, Pointer after, WString className, WString title);
        int GetWindowThreadProcessId(Pointer window, IntByReference processId);
        boolean IsWindow(Pointer window);
        boolean IsIconic(Pointer window);
        boolean ShowWindow(Pointer window, int command);
        boolean BringWindowToTop(Pointer window);
        boolean SetForegroundWindow(Pointer window);
        Pointer GetForegroundWindow();
        boolean AttachThreadInput(int from, int to, boolean attach);
        boolean SetWindowPos(Pointer window, Pointer insertAfter, int x, int y, int width, int height, int flags);
        void keybd_event(byte key, byte scan, int flags, Pointer extra);
        Pointer MonitorFromWindow(Pointer window, int flags);
        /** {@code info}: MONITORINFO as ten ints, the first one its size (40). */
        boolean GetMonitorInfoW(Pointer monitor, int[] info);
    }

    interface Kernel32 extends StdCallLibrary {
        int GetCurrentProcessId();
        int GetCurrentThreadId();
    }

    private static final int MONITOR_DEFAULTTONEAREST = 2;

    private static User32 user;
    private static Kernel32 kernel;
    private static boolean failed;
    private static Pointer hwnd;
    private static long checkedAt;

    private GameWindow() {
    }

    static User32 user() {
        load();
        return user;
    }

    static Kernel32 kernel() {
        load();
        return kernel;
    }

    private static void load() {
        if (user != null || failed) return;
        if (!WINDOWS) {
            failed = true;
            return;
        }
        try {
            user = (User32) Native.loadLibrary("user32", User32.class);
            kernel = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
        } catch (Throwable t) {
            failed = true;
            user = null;
            kernel = null;
            org.apache.logging.log4j.LogManager.getLogger("BetterMyau").warn("Native window access unavailable", t);
        }
    }

    /** The current game window, or null. Re-checks a cached handle twice a second. */
    public static Pointer hwnd() {
        load();
        if (user == null) return null;
        long now = System.currentTimeMillis();
        if (hwnd != null && now - checkedAt > 500) {
            checkedAt = now;
            if (!user.IsWindow(hwnd) || !isOwn(hwnd)) hwnd = null;
        }
        if (hwnd == null) {
            hwnd = find();
            checkedAt = now;
        }
        return hwnd;
    }

    /** The window was (or may have been) created again: look it up on the next use. */
    public static void invalidate() {
        hwnd = null;
    }

    /** True when {@code window} is a different window from the one last returned by {@link #hwnd()}. */
    public static boolean changedSince(Pointer window) {
        Pointer now = hwnd();
        return now != null && !now.equals(window);
    }

    /**
     * Bounds of the monitor the window is mostly on, as {left, top, right, bottom} in desktop
     * pixels, or null when unknown (no window yet, or not on Windows).
     */
    public static int[] monitorBounds() {
        Pointer window = hwnd();
        if (window == null) return null;
        try {
            Pointer monitor = user.MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
            if (monitor == null) return null;
            int[] info = new int[10];
            info[0] = 40;
            if (!user.GetMonitorInfoW(monitor, info)) return null;
            return new int[]{info[1], info[2], info[3], info[4]};
        } catch (Throwable t) {
            return null;
        }
    }

    private static Pointer find() {
        int processId = kernel.GetCurrentProcessId();
        WString lwjgl = new WString("LWJGL");
        Pointer window = null;
        for (int i = 0; i < 64; i++) {
            window = user.FindWindowExW(null, window, lwjgl, null);
            if (window == null) return null;
            IntByReference owner = new IntByReference();
            user.GetWindowThreadProcessId(window, owner);
            if (owner.getValue() == processId) return window;
        }
        return null;
    }

    private static boolean isOwn(Pointer window) {
        IntByReference owner = new IntByReference();
        user.GetWindowThreadProcessId(window, owner);
        return owner.getValue() == kernel.GetCurrentProcessId();
    }
}
