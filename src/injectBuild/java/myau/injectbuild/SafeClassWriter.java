package myau.injectbuild;

import org.objectweb.asm.ClassWriter;

/**
 * 只给构建期生成的类（Acc、各 State）用的 ClassWriter。这些方法都是直线代码、没有控制流合并点，
 * COMPUTE_FRAMES 不会调用 getCommonSuperClass；万一调用，退回 {@code java/lang/Object} 兜底，避免构建期
 * 去加载 net.minecraft / myau 的类。
 */
final class SafeClassWriter extends ClassWriter {
    SafeClassWriter(int flags) {
        super(flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        return "java/lang/Object";
    }
}
