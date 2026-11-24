package myau.injectbuild;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * 注入版构建期改写工具：把 remapJar 产出的 SRG 模组 jar（和发群里的模组版同一批 class）改造成注入载荷的
 * 中间 jar——不改源码，只做两处机械改写（Accessor → 桥接；Mixin 新增字段 → 旁路存储），再生成注入专用
 * Mixin 配置、目标清单、服务文件和 Agent 清单。随后由 Gradle 的 ShadowJar 把 {@code org.spongepowered}
 * 重定位到 {@code myau.inject.shadow} 得到最终载荷。
 *
 * 用法：{@code InjectBuilder <inputSrgJar> <refmapNameInJar> <outputJar> [buildId]}
 */
public final class InjectBuilder {
    private static final String ACCESSOR_PREFIX = "myau/mixin/IAccessor";
    private static final String MIXIN_PREFIX = "myau/mixin/Mixin";
    private static final String MIXIN_ANN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String CONFIG_NAME = "mixins.bettermyau.inject.json";
    private static final String TARGETS_NAME = "myau/inject/targets.txt";
    // 不进注入配置：SplashProgressThread 只在启动画面线程里有意义，注入时那线程早没了。
    private static final Set<String> EXCLUDE_MIXINS = new LinkedHashSet<String>(
        java.util.Arrays.asList("MixinSplashProgressThread"));

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: InjectBuilder <inputSrgJar> <refmapNameInJar> <outputJar> [buildId]");
            System.exit(2);
        }
        String inputJar = args[0];
        String refmapName = args[1];
        String outputJar = args[2];
        String buildId = args.length > 3 ? args[3] : String.valueOf(System.currentTimeMillis());
        new InjectBuilder().run(inputJar, refmapName, outputJar, buildId);
    }

    private final Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
    private final AccessorRewrite accessors = new AccessorRewrite();
    private final FieldRewrite fields = new FieldRewrite();
    private final List<String> issues = new ArrayList<String>();
    private final Set<String> mixinSimpleNames = new LinkedHashSet<String>();
    private final Set<String> targets = new LinkedHashSet<String>();

    private void run(String inputJar, String refmapName, String outputJar, String buildId) throws Exception {
        readJar(inputJar);
        System.out.println("[InjectBuilder] 读入 " + entries.size() + " 个条目: " + inputJar);

        // refmap。
        byte[] refmapBytes = entries.get(refmapName);
        if (refmapBytes == null) throw new IllegalStateException("jar 里没有 refmap: " + refmapName);
        Map<String, Map<String, String>> refmap = parseRefmap(refmapBytes);

        // Pass A：分析所有 Accessor 接口。
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (isClass(e.getKey()) && internal(e.getKey()).startsWith(ACCESSOR_PREFIX)) {
                accessors.analyze(readNode(e.getValue()), refmap);
            }
        }
        System.out.println("[InjectBuilder] Accessor 接口分析完成");

        // Pass B：逐类改写（字段 + Accessor 调用），收集 mixin 与目标。
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!isClass(name)) { out.put(name, e.getValue()); continue; }
            String internal = internal(name);
            ClassNode node = readNode(e.getValue());
            boolean changed = false;

            boolean isMixin = internal.startsWith(MIXIN_PREFIX);
            if (isMixin) {
                collectMixin(node, internal);
                changed |= fields.process(node, issues);
            }
            changed |= accessors.rewrite(node);

            if (changed) {
                ClassWriter cw = new ClassWriter(0); // 栈效果不变、帧自洽，原样写出即可
                node.accept(cw);
                out.put(name, cw.toByteArray());
            } else {
                out.put(name, e.getValue());
            }
        }

        // 生成的类。
        out.put("myau/inject/acc/Acc.class", accessors.generateAcc());
        for (Map.Entry<String, byte[]> s : fields.stateClasses.entrySet()) {
            out.put(s.getKey() + ".class", s.getValue());
        }

        // 注入配置、目标清单。
        out.put(CONFIG_NAME, buildConfig(refmapName).getBytes(StandardCharsets.UTF_8));
        out.put(TARGETS_NAME, buildTargets().getBytes(StandardCharsets.UTF_8));

        // 服务文件：覆盖成我们的 provider（内容不被 Shadow 重定位；文件名会被 Shadow 重定位）。
        out.put("META-INF/services/org.spongepowered.asm.service.IMixinService",
            "myau.inject.mixin.InjectMixinService\n".getBytes(StandardCharsets.UTF_8));
        out.put("META-INF/services/org.spongepowered.asm.service.IMixinServiceBootstrap",
            "myau.inject.mixin.InjectServiceBootstrap\n".getBytes(StandardCharsets.UTF_8));
        out.put("META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService",
            "myau.inject.mixin.InjectGlobalProperties\n".getBytes(StandardCharsets.UTF_8));

        // 去掉模组版配置（用显式列表的注入配置取代，不走插件扫描）。
        out.remove("mixins.bettermyau.json");

        // 校验。
        validate(out);

        // 写出。
        writeJar(outputJar, out, buildId);
        System.out.println("[InjectBuilder] 完成: " + outputJar);
        System.out.println("[InjectBuilder] Accessor 调用改写 " + accessors.callsRewritten
            + " 处，CHECKCAST " + accessors.castsRewritten + " 处");
        System.out.println("[InjectBuilder] 字段读改写 " + fields.fieldsMoved + " 处，删常量 "
            + fields.constantsDropped + " 个，State 类 " + fields.stateClasses.size() + " 个");
        System.out.println("[InjectBuilder] 注入 Mixin " + mixinSimpleNames.size() + " 个，目标类 " + targets.size() + " 个");
    }

    private void collectMixin(ClassNode node, String internal) {
        String simple = internal.substring(internal.lastIndexOf('/') + 1);
        if (EXCLUDE_MIXINS.contains(simple)) return;
        mixinSimpleNames.add(simple);
        // @Mixin 目标 → targets.txt。
        AnnotationNode mixin = findAnn(node, MIXIN_ANN);
        if (mixin != null && mixin.values != null) {
            for (int i = 0; i + 1 < mixin.values.size(); i += 2) {
                String key = (String) mixin.values.get(i);
                Object val = mixin.values.get(i + 1);
                if ("value".equals(key) && val instanceof List) {
                    for (Object o : (List<?>) val) if (o instanceof Type) targets.add(((Type) o).getInternalName());
                } else if ("targets".equals(key) && val instanceof List) {
                    for (Object o : (List<?>) val) targets.add(o.toString().replace('.', '/'));
                }
            }
        }
    }

    private String buildConfig(String refmapName) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"required\": true,\n");
        sb.append("  \"minVersion\": \"0.7\",\n");
        sb.append("  \"package\": \"myau.mixin\",\n");
        sb.append("  \"compatibilityLevel\": \"JAVA_8\",\n");
        sb.append("  \"refmap\": \"").append(refmapName).append("\",\n");
        sb.append("  \"mixins\": [\n");
        int i = 0;
        for (String m : mixinSimpleNames) {
            sb.append("    \"").append(m).append("\"");
            if (++i < mixinSimpleNames.size()) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private String buildTargets() {
        StringBuilder sb = new StringBuilder();
        sb.append("# BetterMyau-Inject 目标类清单（内部名），由 InjectBuilder 生成\n");
        for (String t : targets) sb.append(t).append('\n');
        return sb.toString();
    }

    // ───────────────────────── 校验 ─────────────────────────

    private void validate(Map<String, byte[]> out) {
        List<String> errors = new ArrayList<String>(issues);
        for (Map.Entry<String, byte[]> e : out.entrySet()) {
            if (!isClass(e.getKey())) continue;
            String internal = internal(e.getKey());
            if (internal.startsWith(ACCESSOR_PREFIX)) continue; // 接口本身保留，不算残留
            ClassNode node = readNode(e.getValue());
            for (MethodNode m : node.methods) {
                if (m.instructions == null) continue;
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if (min.owner.startsWith(ACCESSOR_PREFIX)) {
                            errors.add("残留 Accessor 调用 " + internal + "#" + m.name + " → " + min.owner + "." + min.name);
                        }
                    } else if (insn instanceof TypeInsnNode) {
                        if (accessors.isAccessorType(((TypeInsnNode) insn).desc)) {
                            errors.add("残留 CHECKCAST 到 Accessor 接口 " + internal + "#" + m.name);
                        }
                    } else if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        // 只针对真正被搬走的字段；@Shadow 字段访问合法（Mixin 套用时会重映射到目标）。
                        if (fields.removedFieldKeys.contains(fin.owner + "#" + fin.name)
                            && (insn.getOpcode() == Opcodes.GETFIELD || insn.getOpcode() == Opcodes.PUTFIELD)) {
                            errors.add("残留被搬走的字段访问 " + internal + "#" + m.name + " → " + fin.owner + "." + fin.name);
                        }
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            System.err.println("[InjectBuilder] 校验失败（" + errors.size() + "）:");
            for (String s : errors) System.err.println("  - " + s);
            throw new IllegalStateException("InjectBuilder 校验未通过，共 " + errors.size() + " 条");
        }
        System.out.println("[InjectBuilder] 校验通过");
    }

    // ───────────────────────── jar IO ─────────────────────────

    private void readJar(String path) throws Exception {
        try (JarFile jar = new JarFile(path)) {
            java.util.Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    entries.put(entry.getName(), readAll(in));
                }
            }
        }
    }

    private void writeJar(String path, Map<String, byte[]> out, String buildId) throws Exception {
        java.util.jar.Manifest mf = new java.util.jar.Manifest();
        java.util.jar.Attributes a = mf.getMainAttributes();
        a.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        a.putValue("Agent-Class", "myau.inject.agent.InjectAgent");
        a.putValue("Can-Redefine-Classes", "true");
        a.putValue("Can-Retransform-Classes", "true");
        a.putValue("BetterMyau-Inject-Build", buildId);
        Files.createDirectories(Paths.get(path).toAbsolutePath().getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(Paths.get(path)), mf)) {
            for (Map.Entry<String, byte[]> e : out.entrySet()) {
                if (e.getKey().equalsIgnoreCase("META-INF/MANIFEST.MF")) continue; // 用上面的清单
                ZipEntry ze = new ZipEntry(e.getKey());
                jos.putNextEntry(ze);
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
    }

    // ───────────────────────── 杂项 ─────────────────────────

    private static boolean isClass(String name) {
        return name.endsWith(".class");
    }

    private static String internal(String entryName) {
        return entryName.substring(0, entryName.length() - ".class".length());
    }

    private static ClassNode readNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static AnnotationNode findAnn(ClassNode cn, String desc) {
        if (cn.visibleAnnotations != null) {
            for (AnnotationNode a : cn.visibleAnnotations) if (desc.equals(a.desc)) return a;
        }
        if (cn.invisibleAnnotations != null) {
            for (AnnotationNode a : cn.invisibleAnnotations) if (desc.equals(a.desc)) return a;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> parseRefmap(byte[] bytes) throws Exception {
        Map<String, Map<String, String>> result = new LinkedHashMap<String, Map<String, String>>();
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            JsonObject mappings = root.has("mappings") ? root.getAsJsonObject("mappings") : root;
            for (Map.Entry<String, com.google.gson.JsonElement> cls : mappings.entrySet()) {
                Map<String, String> m = new LinkedHashMap<String, String>();
                JsonObject members = cls.getValue().getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> mem : members.entrySet()) {
                    m.put(mem.getKey(), mem.getValue().getAsString());
                }
                result.put(cls.getKey(), m);
            }
        }
        return result;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[16384];
        int read;
        while ((read = in.read(buf)) >= 0) out.write(buf, 0, read);
        return out.toByteArray();
    }
}
