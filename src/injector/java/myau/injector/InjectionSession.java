package myau.injector;

import com.sun.tools.attach.VirtualMachine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 一次注入会话：开回环 ServerSocket → attach 目标 → loadAgent（把令牌/端口随参数传过去）→ 等 Agent 用令牌
 * 回连并回报 ready。改编自 FPS+Injection，并对「JDK 21 控制器 attach Java 8 目标」更稳健：不管 loadAgent
 * 是否抛异常，都以 Agent 经 socket 回报的 ready 为准——ready=true 即成功。
 */
public final class InjectionSession {
    private static final byte[] LOOPBACK_V4 = {127, 0, 0, 1};
    private static final int LOG_TAIL_LINES = 40;

    /** 注入进行到哪一步（回调时表示「开始进入这一步」），供界面画步骤进度。 */
    public enum Stage { PREPARE, ATTACH, LOAD_AGENT, WAIT_AGENT, DONE }

    private final Consumer<String> log;
    private final Consumer<Stage> stage;

    public InjectionSession(Consumer<String> log) {
        this(log, s -> { });
    }

    public InjectionSession(Consumer<String> log, Consumer<Stage> stage) {
        this.log = log;
        this.stage = stage;
    }

    /** @return 成功返回 null；失败返回中文原因。 */
    public String inject(TargetDescriptor target) {
        if (!target.compatible) return "目标不可注入：" + target.status;
        String token = createToken();
        ServerSocket server = null;
        try {
            stage.accept(Stage.PREPARE);
            AgentJarLocator.Staged agent = AgentJarLocator.stage();
            log.accept("载荷：" + agent.jar + "（构建 " + agent.buildId + "）");

            InetAddress loopback = InetAddress.getByAddress(LOOPBACK_V4);
            server = new ServerSocket();
            server.bind(new InetSocketAddress(loopback, 0), 4);
            server.setSoTimeout(15000);
            int port = server.getLocalPort();

            String arguments = "protocol=" + Protocol.VERSION
                + ";host=" + loopback.getHostAddress()
                + ";port=" + port
                + ";token=" + token;

            log.accept("attach PID " + target.pid + " …");
            Exception loadError = null;
            VirtualMachine vm = null;
            try {
                stage.accept(Stage.ATTACH);
                vm = VirtualMachine.attach(target.pid);
                stage.accept(Stage.LOAD_AGENT);
                vm.loadAgent(agent.jar.toString(), arguments);
                log.accept("loadAgent 返回");
            } catch (Exception e) {
                // loadAgent 抛异常不代表 Agent 没干活：跨版本 attach 常会这样。以 socket 回报为准。
                String msg = String.valueOf(e.getMessage());
                if (msg.trim().endsWith(": 0")) {
                    // JDK 21 控制器 attach Java 8 目标时，返回码 0（成功）也会被当成异常抛出。
                    log.accept("loadAgent 返回（跨版本 attach 的返回码 0，属正常）");
                } else {
                    loadError = e;
                    log.accept("loadAgent 抛异常（将以 Agent 回报为准）：" + msg);
                }
            } finally {
                if (vm != null) try { vm.detach(); } catch (Exception ignored) { }
            }

            // 等 Agent 回连（Agent 在游戏里跑完 BetterMyau 的启动才回报）。
            stage.accept(Stage.WAIT_AGENT);
            Map<String, String> hello;
            try (Socket accepted = server.accept()) {
                accepted.setSoTimeout(10000);
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                hello = parseJson(reader.readLine());
            } catch (Exception acceptError) {
                String tail = readAgentLogTail(port);
                if (!tail.isEmpty()) {
                    return "Agent 未完成回连；本次诊断日志：\n" + tail;
                }
                if (loadError != null) {
                    return "注入失败：" + loadError.getMessage() + "\n（也没等到 Agent 回连，请重启 Minecraft 后重试）";
                }
                return "等待 Agent 回连超时（15 秒）。请确认安全软件未拦截 127.0.0.1，并重启 Minecraft 后重试。";
            }

            if (!token.equals(hello.get("token"))) {
                return "回连令牌不匹配，拒绝（可能是旧注入残留）。请重启 Minecraft 后重试。";
            }
            if ("true".equalsIgnoreCase(hello.get("ready"))) {
                log.accept("Agent 回报成功。");
                stage.accept(Stage.DONE);
                return null;
            }
            String err = hello.get("error");
            String logPath = hello.get("logPath");
            StringBuilder sb = new StringBuilder("注入失败：");
            sb.append(err == null || err.isBlank() ? "Agent 未说明原因" : err);
            if (logPath != null && !logPath.isBlank()) sb.append("\nAgent 日志：").append(logPath);
            return sb.toString();
        } catch (Exception e) {
            return "注入过程出错：" + e;
        } finally {
            if (server != null) try { server.close(); } catch (Exception ignored) { }
        }
    }

    // ───────────────────────── 杂项 ─────────────────────────

    private static String createToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> parseJson(String line) {
        Map<String, String> map = new HashMap<>();
        if (line == null) return map;
        line = line.trim();
        if (line.startsWith("{")) line = line.substring(1);
        if (line.endsWith("}")) line = line.substring(0, line.length() - 1);
        boolean inStr = false, escape = false;
        StringBuilder cur = new StringBuilder();
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escape) { cur.append(unescape(c)); escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (c == ',' && !inStr) { tokens.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        for (String t : tokens) {
            int colon = t.indexOf(':');
            if (colon <= 0) continue;
            String k = t.substring(0, colon).trim();
            String v = t.substring(colon + 1).trim();
            map.put(k, v);
        }
        return map;
    }

    private static char unescape(char c) {
        switch (c) {
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            default: return c;
        }
    }

    private static String readAgentLogTail(int port) {
        try {
            String local = System.getenv("LOCALAPPDATA");
            if (local == null || local.isBlank()) local = System.getProperty("user.home");
            Path logFile = Path.of(local, "BetterMyau-Inject", "logs", "agent-" + port + ".log");
            if (!Files.isRegularFile(logFile)) return "";
            var lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (Exception ignored) {
            return "";
        }
    }
}
