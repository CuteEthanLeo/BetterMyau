package myau.guide;

import myau.Myau;
import myau.module.modules.HUD;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * What the player has seen of the guide, in {@code config/Myau/guide.properties} (outside the
 * module configs, which are meant to be shared): whether it was ever opened (the main menu button
 * shows a dot until then) and whether the one-time hint in the first world was shown.
 */
public final class GuideState {
    private static final File FILE = new File("./config/Myau/guide.properties");
    private static final long HINT_DELAY_NANOS = 4_000_000_000L;
    private static Properties props;
    private static long worldSince;

    private GuideState() {
    }

    public static boolean seen() {
        return flag("seen");
    }

    public static void markSeen() {
        set("seen");
    }

    /** Every frame: the first time the player is in a world, point at the guide once. */
    public static void frame() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            worldSince = 0;
            return;
        }
        if (worldSince == 0) worldSince = System.nanoTime();
        if (System.nanoTime() - worldSince < HINT_DELAY_NANOS || flag("hinted")) return;
        set("hinted");
        if (seen()) return;
        HUD.notify("BetterMyau 使用指南", "输入 .guide 或在功能菜单里打开", true);
    }

    private static boolean flag(String key) {
        return "true".equals(load().getProperty(key));
    }

    private static void set(String key) {
        Properties p = load();
        if ("true".equals(p.getProperty(key))) return;
        p.setProperty(key, "true");
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(FILE)) {
                p.store(out, "BetterMyau guide");
            }
        } catch (Exception e) {
            Myau.LOGGER.warn("[Guide] cannot save {}", FILE, e);
        }
    }

    private static Properties load() {
        if (props == null) {
            props = new Properties();
            if (FILE.isFile()) {
                try (InputStream in = new FileInputStream(FILE)) {
                    props.load(in);
                } catch (Exception e) {
                    Myau.LOGGER.warn("[Guide] cannot read {}", FILE, e);
                }
            }
        }
        return props;
    }
}
