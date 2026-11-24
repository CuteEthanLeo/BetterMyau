package myau.inject.mixin;

import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.util.ReEntranceLock;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;

/**
 * 注入版自写的 Mixin 宿主服务，直接实现 {@link IMixinService}（不继承 LaunchWrapper 版——它的
 * {@code getSideName()} 是 final，而 BetterMyau 大量 Mixin 带 {@code @SideOnly(CLIENT)}，必须让服务返回
 * {@code CLIENT}，否则这些 Mixin 会被整类跳过。已由离线 spike 验证）。
 *
 * <p>设计要点（spike 确认）：
 * <ul>
 *   <li>{@link #getInitialPhase()} 返回 {@code DEFAULT}，让晚加的配置落在当前相位、立即被选中；</li>
 *   <li>{@link #getPlatformAgents()} 返回空，避免 FML 平台代理在裸环境空指针，也不扫描别家 Mixin；</li>
 *   <li>类字节、资源、类加载全部走 {@link GameClassSource}；游戏里是 LaunchClassLoader，自检里是 jar。</li>
 * </ul>
 *
 * <p>经 Shadow 重定位后，本类实现的其实是 {@code myau.inject.shadow.asm.service.IMixinService}，和游戏里
 * 未重定位的那套 Mixin 互不相干。ServiceLoader 无参实例化本类，所以来源用静态注入。
 */
public final class InjectMixinService implements IMixinService, IClassProvider, IClassBytecodeProvider {
    private static volatile GameClassSource source;

    private final ReEntranceLock lock = new ReEntranceLock(1);

    /** 引导引擎前由 {@code InjectRuntime} 注入来源。 */
    public static void setSource(GameClassSource gameClassSource) {
        source = gameClassSource;
    }

    private static GameClassSource source() {
        GameClassSource s = source;
        if (s == null) throw new IllegalStateException("GameClassSource 尚未设置");
        return s;
    }

    // ───────────────────────── IMixinService ─────────────────────────

    @Override public String getName() { return "BetterMyauInject"; }

    @Override public boolean isValid() { return true; }

    @Override public void prepare() { }

    @Override public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }

    @Override public void init() { }

    @Override public void beginPhase() { }

    @Override public void checkEnv(Object bootSource) { }

    @Override public ReEntranceLock getReEntranceLock() { return lock; }

    @Override public IClassProvider getClassProvider() { return this; }

    @Override public IClassBytecodeProvider getBytecodeProvider() { return this; }

    @Override public Collection<String> getPlatformAgents() { return Collections.emptyList(); }

    @Override public InputStream getResourceAsStream(String name) { return source().resource(name); }

    @Override public void registerInvalidClass(String className) { }

    @Override public boolean isClassLoaded(String className) { return false; }

    @Override public String getClassRestrictions(String className) { return ""; }

    @Override public Collection<ITransformer> getTransformers() { return Collections.emptyList(); }

    @Override public String getSideName() { return "CLIENT"; }

    // ───────────────────────── IClassProvider ─────────────────────────

    @Override public URL[] getClassPath() { return new URL[0]; }

    @Override public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name.replace('/', '.'), false, source().loader());
    }

    @Override public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name.replace('/', '.'), initialize, source().loader());
    }

    @Override public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name.replace('/', '.'), initialize, source().loader());
    }

    // ───────────────────────── IClassBytecodeProvider ─────────────────────────

    @Override public byte[] getClassBytes(String name, String transformedName) throws IOException {
        byte[] bytes = source().classBytes(transformedName != null ? transformedName : name);
        if (bytes == null && name != null) bytes = source().classBytes(name);
        if (bytes == null) throw new IOException("找不到类字节: " + name);
        return bytes;
    }

    @Override public byte[] getClassBytes(String className, boolean runTransformers)
            throws ClassNotFoundException, IOException {
        byte[] bytes = source().classBytes(className);
        if (bytes == null) throw new ClassNotFoundException(className);
        return bytes;
    }

    @Override public ClassNode getClassNode(String className) throws ClassNotFoundException, IOException {
        byte[] bytes = getClassBytes(className, true);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }
}
