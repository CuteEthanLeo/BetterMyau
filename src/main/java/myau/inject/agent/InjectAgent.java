package myau.inject.agent;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注入版唯一入口，由控制器通过 Attach API 的 {@code loadAgent} 调用。
 *
 * <p>这个类刻意只依赖 JDK 和反射：Java Agent 的类一旦进入目标 JVM 的系统类加载器就无法卸载，
 * 重新编译后再次注入时 {@code myau.inject.*} 仍解析到旧类，只有保持入口自包含，才能在被旧版
 * 污染的 JVM 里跑完并把原因说清楚。它本身被系统类加载器加载，看不到游戏类；真正干活的
 * {@link #RUNTIME_CLASS} 由 LaunchClassLoader 加载，和模组版处在同一个类加载器里。
 *
 * <p>最重要的契约：{@link #agentmain} 永不抛异常。一旦抛出，JDK 只会压成
 * AgentInitializationException，真实原因就永久丢失。协议与 FPS+Injection 一致，便于沿用控制器。
 */
public final class InjectAgent {
    /** 必须与控制器 Protocol.VERSION 一致；此处内联以保持自包含。 */
    private static final int PROTOCOL_VERSION = 1;
    /** {@link #run} 的返回值：失败已由运行时自行上报，无需重复上报。 */
    private static final String ALREADY_REPORTED = "";
    private static final String RUNTIME_CLASS = "myau.inject.InjectRuntime";
    private static final String LAUNCH_CLASS = "net.minecraft.launchwrapper.Launch";
    private static final String MINECRAFT_CLASS = "net.minecraft.client.Minecraft";

    /** 构建标识，由构建期写入 jar 清单的 BetterMyau-Inject-Build；检测旧版残留用。 */
    private static boolean entered;

    private InjectAgent() {
    }

    public static synchronized void agentmain(String arguments, Instrumentation instrumentation) {
        Map<String, String> fields = parse(arguments);
        InjectDiagnosticLog log = InjectDiagnosticLog.open(fields.get("port"));
        try {
            log.write("参数: " + redact(arguments), null);
            log.write("入口 JAR: " + ownLocation(), null);
            log.write("回环地址: 本进程 " + safeLoopback() + "，控制器指定 " + fields.get("host"), null);
            String failure = run(arguments, instrumentation, fields, log);
            if (failure == null) {
                log.write("注入成功", null);
                report(fields, null, log);
            } else if (ALREADY_REPORTED.equals(failure)) {
                log.write("注入失败，原因已由运行时回传", null);
            } else {
                report(fields, failure, log);
            }
        } catch (Throwable error) {
            String detail = "Agent 引导阶段失败: " + describe(error);
            log.write(detail, error);
            report(fields, detail, log);
        } finally {
            log.close();
        }
    }

    /** @return null 表示成功，{@link #ALREADY_REPORTED} 表示已上报，其它字符串是待上报的失败原因。 */
    private static String run(String arguments, Instrumentation instrumentation,
                             Map<String, String> fields, InjectDiagnosticLog log) {
        if (entered) {
            return "本进程已经注入过 BetterMyau-Inject。Java Agent 无法从 JVM 卸载，换版本请先重启 Minecraft。";
        }
        String env = checkEnvironment(instrumentation, log);
        if (env != null) return env;

        Object lcl = launchClassLoader(log);
        if (lcl == null) {
            return "没有找到 LaunchWrapper 的类加载器，无法注入。请确认目标是 Forge 1.8.9 客户端。";
        }
        if (modVersionPresent(lcl)) {
            return "目标已经装了 BetterMyau 模组版（找到 myau/Myau.class）。请用不带 BetterMyau 的实例再注入。";
        }
        try {
            forgetNegativeCache(lcl);
            URL payload = ownJarUrl();
            if (payload == null) return "无法定位注入载荷 JAR。";
            addUrl(lcl, payload);
            log.write("载荷已加入 LaunchClassLoader: " + payload, null);
        } catch (Throwable error) {
            return "把载荷加入类加载器失败: " + describe(error);
        }

        try {
            entered = true;
            Class<?> runtime = Class.forName(RUNTIME_CLASS, true, (ClassLoader) lcl);
            if (runtime.getClassLoader() != lcl) {
                return "运行时类不是由 LaunchClassLoader 加载（实际 "
                    + loaderName(runtime.getClassLoader()) + "）。它会看不到游戏类，注入中止。";
            }
            Method run = runtime.getMethod("run", Instrumentation.class, String.class, String.class);
            Object result = run.invoke(null, instrumentation, arguments, log.path());
            // 运行时返回 null=成功；非空=失败原因（已尽量不改动游戏）。
            return result == null ? null : String.valueOf(result);
        } catch (Throwable error) {
            return "运行时初始化失败: " + describe(error);
        }
    }

    /** 逐项检查运行环境；返回非空即为拒绝原因。 */
    private static String checkEnvironment(Instrumentation instrumentation, InjectDiagnosticLog log) {
        String java = System.getProperty("java.version", "");
        if (!java.startsWith("1.8")) {
            return "只支持 Java 8（Forge 1.8.9 的运行环境），当前是 " + java + "。";
        }
        if (!classExists(LAUNCH_CLASS)) {
            return "没有检测到 LaunchWrapper，判断不是 Forge 1.8.9 客户端。";
        }
        Object lcl = launchClassLoader(log);
        if (lcl == null) return "LaunchWrapper 存在但取不到类加载器。";
        ClassLoader cl = (ClassLoader) lcl;
        // Forge 1.8.9：FML 的核心类必须在。
        if (!resourceExists(cl, "net/minecraftforge/fml/common/Loader.class")) {
            return "没有检测到 Forge（net.minecraftforge.fml）。注入版只支持 Forge 1.8.9。";
        }
        // 正式环境里原版 jar 是混淆的（Minecraft 实际是 ave.class），类名由 FML 在加载时重映射，
        // 所以不能按资源名找；改为看 JVM 里是否已有加载好的 net.minecraft.client.Minecraft。
        Class<?> minecraft = loadedClass(instrumentation, MINECRAFT_CLASS);
        if (minecraft == null) {
            return "没有检测到已加载的 net.minecraft.client.Minecraft。请等游戏进入主菜单后再注入。";
        }
        log.write("找到 " + MINECRAFT_CLASS + "（类加载器 " + loaderName(minecraft.getClassLoader()) + "）", null);
        // 控制器只能从启动参数猜版本（有的启动器看不到 classpath），这里用 Forge 自己的常量精确确认。
        String mc = forgeMcVersion(instrumentation);
        if (mc != null && !"1.8.9".equals(mc)) {
            return "这是 Minecraft " + mc + " 的 Forge，注入版只支持 Forge 1.8.9。";
        }
        log.write("环境检查通过: Java " + java + "，Forge " + (mc == null ? "1.8.9（版本常量读不到，按 1.8.9 处理）" : mc)
            + "，LaunchWrapper 就绪", null);
        return null;
    }

    /** 读 Forge 的 Loader.MC_VERSION / ForgeVersion.mcVersion（取已加载的类，不触发加载）；读不到返回 null。 */
    private static String forgeMcVersion(Instrumentation instrumentation) {
        String[][] candidates = {
            {"net.minecraftforge.fml.common.Loader", "MC_VERSION"},
            {"net.minecraftforge.common.ForgeVersion", "mcVersion"},
        };
        for (String[] c : candidates) {
            try {
                Class<?> cls = loadedClass(instrumentation, c[0]);
                if (cls == null) continue;
                Object v = cls.getField(c[1]).get(null);
                if (v instanceof String && !((String) v).isEmpty()) return (String) v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean modVersionPresent(Object lcl) {
        // 只用 findResource：findClass 会把找不到的 myau.* 记进负缓存，之后 addURL 也加载不出来。
        return resourceExists((ClassLoader) lcl, "myau/Myau.class");
    }

    /** addURL 前清掉 LaunchClassLoader 负缓存里 myau. 开头的条目，避免上次注入失败留下的记录挡路。 */
    @SuppressWarnings("unchecked")
    private static void forgetNegativeCache(Object lcl) {
        for (String fieldName : new String[]{"invalidClasses", "negativeResourceCache"}) {
            try {
                Field f = lcl.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object value = f.get(lcl);
                if (value instanceof java.util.Set) {
                    java.util.Set<String> set = (java.util.Set<String>) value;
                    set.removeIf(name -> name != null && name.startsWith("myau."));
                }
            } catch (Throwable ignored) {
                // 字段名随版本不同可能缺失；缺了也不致命。
            }
        }
    }

    private static Object launchClassLoader(InjectDiagnosticLog log) {
        try {
            Class<?> launch = Class.forName(LAUNCH_CLASS);
            Field f = launch.getField("classLoader");
            return f.get(null);
        } catch (Throwable error) {
            if (log != null) log.write("取 Launch.classLoader 失败", error);
            return null;
        }
    }

    private static void addUrl(Object lcl, URL url) throws Exception {
        Method add = lcl.getClass().getMethod("addURL", URL.class);
        add.setAccessible(true);
        add.invoke(lcl, url);
    }

    private static Class<?> loadedClass(Instrumentation instrumentation, String name) {
        if (instrumentation == null) return null;
        try {
            for (Class<?> c : instrumentation.getAllLoadedClasses()) {
                if (c != null && name.equals(c.getName())) return c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, InjectAgent.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean resourceExists(ClassLoader cl, String path) {
        try {
            return cl.getResource(path) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static URL ownJarUrl() {
        try {
            return InjectAgent.class.getProtectionDomain().getCodeSource().getLocation();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String loaderName(ClassLoader cl) {
        return cl == null ? "bootstrap" : cl.getClass().getName();
    }

    // ───────────────────────── 失败回报（协议与 FPS+Injection 一致） ─────────────────────────

    private static void report(Map<String, String> fields, String detail, InjectDiagnosticLog log) {
        boolean ok = detail == null;
        log.write(ok ? "上报成功握手" : "上报失败原因: " + detail, null);
        Socket socket = null;
        try {
            int protocol = integer(fields.get("protocol"), -1);
            int port = integer(fields.get("port"), -1);
            String token = fields.get("token");
            if (!isReportProtocolAccepted(protocol) || port < 1 || port > 65535
                || token == null || token.length() < 32 || token.length() > 256) {
                log.write("参数不合法，无法回传；原因只保留在本日志中", null);
                return;
            }
            socket = new Socket(Proxy.NO_PROXY);
            socket.connect(new InetSocketAddress(address(fields.get("host")), port), 5000);
            socket.setTcpNoDelay(true);
            PrintWriter output = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            Map<String, String> hello = new LinkedHashMap<String, String>();
            hello.put("type", "hello");
            hello.put("protocol", String.valueOf(protocol));
            hello.put("token", token);
            hello.put("minecraftVersion", "1.8.9");
            hello.put("ready", ok ? "true" : "false");
            if (!ok) hello.put("error", detail);
            hello.put("logPath", log.path());
            output.println(json(hello));
            log.write(output.checkError() ? "回传失败" : "回传完成", null);
        } catch (Throwable error) {
            log.write("回传失败，原因只保留在本日志中", error);
        } finally {
            try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        }
    }

    static boolean isReportProtocolAccepted(int protocol) {
        return protocol >= 1 && protocol <= 999;
    }

    private static String safeLoopback() {
        try {
            return String.valueOf(InetAddress.getLoopbackAddress());
        } catch (Throwable error) {
            return "无法确定（" + error.getClass().getName() + "）";
        }
    }

    private static InetAddress address(String host) {
        if (host != null && !host.trim().isEmpty()) {
            try { return InetAddress.getByName(host.trim()); } catch (Throwable ignored) { }
        }
        return InetAddress.getLoopbackAddress();
    }

    static Map<String, String> parse(String value) {
        Map<String, String> result = new HashMap<String, String>();
        if (value == null) return result;
        for (String part : value.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0) result.put(part.substring(0, separator), part.substring(separator + 1));
        }
        return result;
    }

    static String redact(String arguments) {
        if (arguments == null) return "";
        StringBuilder result = new StringBuilder();
        String[] parts = arguments.split(";");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) result.append(';');
            result.append(parts[i].startsWith("token=") ? "token=***" : parts[i]);
        }
        return result.toString();
    }

    static String json(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(escape(entry.getKey())).append("\":\"")
                .append(escape(entry.getValue() == null ? "" : entry.getValue())).append('"');
        }
        return out.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return fallback; }
    }

    private static String ownLocation() {
        try {
            return String.valueOf(InjectAgent.class.getProtectionDomain().getCodeSource().getLocation());
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    static String describe(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 8) {
            if (depth > 0) result.append(" <- ");
            result.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) result.append(": ").append(message.trim());
            current = current.getCause();
            depth++;
        }
        return result.length() > 1800 ? result.substring(0, 1800) : result.toString();
    }
}
