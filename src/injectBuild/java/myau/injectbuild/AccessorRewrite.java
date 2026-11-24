package myau.injectbuild;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Mixin 的 Accessor/Invoker 机制改写成一个桥接类 {@code myau/inject/acc/Acc} + 运行时 MethodHandle。
 *
 * <p>模组版里 {@code ((IAccessorX) obj).m(...)} 依赖 Mixin 往目标类加接口和方法；注入时类已加载加不了接口。
 * 于是：每个 Accessor 方法生成一个静态桥接方法，转调经 {@link myau.inject.rt.AccessorRuntime} 解析出的
 * MethodHandle（成员名取自 refmap 的 SRG 名）。调用点 {@code INVOKEINTERFACE IAccessorX.m} 改成
 * {@code INVOKESTATIC Acc.bridge}；所有 {@code CHECKCAST IAccessorX} 改成 {@code CHECKCAST Object}，并把帧 /
 * 局部变量表里对 IAccessorX 的类型引用一并换成 Object——这样栈形状和帧都自洽，无需重算帧。
 */
final class AccessorRewrite {
    private static final String ACC = "myau/inject/acc/Acc";
    private static final String RT = "myau/inject/rt/AccessorRuntime";
    private static final String MH = "java/lang/invoke/MethodHandle";
    private static final String ACCESSOR_ANN = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_ANN = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String MIXIN_ANN = "Lorg/spongepowered/asm/mixin/Mixin;";

    /** 调用点改写表：key = owner+"."+name+desc → 桥接方法。 */
    private final Map<String, Bridge> callTable = new HashMap<String, Bridge>();
    /** 所有 Accessor 接口的内部名，供 CHECKCAST / 帧重写识别。 */
    private final java.util.Set<String> accessorTypes = new java.util.HashSet<String>();
    private final java.util.List<Bridge> bridges = new java.util.ArrayList<Bridge>();

    int callsRewritten;
    int castsRewritten;

    static final class Bridge {
        final String bridgeName;   // IAccessorMinecraft$getTimer
        final String bridgeDesc;   // (Ljava/lang/Object;...)...
        final int kind;            // 0 getter,1 setter,2 invoker
        final String owner;        // net/minecraft/client/Minecraft
        final String srgName;      // field_71428_T / func_...
        final String srgDesc;      // invoker 的方法描述符，否则 null
        Bridge(String bridgeName, String bridgeDesc, int kind, String owner, String srgName, String srgDesc) {
            this.bridgeName = bridgeName; this.bridgeDesc = bridgeDesc; this.kind = kind;
            this.owner = owner; this.srgName = srgName; this.srgDesc = srgDesc;
        }
    }

    /** 分析一个 IAccessor 接口，登记它的桥接。 */
    void analyze(ClassNode iface, Map<String, Map<String, String>> refmap) {
        accessorTypes.add(iface.name);
        String target = mixinTarget(iface);
        if (target == null) throw new IllegalStateException("Accessor 接口没有 @Mixin 目标: " + iface.name);
        Map<String, String> map = refmap.get(iface.name);
        String simple = iface.name.substring(iface.name.lastIndexOf('/') + 1);
        for (MethodNode m : iface.methods) {
            AnnotationNode accessor = findAnn(m.visibleAnnotations, ACCESSOR_ANN);
            AnnotationNode invoker = findAnn(m.visibleAnnotations, INVOKER_ANN);
            if (accessor == null && invoker == null) continue;
            boolean isInvoker = invoker != null;
            String memberKey = annValue(isInvoker ? invoker : accessor);
            if (memberKey == null) memberKey = inferMember(m.name, isInvoker);

            String srg = map != null ? map.get(memberKey) : null;
            if (srg == null) {
                // refmap 没有就直接用 key（S27PacketExplosion 那种本就写死 SRG 名的情况）。
                srg = memberKey + (isInvoker ? m.desc : fieldDescOf(m, false));
            }

            int kind;
            String srgName;
            String srgDesc = null;
            if (isInvoker) {
                kind = 2;
                int p = srg.indexOf('(');
                srgName = p >= 0 ? srg.substring(0, p) : srg;
                srgDesc = p >= 0 ? srg.substring(p) : m.desc;
            } else {
                boolean setter = Type.getReturnType(m.desc) == Type.VOID_TYPE;
                kind = setter ? 1 : 0;
                int c = srg.indexOf(':');
                srgName = c >= 0 ? srg.substring(0, c) : srg;
            }

            String bridgeName = simple + "$" + m.name;
            String bridgeDesc = "(Ljava/lang/Object;" + m.desc.substring(1);
            Bridge b = new Bridge(bridgeName, bridgeDesc, kind, target, srgName, srgDesc);
            bridges.add(b);
            callTable.put(iface.name + "." + m.name + m.desc, b);
        }
    }

    /** 生成 Acc 桥接类字节。 */
    byte[] generateAcc() {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        cn.name = ACC;
        cn.superName = "java/lang/Object";

        org.objectweb.asm.tree.MethodNode clinit = new org.objectweb.asm.tree.MethodNode(
            Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        InsnList ci = clinit.instructions;

        for (int i = 0; i < bridges.size(); i++) {
            Bridge b = bridges.get(i);
            String handleField = "MH_" + i;
            cn.fields.add(new org.objectweb.asm.tree.FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                handleField, "Ljava/lang/invoke/MethodHandle;", null, null));

            // <clinit>: MH_i = AccessorRuntime.getter/setter/invoker(...)
            ci.add(new org.objectweb.asm.tree.LdcInsnNode(b.owner));
            ci.add(new org.objectweb.asm.tree.LdcInsnNode(b.srgName));
            if (b.kind == 2) {
                ci.add(new org.objectweb.asm.tree.LdcInsnNode(b.srgDesc));
                ci.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RT, "invoker",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/MethodHandle;", false));
            } else {
                ci.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RT, b.kind == 1 ? "setter" : "getter",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/MethodHandle;", false));
            }
            ci.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, ACC, handleField,
                "Ljava/lang/invoke/MethodHandle;"));

            // 桥接方法体：getstatic MH_i; aload/load 参数; MethodHandle.invoke(bridgeDesc); return
            org.objectweb.asm.tree.MethodNode bm = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, b.bridgeName, b.bridgeDesc, null, null);
            InsnList mi = bm.instructions;
            mi.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, ACC, handleField,
                "Ljava/lang/invoke/MethodHandle;"));
            Type[] args = Type.getArgumentTypes(b.bridgeDesc);
            int local = 0;
            for (Type a : args) {
                mi.add(new org.objectweb.asm.tree.VarInsnNode(a.getOpcode(Opcodes.ILOAD), local));
                local += a.getSize();
            }
            mi.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MH, "invoke", b.bridgeDesc, false));
            mi.add(new org.objectweb.asm.tree.InsnNode(Type.getReturnType(b.bridgeDesc).getOpcode(Opcodes.IRETURN)));
            bm.maxStack = Math.max(1, local + 1);
            bm.maxLocals = local;
            cn.methods.add(bm);
        }
        ci.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        clinit.maxStack = 4;
        clinit.maxLocals = 0;
        cn.methods.add(clinit);

        SafeClassWriter cw = new SafeClassWriter(
            org.objectweb.asm.ClassWriter.COMPUTE_MAXS | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** 改写一个类里的 Accessor 调用点与 CHECKCAST / 帧。返回是否改动过。 */
    boolean rewrite(ClassNode cn) {
        boolean changed = false;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof MethodInsnNode && insn.getOpcode() == Opcodes.INVOKEINTERFACE) {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    if (min.owner.startsWith("myau/mixin/IAccessor")) {
                        Bridge b = callTable.get(min.owner + "." + min.name + min.desc);
                        if (b != null) {
                            m.instructions.set(insn, new MethodInsnNode(
                                Opcodes.INVOKESTATIC, ACC, b.bridgeName, b.bridgeDesc, false));
                            callsRewritten++;
                            changed = true;
                        }
                    }
                } else if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.CHECKCAST) {
                    TypeInsnNode tin = (TypeInsnNode) insn;
                    if (accessorTypes.contains(tin.desc)) {
                        tin.desc = "java/lang/Object";
                        castsRewritten++;
                        changed = true;
                    }
                } else if (insn instanceof FrameNode) {
                    changed |= retypeFrame((FrameNode) insn);
                }
                insn = next;
            }
            if (m.localVariables != null) {
                for (LocalVariableNode lv : m.localVariables) {
                    for (String t : accessorTypes) {
                        if (lv.desc != null && lv.desc.equals("L" + t + ";")) {
                            lv.desc = "Ljava/lang/Object;";
                            lv.signature = null;
                            changed = true;
                        }
                    }
                }
            }
        }
        return changed;
    }

    private boolean retypeFrame(FrameNode f) {
        boolean changed = false;
        changed |= retypeList(f.local);
        changed |= retypeList(f.stack);
        return changed;
    }

    private boolean retypeList(List<Object> list) {
        if (list == null) return false;
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o instanceof String && accessorTypes.contains((String) o)) {
                list.set(i, "java/lang/Object");
                changed = true;
            }
        }
        return changed;
    }

    boolean isAccessorType(String internalName) {
        return accessorTypes.contains(internalName);
    }

    // ───────────────────────── 读注解 ─────────────────────────

    private static String mixinTarget(ClassNode cn) {
        AnnotationNode mixin = findAnn(cn.visibleAnnotations, MIXIN_ANN);
        if (mixin == null) mixin = findAnn(cn.invisibleAnnotations, MIXIN_ANN);
        if (mixin == null || mixin.values == null) return null;
        for (int i = 0; i + 1 < mixin.values.size(); i += 2) {
            String key = (String) mixin.values.get(i);
            Object val = mixin.values.get(i + 1);
            if ("value".equals(key) && val instanceof List && !((List<?>) val).isEmpty()) {
                Object first = ((List<?>) val).get(0);
                if (first instanceof Type) return ((Type) first).getInternalName();
            }
            if ("targets".equals(key) && val instanceof List && !((List<?>) val).isEmpty()) {
                return ((List<?>) val).get(0).toString().replace('.', '/');
            }
        }
        return null;
    }

    private static AnnotationNode findAnn(List<AnnotationNode> anns, String desc) {
        if (anns == null) return null;
        for (AnnotationNode a : anns) if (desc.equals(a.desc)) return a;
        return null;
    }

    private static String annValue(AnnotationNode a) {
        if (a == null || a.values == null) return null;
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if ("value".equals(a.values.get(i))) {
                Object v = a.values.get(i + 1);
                if (v instanceof String) return (String) v;
                if (v instanceof List && !((List<?>) v).isEmpty()) return ((List<?>) v).get(0).toString();
            }
        }
        return null;
    }

    /** 从方法名推成员名：getX/isX/setX → x；@Invoker callX → x。 */
    private static String inferMember(String methodName, boolean invoker) {
        String n = methodName;
        for (String p : new String[]{"get", "set", "is", "call", "invoke"}) {
            if (n.length() > p.length() && n.startsWith(p) && Character.isUpperCase(n.charAt(p.length()))) {
                n = n.substring(p.length());
                break;
            }
        }
        if (n.isEmpty()) return methodName;
        return Character.toLowerCase(n.charAt(0)) + n.substring(1);
    }

    private static String fieldDescOf(MethodNode m, boolean ignored) {
        // getter: 返回类型即字段类型；setter: 唯一参数类型即字段类型。此处只用于 refmap 缺失时的兜底。
        if (Type.getReturnType(m.desc) != Type.VOID_TYPE) return ":" + Type.getReturnType(m.desc).getDescriptor();
        Type[] a = Type.getArgumentTypes(m.desc);
        return a.length == 1 ? ":" + a[0].getDescriptor() : "";
    }
}
