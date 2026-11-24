package myau.config;

import myau.Myau;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Saved configs as the click GUI sees them: the list on disk, one-click load / save / delete,
 * and auto-save. Auto-save writes the active config a moment after the last change (and when the
 * GUI closes, and on exit); with it off nothing is written unless the user saves.
 *
 * <p>Never construct a {@link Config} just to look at a file: its constructor makes that config
 * the active one.
 */
public final class ConfigManager {
    /** Not a .json file, so it never shows up as a config. */
    private static final File STATE_FILE = new File(Config.CONFIG_DIR, "config-manager.properties");
    /** Quiet time after the last change before auto-save writes. */
    private static final long DEBOUNCE_MS = 1500;
    /** A steady stream of changes still gets written at least this often. */
    private static final long MAX_DELAY_MS = 10000;

    private static boolean autoSave = true;
    /** Loading applies hundreds of values; none of them is a user change. */
    private static int suppress;
    /** Set once the startup config is applied: before that, property writes are just defaults. */
    private static volatile boolean ready;
    private static volatile boolean dirty;
    private static long firstDirty;
    private static ScheduledExecutorService timer;
    private static ScheduledFuture<?> pending;
    /** Bumped whenever the files on disk change, so the GUI knows to re-read the list. */
    private static int revision;

    private ConfigManager() {
    }

    public static final class Entry {
        public final String name;
        public final long modified;

        Entry(String name, long modified) {
            this.name = name;
            this.modified = modified;
        }
    }

    // ================================================================ startup / shutdown

    /** Reads the manager's own settings. @return the config to load at startup */
    public static String init() {
        String last = "default";
        if (STATE_FILE.isFile()) {
            Properties props = new Properties();
            try (InputStream in = new FileInputStream(STATE_FILE)) {
                props.load(in);
                autoSave = !"false".equalsIgnoreCase(props.getProperty("autoSave", "true"));
                last = Config.sanitize(props.getProperty("lastConfig", "default"));
            } catch (Exception e) {
                Myau.LOGGER.warn("Could not read {}", STATE_FILE.getName(), e);
            }
        }
        if (!new File(Config.CONFIG_DIR, last + ".json").isFile()) last = "default";
        return last;
    }

    /** The startup config has been applied: from now on changes count. */
    public static void ready() {
        dirty = false;
        ready = true;
    }

    /** Shutdown hook: save the active config if auto-save is on, and remember which one it was. */
    public static void onShutdown() {
        if (autoSave) {
            try {
                new Config(Config.lastConfig, false).save(true);
            } catch (Exception e) {
                Myau.LOGGER.error("Failed to save config on shutdown", e);
            }
        }
        writeState();
    }

    // ================================================================ list / load / save / delete

    public static String current() {
        return Config.lastConfig;
    }

    public static int revision() {
        return revision;
    }

    /** Configs on disk, newest first. */
    public static List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        File[] files = Config.CONFIG_DIR.listFiles();
        if (files == null) return entries;
        for (File file : files) {
            String fileName = file.getName();
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (!file.isFile() || !lower.endsWith(".json")) continue;
            if (lower.startsWith("clickgui-") || lower.contains(".corrupt")) continue;
            String name = fileName.substring(0, fileName.length() - 5);
            // Only names the config system itself can produce; anything else is not ours.
            if (!name.equals(Config.sanitize(name)) && !name.equalsIgnoreCase("default")) continue;
            entries.add(new Entry(name, file.lastModified()));
        }
        entries.sort((a, b) -> Long.compare(b.modified, a.modified));
        return entries;
    }

    public static boolean exists(String name) {
        return new File(Config.CONFIG_DIR, Config.sanitize(name) + ".json").isFile();
    }

    /** Applies a config and makes it the active one. */
    public static void load(String name) {
        suppress++;
        try {
            new Config(name, false).load();
        } finally {
            suppress--;
        }
        markClean();
        writeState();
        revision++;
    }

    /** Startup variant of {@link #load}: modules wake up once a world exists. */
    public static void loadDeferred(String name) {
        suppress++;
        try {
            Config config = new Config(name, true);
            if (config.file.exists()) config.loadDeferred();
        } finally {
            suppress--;
        }
    }

    /** Saves the live state as {@code name} and makes it the active config. */
    public static void save(String name) {
        new Config(name, true).save();
        markClean();
        writeState();
        revision++;
    }

    public static void saveCurrent() {
        save(Config.lastConfig);
    }

    /** Deletes a config file. The active config cannot be deleted. */
    public static boolean delete(String name) {
        String clean = Config.sanitize(name);
        if (clean.equals(Config.lastConfig)) return false;
        File file = new File(Config.CONFIG_DIR, clean + ".json");
        boolean deleted = file.isFile() && file.delete();
        if (deleted) revision++;
        return deleted;
    }

    // ================================================================ auto-save

    public static boolean isAutoSave() {
        return autoSave;
    }

    public static void setAutoSave(boolean on) {
        if (autoSave == on) return;
        autoSave = on;
        writeState();
        if (on && dirty) schedule();
    }

    /** There are changes the active config file does not have yet. */
    public static boolean isDirty() {
        return dirty;
    }

    /**
     * Runs a temporary change a module makes on its own and undoes later (e.g. HitSelect zeroing
     * KeepSprint's slowdown for one hit): it does not count as a change to auto-save.
     */
    public static void transientChange(Runnable change) {
        suppress++;
        try {
            change.run();
        } finally {
            suppress--;
        }
    }

    /** A module or setting changed. Cheap; safe to call from anywhere. */
    public static void markDirty() {
        if (!ready || suppress > 0) return;
        synchronized (ConfigManager.class) {
            if (!dirty) {
                dirty = true;
                firstDirty = System.currentTimeMillis();
            }
        }
        if (autoSave) schedule();
    }

    /** Write now if auto-save has something pending (e.g. the click GUI just closed). */
    public static void flush() {
        if (!dirty || !autoSave) return;
        synchronized (ConfigManager.class) {
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
        }
        autoSaveNow();
    }

    private static synchronized void schedule() {
        if (timer == null) {
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "BetterMyau-autosave");
                thread.setDaemon(true);
                return thread;
            });
        }
        if (pending != null) pending.cancel(false);
        long untilMax = firstDirty + MAX_DELAY_MS - System.currentTimeMillis();
        long delay = Math.max(0, Math.min(DEBOUNCE_MS, untilMax));
        // Saving reads every module's state, so it runs on the client thread, between ticks.
        pending = timer.schedule(() -> {
            Minecraft.getMinecraft().addScheduledTask(ConfigManager::autoSaveNow);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static void autoSaveNow() {
        if (!dirty || !autoSave) return;
        try {
            new Config(Config.lastConfig, false).save(true);
            markClean();
            revision++;
        } catch (Exception e) {
            Myau.LOGGER.error("Auto-save failed", e);
        }
    }

    private static synchronized void markClean() {
        dirty = false;
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private static void writeState() {
        Properties props = new Properties();
        props.setProperty("autoSave", Boolean.toString(autoSave));
        props.setProperty("lastConfig", Config.lastConfig);
        try {
            if (!Config.CONFIG_DIR.exists()) Config.CONFIG_DIR.mkdirs();
            try (OutputStream out = new FileOutputStream(STATE_FILE)) {
                props.store(out, "BetterMyau config manager");
            }
        } catch (Exception e) {
            Myau.LOGGER.warn("Could not write {}", STATE_FILE.getName(), e);
        }
    }
}
