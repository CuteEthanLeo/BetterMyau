package myau.ui.glass;

import java.util.ArrayList;
import java.util.List;

/**
 * Work for the start of the next rendered frame (main thread). A chat command that opens a
 * screen needs this: the chat closes itself, and whatever screen is showing, right after the
 * command runs.
 */
public final class Defer {
    private static final List<Runnable> tasks = new ArrayList<>();

    private Defer() {
    }

    public static void nextFrame(Runnable task) {
        synchronized (tasks) {
            tasks.add(task);
        }
    }

    /** Called from {@code GlassTheme.beginFrame()}. */
    public static void run() {
        List<Runnable> now;
        synchronized (tasks) {
            if (tasks.isEmpty()) return;
            now = new ArrayList<>(tasks);
            tasks.clear();
        }
        for (Runnable task : now) {
            try {
                task.run();
            } catch (RuntimeException e) {
                myau.Myau.LOGGER.warn("[Glass] Deferred task failed", e);
            }
        }
    }
}
