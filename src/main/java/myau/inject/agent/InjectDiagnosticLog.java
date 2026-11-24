package myau.inject.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Socket 之外、独立于本项目其它类的失败落盘通道。
 *
 * <p>控制器读不到 Minecraft 进程的 stderr，而 socket 上报本身也可能是失败原因，所以每次注入都先在
 * 磁盘上留痕。文件名只由端口决定，控制器据此定位本次注入。写法移植自 FPS+Injection 的
 * AgentDiagnosticLog，只换了目录名和系统属性名。
 */
public final class InjectDiagnosticLog {
    private final File file;
    private final PrintWriter writer;

    private InjectDiagnosticLog(File file, PrintWriter writer) {
        this.file = file;
        this.writer = writer;
    }

    public static InjectDiagnosticLog open(String port) {
        try {
            File directory = directory();
            if (directory != null && (directory.isDirectory() || directory.mkdirs())) {
                File target = new File(directory, "agent-" + safe(port) + ".log");
                PrintWriter output = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(target, false), "UTF-8"), true);
                InjectDiagnosticLog log = new InjectDiagnosticLog(target, output);
                log.write("BetterMyau-Inject Agent 启动，进程 " + pid(), null);
                return log;
            }
        } catch (Throwable ignored) {
            // 落盘失败不能影响注入本身。
        }
        return new InjectDiagnosticLog(null, null);
    }

    /** 返回日志文件路径；无法落盘时返回空串。 */
    public String path() {
        return file == null ? "" : file.getAbsolutePath();
    }

    public void write(String message, Throwable error) {
        if (writer == null) return;
        try {
            writer.println(timestamp() + " " + message);
            if (error != null) error.printStackTrace(writer);
            writer.flush();
        } catch (Throwable ignored) {
        }
    }

    public void close() {
        if (writer == null) return;
        try { writer.close(); } catch (Throwable ignored) { }
    }

    /** 与控制器 InjectionSession.agentLogFile 必须保持一致，包括 bettermyau.inject.logDir 覆盖。 */
    private static File directory() {
        String override = System.getProperty("bettermyau.inject.logDir");
        if (override != null && !override.trim().isEmpty()) return new File(override);
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.trim().isEmpty()) local = System.getProperty("user.home");
        if (local == null || local.trim().isEmpty()) return null;
        return new File(new File(local, "BetterMyau-Inject"), "logs");
    }

    private static String pid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int separator = name.indexOf('@');
            return separator > 0 ? name.substring(0, separator) : name;
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    private static String safe(String port) {
        if (port == null) return "unknown";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < port.length() && i < 8; i++) {
            char c = port.charAt(i);
            if (c >= '0' && c <= '9') result.append(c);
        }
        return result.length() == 0 ? "unknown" : result.toString();
    }

    private static String timestamp() {
        try {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        } catch (Throwable ignored) {
            return "--:--:--";
        }
    }
}
