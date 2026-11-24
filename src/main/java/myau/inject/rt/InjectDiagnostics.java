package myau.inject.rt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行时（已进入 LaunchClassLoader、游戏已跑起来之后）的简易日志。入口 {@code InjectAgent} 有自己独立
 * 的诊断日志；本类服务注入完成后、加载期 transformer 之类的后续事件，写到同一个日志目录，便于排查。
 */
public final class InjectDiagnostics {
    private static volatile PrintWriter writer;

    private InjectDiagnostics() {
    }

    /** 由 InjectRuntime 在 run() 开头调用，沿用入口日志的目录。 */
    public static synchronized void init(String agentLogPath) {
        if (writer != null) return;
        try {
            File dir;
            if (agentLogPath != null && !agentLogPath.trim().isEmpty()) {
                dir = new File(agentLogPath).getParentFile();
            } else {
                String local = System.getenv("LOCALAPPDATA");
                if (local == null || local.trim().isEmpty()) local = System.getProperty("user.home");
                dir = new File(new File(local, "BetterMyau-Inject"), "logs");
            }
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(dir, "runtime.log"), true), "UTF-8"), true);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warn(String message, Throwable error) {
        write("WARN", message, error);
    }

    private static void write(String level, String message, Throwable error) {
        PrintWriter w = writer;
        String line = stamp() + " [" + level + "] " + message;
        try {
            if (w != null) {
                w.println(line);
                if (error != null) error.printStackTrace(w);
                w.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stamp() {
        try {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        } catch (Throwable ignored) {
            return "--:--:--";
        }
    }
}
