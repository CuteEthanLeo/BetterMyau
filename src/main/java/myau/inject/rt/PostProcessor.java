package myau.inject.rt;

import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.ClassWriter;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldNode;
import org.spongepowered.asm.lib.tree.MethodNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把 Mixin 套用后的类字节，整理成 HotSpot {@code redefineClasses} 能接受的形状，并顺带校验没有发生
 * 非法的结构改动（已由 spike 验证规则）。
 *
 * <ul>
 *   <li>Mixin 新增的处理/辅助方法都是 {@code private} 实例方法；redefine 只接受新增 {@code private final}
 *       或 {@code private static} 方法，所以给新增的 private 实例方法补上 {@code final}。</li>
 *   <li>class 版本从 50 升到 52（Mixin 合并进方法引用导致）——redefine 接受，保持原样。</li>
 *   <li>校验：父类、接口、字段集合、已有方法的修饰符都不得变化；不得新增非 private 方法。任一不满足
 *       就抛错，整次注入中止、什么都不改。构建期已把新增字段挪到旁路存储，这里是最后一道防线。</li>
 * </ul>
 */
public final class PostProcessor {
    private PostProcessor() {
    }

    public static final class Rejection extends RuntimeException {
        public Rejection(String message) { super(message); }
    }

    /**
     * @param originalBytes 目标类当前定义（抓取到的字节）
     * @param appliedBytes  Mixin 套用后的字节
     * @return 可直接交给 redefineClasses 的字节
     * @throws Rejection 发现非法结构改动时
     */
    public static byte[] finish(String target, byte[] originalBytes, byte[] appliedBytes) {
        ClassNode original = read(originalBytes);
        ClassNode applied = read(appliedBytes);

        if (!eq(original.superName, applied.superName)) {
            throw new Rejection(target + ": 父类被改动（" + original.superName + " → " + applied.superName + "）");
        }
        if (!new HashSet<String>(nz(original.interfaces)).equals(new HashSet<String>(nz(applied.interfaces)))) {
            throw new Rejection(target + ": 接口集合被改动，redefine 不允许新增接口");
        }

        // 字段集合必须一致（构建期已把新增字段挪到旁路存储）。
        Set<String> of = fieldKeys(original);
        Set<String> af = fieldKeys(applied);
        if (!of.equals(af)) {
            Set<String> added = new HashSet<String>(af); added.removeAll(of);
            Set<String> removed = new HashSet<String>(of); removed.removeAll(af);
            throw new Rejection(target + ": 字段集合变化，redefine 不允许加/删字段。新增=" + added + " 删除=" + removed);
        }

        // 已有方法的修饰符不得变化。
        Map<String, MethodNode> originalMethods = methodMap(original);
        for (MethodNode m : applied.methods) {
            MethodNode o = originalMethods.get(key(m));
            if (o != null) {
                if (o.access != m.access) {
                    throw new Rejection(target + ": 已有方法修饰符被改动 " + m.name + m.desc
                        + "（0x" + Integer.toHexString(o.access) + " → 0x" + Integer.toHexString(m.access) + "）");
                }
            } else {
                // 新增方法：必须是 private；给 private 实例方法补 final。
                boolean isPrivate = (m.access & Opcodes.ACC_PRIVATE) != 0;
                boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
                if (!isPrivate) {
                    throw new Rejection(target + ": 新增了非 private 方法 " + m.name + m.desc
                        + "（0x" + Integer.toHexString(m.access) + "），redefine 不允许");
                }
                if (!isStatic && (m.access & Opcodes.ACC_FINAL) == 0) {
                    m.access |= Opcodes.ACC_FINAL;
                }
            }
        }

        ClassWriter writer = new ClassWriter(0);
        applied.accept(writer);
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Set<String> fieldKeys(ClassNode node) {
        Set<String> keys = new HashSet<String>();
        if (node.fields != null) {
            for (FieldNode f : node.fields) keys.add(f.name + ":" + f.desc + ":" + f.access);
        }
        return keys;
    }

    private static Map<String, MethodNode> methodMap(ClassNode node) {
        Map<String, MethodNode> map = new HashMap<String, MethodNode>();
        if (node.methods != null) {
            for (MethodNode m : node.methods) map.put(key(m), m);
        }
        return map;
    }

    private static String key(MethodNode m) { return m.name + m.desc; }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static <T> java.util.List<T> nz(java.util.List<T> list) {
        return list == null ? java.util.Collections.<T>emptyList() : list;
    }
}
