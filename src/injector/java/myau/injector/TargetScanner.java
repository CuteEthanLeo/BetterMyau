package myau.injector;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 枚举本机 JVM，判定哪些是可注入的 Forge 1.8.9 客户端。
 *
 * <p>识别规则（1.9.2 起）：<b>疑似游戏的进程一律显示出来</b>，哪怕 attach 不上——卡片上写明原因
 * （32 位 Java、权限不足、被安全软件拦截……），而不是悄悄藏起来让界面一直显示「等待 Minecraft 启动」。
 * 候选进程有两个来源：{@code VirtualMachine.list()}（靠 hsperfdata），以及兜底的「所有 java.exe / javaw.exe
 * 进程」——hsperfdata 被禁用、不可读或游戏以管理员身份运行时，前者里根本看不到游戏。
 * 每个进程的结论有变化时写进日志回调和 {@code %LOCALAPPDATA%\BetterMyau-Inject\logs\scan.log}。
 */
public final class TargetScanner {
    /** attach 失败的进程隔多久再试一次（刚启动的 JVM 常会先失败）。 */
    private static final long RETRY_MS = 6000;
    /** 单次 attach 判定的超时：超时就记「连接超时」，不让一个卡住的 attach 拖死整个自动检测。 */
    private static final long ATTACH_TIMEOUT_MS = 4000;
    /** 兜底来源的进程至少启动这么久才判定。 */
    private static final long UNLISTED_MIN_AGE_MS = 3000;

    private static final Pattern FORGE_LIB = Pattern.compile("minecraftforge[\\\\/]forge[\\\\/](\\d+\\.\\d+(?:\\.\\d+)?)-");
    private static final Pattern SECRET_ARG = Pattern.compile(
        "(--(?:accessToken|uuid|session|xuid|clientId)\\s+)\\S+", Pattern.CASE_INSENSITIVE);

    /** 一个候选进程（来自 VirtualMachine.list() 或兜底的进程枚举）。 */
    private static final class Candidate {
        final String pid;
        final String displayName; // jvmstat 里的命令行（主类 + 参数），兜底来源没有
        final boolean listed;
        final String exe;         // 兜底来源的可执行文件路径

        Candidate(String pid, String displayName, boolean listed, String exe) {
            this.pid = pid;
            this.displayName = displayName == null ? "" : displayName.trim();
            this.listed = listed;
            this.exe = exe == null ? "" : exe;
        }

        String source() {
            return listed ? "JVM 列表" : "进程兜底";
        }
    }

    /** 一个进程的判定结论。 */
    private static final class Entry {
        final TargetDescriptor target; // null：不是游戏，不显示
        final long retryAt;            // 0：结论已定；否则到这个时间再判一次
        final String summary;          // 一行结论（变化时才输出）
        final String detail;           // 只写进 scan.log 的细节（已打码）

        Entry(TargetDescriptor target, long retryAt, String summary, String detail) {
            this.target = target;
            this.retryAt = retryAt;
            this.summary = summary;
            this.detail = detail;
        }
    }

    private final Map<String, Entry> known = new HashMap<>();
    private final Map<String, Future<Entry>> inflight = new HashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "BetterMyau-Inject-scan");
        t.setDaemon(true);
        return t;
    });
    private final Consumer<String> log;
    private final ScanLog file = new ScanLog();
    private String lastListError;

    public TargetScanner() {
        this(msg -> { });
    }

    /** @param log 进程结论有变化时的一行中文说明（界面日志抽屉用；在扫描线程上回调） */
    public TargetScanner(Consumer<String> log) {
        this.log = log;
    }

    /** scan.log 的位置（界面里提示用户发这个文件）。 */
    public static Path scanLogPath() {
        return ScanLog.PATH;
    }

    /** 全量重新判定（手动刷新）。 */
    public synchronized List<TargetDescriptor> scan() {
        known.clear();
        file.header();
        return poll();
    }

    /**
     * 增量判定：只对新出现的、或到了重试时间的进程做 attach；消失的进程移除。供自动检测每隔几秒调用。
     */
    public synchronized List<TargetDescriptor> poll() {
        String ownPid = String.valueOf(ProcessHandle.current().pid());
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        try {
            for (VirtualMachineDescriptor d : VirtualMachine.list()) {
                if (ownPid.equals(d.id())) continue;
                candidates.put(d.id(), new Candidate(d.id(), d.displayName(), true, null));
            }
            lastListError = null;
        } catch (Throwable e) {
            String msg = describe(e);
            if (!msg.equals(lastListError)) file.line("VirtualMachine.list() 失败：" + msg);
            lastListError = msg;
        }
        for (Candidate c : unlistedJavaProcesses(candidates.keySet(), ownPid)) candidates.put(c.pid, c);

        // 先收回上次超时、后来才跑完的判定。
        for (Iterator<Map.Entry<String, Future<Entry>>> it = inflight.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Future<Entry>> me = it.next();
            if (!me.getValue().isDone()) continue;
            it.remove();
            try {
                record(me.getKey(), me.getValue().get());
            } catch (Exception ignored) {
            }
        }

        long now = System.currentTimeMillis();
        Map<String, Future<Entry>> started = new LinkedHashMap<>();
        for (Candidate c : candidates.values()) {
            Entry e = known.get(c.pid);
            if (e != null && (e.retryAt == 0 || now < e.retryAt)) continue;
            if (inflight.containsKey(c.pid)) continue;   // 上次的 attach 还卡着，别再叠线程
            Future<Entry> f = pool.submit(() -> inspect(c));
            inflight.put(c.pid, f);
            started.put(c.pid, f);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ATTACH_TIMEOUT_MS);
        for (Map.Entry<String, Future<Entry>> me : started.entrySet()) {
            Candidate c = candidates.get(me.getKey());
            Entry e;
            try {
                e = me.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                inflight.remove(c.pid);
            } catch (TimeoutException timeout) {
                e = failed(c, "attach 超过 " + ATTACH_TIMEOUT_MS / 1000 + " 秒没有响应（timed out）");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException error) {
                inflight.remove(c.pid);
                e = failed(c, describe(error.getCause() == null ? error : error.getCause()));
            }
            record(c.pid, e);
        }

        known.keySet().retainAll(candidates.keySet());
        inflight.keySet().retainAll(candidates.keySet());
        List<TargetDescriptor> targets = new ArrayList<>();
        for (Entry e : known.values()) if (e.target != null) targets.add(e.target);
        targets.sort(Comparator.comparing((TargetDescriptor t) -> t.compatible).reversed()
            .thenComparing(t -> t.pid));
        return targets;
    }

    private void record(String pid, Entry e) {
        Entry previous = known.put(pid, e);
        if (previous != null && Objects.equals(previous.summary, e.summary)) return;
        file.line(e.summary);
        if (e.detail != null && !e.detail.isEmpty() && (previous == null || !Objects.equals(previous.detail, e.detail))) {
            file.line("    " + e.detail);
        }
        if (e.target != null) log.accept(e.summary);   // 不是游戏的进程只进 scan.log，不刷屏
    }

    // ───────────────────────── 单个进程的判定 ─────────────────────────

    /** attach 进去读系统属性再判定；attach 失败交给 {@link #failed}。 */
    private Entry inspect(Candidate c) {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(c.pid);
            Properties p = vm.getSystemProperties();
            String javaVersion = p.getProperty("java.version", "未知");
            String dataModel = p.getProperty("sun.arch.data.model", "");
            String classPath = p.getProperty("java.class.path", "");
            String command = p.getProperty("sun.java.command", "");
            String head = "PID " + c.pid + "（" + c.source() + "）Java " + javaVersion
                + (dataModel.isEmpty() ? "" : " " + dataModel + " 位");
            TargetDescriptor t = classify(c.pid, javaVersion, dataModel, classPath, command, c.displayName);
            String detail = "命令：" + shorten(redact(command.isEmpty() ? c.displayName : command), 400)
                + "  ·  java.home=" + p.getProperty("java.home", "?");
            if (t == null) return new Entry(null, 0, head + "：不是 Minecraft，忽略", detail);
            return new Entry(t, 0, head + " · " + t.kind + " → " + t.status, detail);
        } catch (Throwable e) {
            return failed(c, describe(e));
        } finally {
            if (vm != null) try { vm.detach(); } catch (Exception ignored) { }
        }
    }

    /**
     * attach 不上时的结论：命令行（不用 attach 就有）像游戏、或者根本读不到命令行，都显示一张不可注入的卡片并写明原因，
     * 隔几秒再试；命令行明确不是游戏的才隐藏。
     */
    private static Entry failed(Candidate c, String raw) {
        String reason = attachFailureReason(raw);
        long retryAt = System.currentTimeMillis() + RETRY_MS;
        String head = "PID " + c.pid + "（" + c.source() + "）";
        if (c.listed) {
            boolean readable = !c.displayName.isEmpty() && !c.displayName.equals(c.pid);
            String lower = c.displayName.toLowerCase(Locale.ROOT);
            String detail = "命令：" + shorten(redact(c.displayName), 400) + "  ·  attach 异常：" + raw;
            if (readable && !looksLikeMinecraft(lower)) {
                return new Entry(null, 0, head + "：不是 Minecraft（attach 也失败），忽略", detail);
            }
            String kind = readable ? detectLauncher(lower, "Minecraft") : "Java 进程（读不到命令行）";
            TargetDescriptor t = new TargetDescriptor(c.pid, "?", kind, false, false, reason);
            return new Entry(t, retryAt, head + " " + kind + " → 无法连接：" + reason, detail);
        }
        String exe = c.exe.isEmpty() ? "java" : new File(c.exe).getName();
        String kind = "无法识别的 Java 进程（" + exe + "）";
        TargetDescriptor t = new TargetDescriptor(c.pid, "?", kind, false, false, reason);
        return new Entry(t, retryAt, head + " " + kind + " → 无法连接：" + reason,
            "路径：" + c.exe + "  ·  attach 异常：" + raw);
    }

    /**
     * 纯判定（不碰进程）：根据 attach 读到的属性 + jvmstat 命令行决定是不是游戏、能不能注入。
     * 只会读 {@code --gameDir/mods} 里的 jar 来查模组版，别的文件一概不碰。
     *
     * @return null 表示不是 Minecraft
     */
    static TargetDescriptor classify(String pid, String javaVersion, String dataModel,
                                     String classPath, String command, String displayName) {
        String combined = (classPath + " " + command + " " + displayName).toLowerCase(Locale.ROOT);
        if (!looksLikeMinecraft(combined)) return null;

        boolean java8 = javaVersion.startsWith("1.8");
        boolean lunar = combined.contains("lunar");
        boolean badlion = combined.contains("badlion") || combined.contains("blclient");
        boolean forge = combined.contains("minecraftforge") || combined.contains("fml");
        boolean launchWrapper = combined.contains("launchwrapper");
        boolean optifine = combined.contains("optifine");
        String forgeMc = forgeMcVersion(combined);
        boolean v189 = combined.contains("1.8.9") || combined.contains("1_8_9");
        // 不能按路径里的字样判断（实例文件夹常叫「BetterMyau自用」）；去 mods 里找真正带 myau/Myau.class 的 jar。
        boolean modPresent = modJarPresent(command, classPath);
        String kind = detectLauncher(combined, "Forge 1.8.9") + (optifine ? " · OptiFine" : "");

        if (lunar) {
            return new TargetDescriptor(pid, javaVersion, "Lunar Client", false, modPresent,
                "Lunar 不支持注入（Java 17、自有类名、关闭了 attach）");
        }
        if (badlion) {
            return new TargetDescriptor(pid, javaVersion, "Badlion Client", false, modPresent,
                "Badlion 不支持注入");
        }
        if ("32".equals(dataModel)) {
            return new TargetDescriptor(pid, javaVersion, kind, false, modPresent, attachFailureReason("32-bit"));
        }
        if (!java8) {
            return new TargetDescriptor(pid, javaVersion, kind, false, modPresent,
                "不是 Java 8（当前 " + javaVersion + "），注入版只支持 Forge 1.8.9");
        }
        if (!forge) {
            return new TargetDescriptor(pid, javaVersion, kind, false, modPresent, launchWrapper
                ? "只检测到 LaunchWrapper、没有 Forge（纯 OptiFine 实例？），注入版需要 Forge 1.8.9"
                : "没检测到 Forge，注入版只支持 Forge 1.8.9");
        }
        if (forgeMc != null && !forgeMc.equals("1.8.9")) {
            return new TargetDescriptor(pid, javaVersion, kind, false, modPresent,
                "这是 Minecraft " + forgeMc + " 的 Forge，注入版只支持 1.8.9");
        }
        if (modPresent) {
            return new TargetDescriptor(pid, javaVersion, kind, false, true,
                "已装 BetterMyau 模组版，请用不带它的实例再注入");
        }
        if (!v189) {
            // 有的启动器用包装器启动，classpath 里看不到 Forge 版本；注入时 Agent 会读 Forge 常量精确确认。
            return new TargetDescriptor(pid, javaVersion, kind, true, false, "可注入（版本注入时再确认）");
        }
        return new TargetDescriptor(pid, javaVersion, kind, true, false, "可注入");
    }

    /** attach 异常原文 → 给玩家看的中文原因（英文原文来自 JDK 的 attach.dll / Attach API）。 */
    static String attachFailureReason(String raw) {
        String l = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (l.contains("32-bit")) {
            return "这是 32 位 Java，注入器只支持 64 位：在启动器里把这个实例的 Java 换成 64 位 Java 8";
        }
        if (l.contains("lasterror=0x5") || l.contains("access is denied") || l.contains("拒绝访问")
            || l.contains("insufficient privileges")) {
            return "权限不足：游戏可能以管理员身份运行，请右键注入器 → 以管理员身份运行";
        }
        if (l.contains("createremotethread") || l.contains("remote thread") || l.contains("timed out")) {
            return "连接被拦截或超时：可能是安全软件拦截了注入器，把它加入信任后重试";
        }
        if (l.contains("does not support") && l.contains("attach")) {
            return "游戏禁用了 attach（启动参数里有 -XX:+DisableAttachMechanism），去掉后重启游戏";
        }
        return "无法连接游戏进程：" + shorten(raw == null ? "未知原因" : raw, 90);
    }

    static boolean looksLikeMinecraft(String lower) {
        return lower.contains("minecraft") || lower.contains("launchwrapper") || lower.contains("1.8.9")
            || lower.contains("lunar") || lower.contains("badlion") || lower.contains("blclient")
            || lower.contains("prismlauncher") || lower.contains("multimc") || lower.contains("polymc");
    }

    /** 从 classpath 里的 Forge 库路径取 MC 版本（…/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/…）。 */
    static String forgeMcVersion(String lower) {
        Matcher m = FORGE_LIB.matcher(lower);
        return m.find() ? m.group(1) : null;
    }

    /** 启动命令打码：令牌、UUID、会话一律换成 ***。 */
    static String redact(String command) {
        return command == null ? "" : SECRET_ARG.matcher(command).replaceAll("$1***");
    }

    // ───────────────────────── 兜底的进程枚举 ─────────────────────────

    /** 不在 VirtualMachine.list() 里的 java.exe / javaw.exe（同一用户的，或者读不到属主的——比如管理员身份运行的游戏）。 */
    private static List<Candidate> unlistedJavaProcesses(Set<String> listed, String ownPid) {
        List<Candidate> out = new ArrayList<>();
        Optional<String> me = ProcessHandle.current().info().user();
        try {
            ProcessHandle.allProcesses().forEach(ph -> {
                String pid = String.valueOf(ph.pid());
                if (pid.equals(ownPid) || listed.contains(pid)) return;
                ProcessHandle.Info info = ph.info();
                String exe = info.command().orElse("");
                String name = new File(exe).getName().toLowerCase(Locale.ROOT);
                if (!name.equals("java.exe") && !name.equals("javaw.exe")
                    && !name.equals("java") && !name.equals("javaw")) return;
                Optional<String> user = info.user();
                if (user.isPresent() && me.isPresent() && !user.get().equalsIgnoreCase(me.get())) return;
                // 刚启动的 JVM 还没建好 hsperfdata、attach 也常失败；等它站稳了再判，免得任何 Java 程序一启动就闪一张灰卡片。
                boolean young = info.startInstant()
                    .map(s -> java.time.Duration.between(s, java.time.Instant.now()).toMillis() < UNLISTED_MIN_AGE_MS)
                    .orElse(false);
                if (young) return;
                out.add(new Candidate(pid, null, false, exe));
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ───────────────────────── 杂项 ─────────────────────────

    /** 在 classpath 和 --gameDir/mods 里找含 myau/Myau.class 的 jar。判断不了就当没有（Agent 进程内还会精确复查）。 */
    private static boolean modJarPresent(String command, String classPath) {
        List<File> jars = new ArrayList<>();
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.toLowerCase(Locale.ROOT).endsWith(".jar")) jars.add(new File(entry));
        }
        String gameDir = argValue(command, "--gameDir");
        if (gameDir != null) {
            File[] mods = new File(gameDir, "mods").listFiles(
                f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (mods != null) jars.addAll(java.util.Arrays.asList(mods));
        }
        for (File jar : jars) {
            if (!jar.isFile()) continue;
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
                if (jf.getEntry("myau/Myau.class") != null) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 取 sun.java.command 里某个参数的值；路径可能含空格，取到下一个 " --" 为止。 */
    private static String argValue(String command, String key) {
        int i = command.indexOf(key + " ");
        if (i < 0) return null;
        int start = i + key.length() + 1;
        int end = command.indexOf(" --", start);
        String v = (end < 0 ? command.substring(start) : command.substring(start, end)).trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        return v.isEmpty() ? null : v;
    }

    private static String detectLauncher(String lower, String fallback) {
        if (lower.contains("pcl") || lower.contains("plain craft")) return "PCL";
        if (lower.contains("hmcl")) return "HMCL";
        if (lower.contains("prismlauncher")) return "Prism Launcher";
        if (lower.contains("\\.minecraft\\versions") || lower.contains("minecraftlauncher")) return "官方启动器";
        return fallback;
    }

    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 300; t = t.getCause()) {
            if (sb.length() > 0) sb.append(" ← ");
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
            if (t.getCause() == t) break;
        }
        return sb.toString();
    }

    private static String shorten(String s, int max) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + " …";
    }

    /** 扫描诊断日志：每次启动注入器覆盖重写，测试者识别不到游戏时把它发回来。 */
    private static final class ScanLog {
        static final Path PATH = logDir().resolve("scan.log");
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        private boolean truncated;

        private static Path logDir() {
            String local = System.getenv("LOCALAPPDATA");
            if (local == null || local.isBlank()) local = System.getProperty("user.home");
            return Path.of(local, "BetterMyau-Inject", "logs");
        }

        void header() {
            String tmp = System.getProperty("java.io.tmpdir", "");
            String user = System.getProperty("user.name", "");
            File perf = new File(tmp, "hsperfdata_" + user);
            String[] perfFiles = perf.list();
            line("──── 全量扫描 ────  注入器 " + Optional.ofNullable(TargetScanner.class.getPackage())
                .map(Package::getImplementationVersion).orElse("dev")
                + " · Java " + System.getProperty("java.version") + " " + System.getProperty("os.arch")
                + " · " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
            line("临时目录 " + tmp + " · " + perf.getName() + (perfFiles == null ? " 不存在"
                : " 里有 " + perfFiles.length + " 个 JVM 记录"));
        }

        synchronized void line(String text) {
            try {
                Files.createDirectories(PATH.getParent());
                String row = LocalDateTime.now().format(TIME) + " " + text + System.lineSeparator();
                if (!truncated) {
                    Files.writeString(PATH, row, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    truncated = true;
                } else {
                    Files.writeString(PATH, row, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (IOException ignored) {
            }
        }
    }
}
