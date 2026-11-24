package myau.injectbuild;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Mixin 本会加到目标类上的实例字段，挪到旁路 {@code myau/inject/state/<Mixin>State} 对象里。
 *
 * <ul>
 *   <li>识别一个 Mixin 里的「新增实例字段」（非 @Shadow、非静态）；静态常量（已被 javac 内联）直接删声明。</li>
 *   <li>生成 State 类：带这些字段、一张弱键 identity 映射、以及从 Mixin {@code <init>} 搬过来的字段初值。</li>
 *   <li>把 Mixin 各方法里对这些字段的 {@code GETFIELD/PUTFIELD} 改成 {@code INVOKESTATIC State.get_/set_}
 *       （栈效果一致，无需改帧）；删掉字段声明与 {@code <init>} 里的初值序列。</li>
 * </ul>
 *
 * 初值若引用了 {@code this} 或局部变量（无法搬走），构建直接失败并指出位置。
 */
final class FieldRewrite {
    private static final String SHADOW_ANN = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String STATE_PKG = "myau/inject/state/";
    private static final String FIELD_STORE = "myau/inject/rt/FieldStore";
    private static final String OF_DESC =
        "(Ljava/util/Map;[Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;";

    /** 生成的 State 类：内部名 → 字节。 */
    final Map<String, byte[]> stateClasses = new LinkedHashMap<String, byte[]>();
    /** 被搬走（删除）的字段键 owner+"#"+name，供校验只针对真正残留（不误伤 @Shadow）。 */
    final java.util.Set<String> removedFieldKeys = new java.util.HashSet<String>();
    int fieldsMoved;
    int constantsDropped;

    /**
     * 处理一个 Mixin 类（原地改 ClassNode）。
     *
     * @param issues 发现无法搬走的初值等问题时追加到这里（非空即构建失败）。
     * @return 是否改动过。
     */
    boolean process(ClassNode mixin, List<String> issues) {
        List<FieldNode> addedInstance = new ArrayList<FieldNode>();
        List<FieldNode> staticConstants = new ArrayList<FieldNode>();
        for (FieldNode f : new ArrayList<FieldNode>(mixin.fields)) {
            if (isShadow(f)) continue;
            if ((f.access & Opcodes.ACC_STATIC) != 0) {
                staticConstants.add(f);
            } else {
                addedInstance.add(f);
            }
        }
        if (addedInstance.isEmpty() && staticConstants.isEmpty()) return false;

        String simple = mixin.name.substring(mixin.name.lastIndexOf('/') + 1);
        String stateName = STATE_PKG + simple + "State";

        // 字段名 → State 里的存取方法名。
        Map<String, String[]> accessors = new HashMap<String, String[]>(); // name → {getMethod,setMethod,desc}
        for (FieldNode f : addedInstance) {
            String safe = f.name.replace('$', '_');
            accessors.put(f.name, new String[]{"get_" + safe, "set_" + safe, f.desc});
        }

        // 从 <init> 抽取初值序列。
        Map<String, List<AbstractInsnNode>> initializers = new LinkedHashMap<String, List<AbstractInsnNode>>();
        MethodNode ctor = findMethod(mixin, "<init>");
        if (ctor != null) {
            extractInitializers(mixin, ctor, addedInstance, initializers, issues);
        }

        // 改写所有方法里的字段访问。
        for (MethodNode m : mixin.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (fin.owner.equals(mixin.name) && accessors.containsKey(fin.name)) {
                        String[] a = accessors.get(fin.name);
                        if (insn.getOpcode() == Opcodes.GETFIELD) {
                            m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, stateName,
                                a[0], "(Ljava/lang/Object;)" + a[2], false));
                            fieldsMoved++;
                        } else if (insn.getOpcode() == Opcodes.PUTFIELD) {
                            m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, stateName,
                                a[1], "(Ljava/lang/Object;" + a[2] + ")V", false));
                        }
                    }
                }
                insn = next;
            }
        }

        // 删除字段声明。
        for (FieldNode f : addedInstance) removedFieldKeys.add(mixin.name + "#" + f.name);
        for (FieldNode f : staticConstants) removedFieldKeys.add(mixin.name + "#" + f.name);
        mixin.fields.removeAll(addedInstance);
        mixin.fields.removeAll(staticConstants);
        constantsDropped += staticConstants.size();

        // 生成 State 类。
        stateClasses.put(stateName, generateState(stateName, addedInstance, initializers));
        return true;
    }

    // ───────────────────────── 初值抽取 ─────────────────────────

    private void extractInitializers(ClassNode mixin, MethodNode ctor, List<FieldNode> added,
                                     Map<String, List<AbstractInsnNode>> out, List<String> issues) {
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (FieldNode f : added) names.add(f.name);

        AbstractInsnNode[] arr = ctor.instructions.toArray();
        List<Integer> producers = new ArrayList<Integer>(); // 每个栈槽记录产出它的指令下标
        List<int[]> toRemove = new ArrayList<int[]>();       // {objIdx, putIdx}

        for (int i = 0; i < arr.length; i++) {
            AbstractInsnNode insn = arr[i];
            int op = insn.getOpcode();
            if (insn instanceof FieldInsnNode && op == Opcodes.PUTFIELD) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                boolean ours = fin.owner.equals(mixin.name) && names.contains(fin.name);
                int fieldSize = Type.getType(fin.desc).getSize();
                if (ours) {
                    if (fieldSize != 1) {
                        issues.add(mixin.name + "." + fin.name + " 初值是 2 槽（long/double），暂不支持搬移");
                        pop(producers, 1 + fieldSize);
                        continue;
                    }
                    int objIdx = producers.get(producers.size() - 2);
                    // 校验初值块不引用 this / 局部变量。
                    for (int k = objIdx + 1; k < i; k++) {
                        int kop = arr[k].getOpcode();
                        if (arr[k] instanceof VarInsnNode) {
                            issues.add(mixin.name + "." + fin.name + " 初值引用了局部变量/this，无法搬移");
                            break;
                        }
                        if (kop == Opcodes.DUP2 || kop == Opcodes.DUP_X1 || kop == Opcodes.DUP_X2
                            || kop == Opcodes.DUP2_X1 || kop == Opcodes.DUP2_X2) {
                            issues.add(mixin.name + "." + fin.name + " 初值含不支持的栈操作");
                            break;
                        }
                    }
                    List<AbstractInsnNode> value = new ArrayList<AbstractInsnNode>();
                    for (int k = objIdx + 1; k < i; k++) value.add(clone(arr[k]));
                    out.put(fin.name, value);
                    toRemove.add(new int[]{objIdx, i});
                }
                pop(producers, 1 + fieldSize);
                continue;
            }
            applyStack(insn, producers, i);
        }

        // 删除初值序列（从后往前，避免影响前面的节点引用）。
        toRemove.sort((a, b) -> b[0] - a[0]);
        for (int[] range : toRemove) {
            for (int k = range[1]; k >= range[0]; k--) {
                ctor.instructions.remove(arr[k]);
            }
        }
    }

    /** 维护「产出指令下标」的抽象栈。只支持字段初值里会出现的指令；遇到不认识的保守按其通用栈效果处理。 */
    private void applyStack(AbstractInsnNode insn, List<Integer> producers, int idx) {
        int op = insn.getOpcode();
        if (op < 0) return; // Label / Line / Frame
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            int pops = (op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEDYNAMIC) ? 0 : 1;
            for (Type t : Type.getArgumentTypes(m.desc)) pops += t.getSize();
            pop(producers, pops);
            int push = Type.getReturnType(m.desc).getSize();
            for (int n = 0; n < push; n++) producers.add(idx);
            return;
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            int size = Type.getType(f.desc).getSize();
            switch (op) {
                case Opcodes.GETSTATIC: for (int n = 0; n < size; n++) producers.add(idx); break;
                case Opcodes.GETFIELD: pop(producers, 1); for (int n = 0; n < size; n++) producers.add(idx); break;
                case Opcodes.PUTSTATIC: pop(producers, size); break;
                case Opcodes.PUTFIELD: pop(producers, 1 + size); break;
                default: break;
            }
            return;
        }
        if (insn instanceof VarInsnNode) {
            switch (op) {
                case Opcodes.ALOAD: case Opcodes.ILOAD: case Opcodes.FLOAD: producers.add(idx); break;
                case Opcodes.LLOAD: case Opcodes.DLOAD: producers.add(idx); producers.add(idx); break;
                case Opcodes.ASTORE: case Opcodes.ISTORE: case Opcodes.FSTORE: pop(producers, 1); break;
                case Opcodes.LSTORE: case Opcodes.DSTORE: pop(producers, 2); break;
                default: break;
            }
            return;
        }
        if (insn instanceof LdcInsnNode) {
            Object c = ((LdcInsnNode) insn).cst;
            int size = (c instanceof Long || c instanceof Double) ? 2 : 1;
            for (int n = 0; n < size; n++) producers.add(idx);
            return;
        }
        if (insn instanceof TypeInsnNode) {
            switch (op) {
                case Opcodes.NEW: producers.add(idx); break;
                case Opcodes.ANEWARRAY: case Opcodes.CHECKCAST: case Opcodes.INSTANCEOF: pop(producers, 1); producers.add(idx); break;
                default: break;
            }
            return;
        }
        if (op == Opcodes.DUP) {
            producers.add(producers.isEmpty() ? idx : producers.get(producers.size() - 1));
            return;
        }
        // 常量与单槽入栈。
        switch (op) {
            case Opcodes.ACONST_NULL:
            case Opcodes.ICONST_M1: case Opcodes.ICONST_0: case Opcodes.ICONST_1: case Opcodes.ICONST_2:
            case Opcodes.ICONST_3: case Opcodes.ICONST_4: case Opcodes.ICONST_5:
            case Opcodes.FCONST_0: case Opcodes.FCONST_1: case Opcodes.FCONST_2:
            case Opcodes.BIPUSH: case Opcodes.SIPUSH:
                producers.add(idx); break;
            case Opcodes.LCONST_0: case Opcodes.LCONST_1: case Opcodes.DCONST_0: case Opcodes.DCONST_1:
                producers.add(idx); producers.add(idx); break;
            case Opcodes.NEWARRAY: pop(producers, 1); producers.add(idx); break;
            case Opcodes.POP: pop(producers, 1); break;
            case Opcodes.POP2: pop(producers, 2); break;
            default:
                break;
        }
    }

    private static void pop(List<Integer> stack, int n) {
        for (int i = 0; i < n && !stack.isEmpty(); i++) stack.remove(stack.size() - 1);
    }

    // ───────────────────────── 生成 State 类 ─────────────────────────

    private byte[] generateState(String stateName, List<FieldNode> fields,
                                 Map<String, List<AbstractInsnNode>> initializers) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        cn.name = stateName;
        cn.superName = "java/lang/Object";

        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "MAP", "Ljava/util/Map;", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "CACHE", "[Ljava/lang/Object;", null, null));
        for (FieldNode f : fields) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, f.name, f.desc, null, null));
        }

        // <clinit>: MAP = FieldStore.newMap(); CACHE = new Object[2];
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        InsnList ci = clinit.instructions;
        ci.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIELD_STORE, "newMap", "()Ljava/util/Map;", false));
        ci.add(new FieldInsnNode(Opcodes.PUTSTATIC, stateName, "MAP", "Ljava/util/Map;"));
        ci.add(new InsnNode(Opcodes.ICONST_2));
        ci.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        ci.add(new FieldInsnNode(Opcodes.PUTSTATIC, stateName, "CACHE", "[Ljava/lang/Object;"));
        ci.add(new InsnNode(Opcodes.RETURN));
        clinit.maxStack = 2;
        clinit.maxLocals = 0;
        cn.methods.add(clinit);

        // <init>: super(); 字段初值
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        InsnList ii = init.instructions;
        ii.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ii.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        for (FieldNode f : fields) {
            List<AbstractInsnNode> value = initializers.get(f.name);
            if (value == null) continue;
            ii.add(new VarInsnNode(Opcodes.ALOAD, 0));
            for (AbstractInsnNode v : value) ii.add(clone(v));
            ii.add(new FieldInsnNode(Opcodes.PUTFIELD, stateName, f.name, f.desc));
        }
        ii.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(init);

        // get_/set_
        for (FieldNode f : fields) {
            String safe = f.name.replace('$', '_');
            Type ft = Type.getType(f.desc);

            MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "get_" + safe, "(Ljava/lang/Object;)" + f.desc, null, null);
            InsnList gi = get.instructions;
            emitStore(gi, stateName);
            gi.add(new FieldInsnNode(Opcodes.GETFIELD, stateName, f.name, f.desc));
            gi.add(new InsnNode(ft.getOpcode(Opcodes.IRETURN)));
            cn.methods.add(get);

            MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "set_" + safe, "(Ljava/lang/Object;" + f.desc + ")V", null, null);
            InsnList si = set.instructions;
            emitStore(si, stateName);
            si.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), 1));
            si.add(new FieldInsnNode(Opcodes.PUTFIELD, stateName, f.name, f.desc));
            si.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(set);
        }

        SafeClassWriter cw = new SafeClassWriter(
            org.objectweb.asm.ClassWriter.COMPUTE_MAXS | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** 压入该 owner 对应的 State 实例（已 checkcast）。栈：... → ..., State */
    private void emitStore(InsnList out, String stateName) {
        out.add(new FieldInsnNode(Opcodes.GETSTATIC, stateName, "MAP", "Ljava/util/Map;"));
        out.add(new FieldInsnNode(Opcodes.GETSTATIC, stateName, "CACHE", "[Ljava/lang/Object;"));
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new LdcInsnNode(Type.getObjectType(stateName)));
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIELD_STORE, "of", OF_DESC, false));
        out.add(new TypeInsnNode(Opcodes.CHECKCAST, stateName));
    }

    // ───────────────────────── 杂项 ─────────────────────────

    private static boolean isShadow(FieldNode f) {
        return hasAnn(f.visibleAnnotations) || hasAnn(f.invisibleAnnotations);
    }

    private static boolean hasAnn(List<AnnotationNode> anns) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) if (SHADOW_ANN.equals(a.desc)) return true;
        return false;
    }

    private static MethodNode findMethod(ClassNode cn, String name) {
        for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
        return null;
    }

    private static AbstractInsnNode clone(AbstractInsnNode insn) {
        return insn.clone(Collections.<org.objectweb.asm.tree.LabelNode, org.objectweb.asm.tree.LabelNode>emptyMap());
    }
}
