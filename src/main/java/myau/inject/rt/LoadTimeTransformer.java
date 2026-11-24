package myau.inject.rt;

import org.spongepowered.asm.mixin.transformer.Proxy;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

/**
 * 给注入之后才加载的目标类套用 Mixin（如 GuiConnecting、GuiYesNo 等）。同一条「套用 → 后处理」流水线，
 * 只是在类定义时现场跑。{@code classBeingRedefined != null} 时跳过，避免我们自己 {@code redefineClasses}
 * 那一遍又被重复套用。
 */
public final class LoadTimeTransformer implements ClassFileTransformer {
    private final ClassLoader gameLoader;
    private final Set<String> targets; // 内部名（斜杠）
    private final Proxy proxy;

    public LoadTimeTransformer(ClassLoader gameLoader, Set<String> targets, Proxy proxy) {
        this.gameLoader = gameLoader;
        this.targets = targets;
        this.proxy = proxy;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classBeingRedefined != null) return null;
        if (loader != gameLoader) return null;
        if (!targets.contains(className)) return null;
        try {
            String dotted = className.replace('/', '.');
            byte[] applied = proxy.transformClassBytes(dotted, dotted, classfileBuffer);
            if (applied == null || applied == classfileBuffer || java.util.Arrays.equals(applied, classfileBuffer)) {
                return null;
            }
            return PostProcessor.finish(dotted, classfileBuffer, applied);
        } catch (Throwable error) {
            // 单个类套用失败不拖垮类加载：保持原样加载，并在诊断日志留痕。
            InjectDiagnostics.warn("加载期套用失败 " + className, error);
            return null;
        }
    }
}
