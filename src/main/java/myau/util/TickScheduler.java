package myau.util;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.TickEvent;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Runs actions a number of game ticks later (replaces the coroutine / nextTick helpers of the
 * ported modules). Everything pending is dropped on world change. Also keeps
 * {@link MoveUtil#airTicks} / {@link MoveUtil#groundTicks} up to date.
 */
public final class TickScheduler {
    public static final TickScheduler INSTANCE = new TickScheduler();
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final class Task {
        int ticks;
        final Runnable action;

        Task(int ticks, Runnable action) {
            this.ticks = ticks;
            this.action = action;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private final List<Task> incoming = new ArrayList<>();

    private TickScheduler() {
    }

    /** Runs {@code action} at the start of the game tick that is {@code ticks} ticks from now (minimum 1). */
    public static void schedule(int ticks, Runnable action) {
        synchronized (INSTANCE.incoming) {
            INSTANCE.incoming.add(new Task(Math.max(1, ticks), action));
        }
    }

    public static void nextTick(Runnable action) {
        schedule(1, action);
    }

    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer != null) {
            if (mc.thePlayer.onGround) {
                MoveUtil.groundTicks++;
                MoveUtil.airTicks = 0;
            } else {
                MoveUtil.airTicks++;
                MoveUtil.groundTicks = 0;
            }
        }
        synchronized (this.incoming) {
            this.tasks.addAll(this.incoming);
            this.incoming.clear();
        }
        List<Runnable> due = null;
        for (Iterator<Task> iterator = this.tasks.iterator(); iterator.hasNext(); ) {
            Task task = iterator.next();
            if (--task.ticks <= 0) {
                iterator.remove();
                if (due == null) {
                    due = new ArrayList<>();
                }
                due.add(task.action);
            }
        }
        if (due != null) {
            for (Runnable action : due) {
                try {
                    action.run();
                } catch (Throwable throwable) {
                    myau.Myau.LOGGER.error("Scheduled tick action failed", throwable);
                }
            }
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.tasks.clear();
        synchronized (this.incoming) {
            this.incoming.clear();
        }
        MoveUtil.airTicks = 0;
        MoveUtil.groundTicks = 0;
    }
}
