package myau.injector;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 定位注入载荷 jar，并复制到按内容哈希命名的缓存目录后返回。被注入的 Minecraft 进程会长期锁住它加载过的
 * Agent jar；attach 缓存副本而非应用目录里的原件，游戏运行期间也能随时重新打包。改编自 FPS+Injection。
 *
 * <p>注意：控制器 jar（BetterMyau-Inject-Controller-*.jar）和载荷放在同一目录、名字也匹配同一通配符，
 * 只能按清单里的 {@code Agent-Class} 区分。误把控制器交给 loadAgent 时，目标 JVM 只会回一个含糊的
 * 「Failed to load agent library: 100」（JPLIS AGENT_ERROR_BADJAR）。
 */
public final class AgentJarLocator {
    private AgentJarLocator() {
    }

    public static final class Staged {
        public final Path jar;
        public final String buildId;
        Staged(Path jar, String buildId) {
            this.jar = jar;
            this.buildId = buildId;
        }
    }

    public static Staged stage() throws Exception {
        Path source = locate();
        String buildId = sha256(source).substring(0, 16);
        Path dir = cacheRoot().resolve(buildId);
        Path staged = dir.resolve("BetterMyau-Inject.jar");
        if (!Files.isRegularFile(staged) || Files.size(staged) != Files.size(source) || !isAgentJar(staged)) {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("BetterMyau-Inject.jar.tmp");
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                if (!Files.isRegularFile(staged)) throw e;
            }
        }
        if (!isAgentJar(staged)) {
            throw new IllegalStateException("载荷 jar 缺少 Agent-Class 清单：" + staged + "（来源 " + source + "）");
        }
        return new Staged(staged.toAbsolutePath().normalize(), buildId);
    }

    /** 载荷查找顺序：系统属性 → 可执行目录 → 可执行目录旁的 app/ → 开发产物目录。每个目录取最新的有效载荷。 */
    static Path locate() {
        String explicit = System.getProperty("bettermyau.inject.agentJar");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit).toAbsolutePath().normalize();
            if (!Files.isRegularFile(p)) throw new IllegalStateException("指定的载荷 jar 不存在：" + p);
            if (!isAgentJar(p)) throw new IllegalStateException("指定的 jar 不是注入载荷（清单缺少 Agent-Class）：" + p);
            return p;
        }

        List<Path> dirs = new ArrayList<>();
        try {
            URI codeSource = AgentJarLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path self = Path.of(codeSource);
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            if (dir != null) {
                dirs.add(dir);
                dirs.add(dir.resolve("app"));
            }
        } catch (Exception ignored) {
        }
        dirs.add(Path.of(".gradle-ui", "ui-build", "inject", "libs").toAbsolutePath());
        dirs.add(Path.of("build", "inject", "libs").toAbsolutePath());

        for (Path dir : dirs) {
            Path found = newestAgentJar(dir);
            if (found != null) return found.toAbsolutePath().normalize();
        }
        StringBuilder sb = new StringBuilder("找不到注入载荷 jar（BetterMyau-Inject-*.jar，且清单带 Agent-Class）。已搜索：");
        for (Path d : dirs) sb.append("\n  ").append(d.toAbsolutePath().normalize());
        throw new IllegalStateException(sb.toString());
    }

    private static Path newestAgentJar(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        try (var stream = Files.newDirectoryStream(dir, "BetterMyau-Inject-*.jar")) {
            for (Path p : stream) {
                if (p.getFileName().toString().contains("-Controller-")) continue;
                if (!Files.isRegularFile(p) || !isAgentJar(p)) continue;
                long t = Files.getLastModifiedTime(p).toMillis();
                if (best == null || t > bestTime) {
                    best = p;
                    bestTime = t;
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    /** 清单里有非空 Agent-Class 才算注入载荷。 */
    static boolean isAgentJar(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) return false;
            String agentClass = mf.getMainAttributes().getValue(new Attributes.Name("Agent-Class"));
            return agentClass != null && !agentClass.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    static Path cacheRoot() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) local = System.getProperty("user.home");
        return Path.of(local, "BetterMyau-Inject", "agent-cache");
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) >= 0) digest.update(buf, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }
}
