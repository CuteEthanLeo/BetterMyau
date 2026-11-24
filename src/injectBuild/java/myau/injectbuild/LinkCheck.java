package myau.injectbuild;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline linker for the finished mod jar: every class must be Java 8 bytecode, and every class,
 * method and field it refers to must exist on what the game actually runs with (the jar itself,
 * the Java 8 runtime, Minecraft/Forge in SRG names and the game's libraries). Catches what the
 * Java 8 downgrade of ViaVersion could leave behind (a Java 9+ method, a newer Guava method than
 * the game's Guava 17) without starting the game.
 * <p>
 * Args: {@code <mod jar> <classpath jar>...}. Mixin classes are skipped (they refer to their own
 * shadows) and so is the bundled Mixin library (it runs against LaunchWrapper).
 * <p>
 * Bundled libraries (under {@code myau/deps/}) are linked lazily by the JVM, and ViaVersion ships
 * minimized copies of fastutil and optional platform hooks, so a library class referring to
 * another library class that is not there is not reported; anything it needs from the platform
 * (Java, Guava, Netty, Minecraft...) is. Of JvmDowngrader's stubs only those ViaVersion can reach
 * are checked: the rest belong to downgrading code that never runs here.
 */
public final class LinkCheck {
    private LinkCheck() { }

    private static final class Info {
        String superName;
        String[] interfaces;
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();
    }

    private final Map<String, Info> infos = new HashMap<>();
    private final Map<String, ZipFile> where = new HashMap<>();
    private final Set<String> missingClasses = new HashSet<>();
    private final Map<String, Set<String>> problems = new TreeMap<>();
    private final Set<String> reachableStubs = new HashSet<>();

    private static final String DEPS = "myau/deps/";
    private static final String JVMDG = "myau/deps/xyz/wagyourtail/jvmdg/";
    /** What the game provides at runtime; a missing one of these is always a real problem. */
    private static final String[] PLATFORM = {
            "java/", "javax/", "sun/", "jdk/", "com/google/common/", "com/google/gson/", "io/netty/", "org/apache/",
            "net/minecraft/", "net/minecraftforge/", "com/mojang/", "org/lwjgl/", "org/objectweb/asm/", "org/slf4j/"
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("usage: LinkCheck <mod jar> <classpath jar>...");
        LinkCheck check = new LinkCheck();
        ZipFile mod = new ZipFile(args[0]);
        check.index(mod);
        for (int i = 1; i < args.length; i++) {
            File file = new File(args[i]);
            if (file.isFile()) check.index(new ZipFile(file));
        }
        int checked = check.checkAll(mod);
        if (check.problems.isEmpty()) {
            System.out.println("[LinkCheck] OK: " + checked + " classes (" + check.reachableStubs.size()
                    + " JvmDowngrader stubs in use), all references resolve on Java 8 + the game's libraries");
            return;
        }
        int count = 0;
        for (Map.Entry<String, Set<String>> entry : check.problems.entrySet()) {
            System.out.println("[LinkCheck] " + entry.getKey());
            for (String from : entry.getValue()) {
                if (count++ < 4000) System.out.println("    from " + from);
            }
        }
        System.out.println("[LinkCheck] FAILED: " + check.problems.size() + " unresolved references in " + checked + " classes");
        System.exit(1);
    }

    /** First jar wins, like the class path. */
    private void index(ZipFile zip) {
        for (ZipEntry entry : Collections.list(zip.entries())) {
            String name = entry.getName();
            if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
            String internal = name.substring(0, name.length() - 6);
            if (!where.containsKey(internal)) where.put(internal, zip);
        }
    }

    /**
     * The stub of java.lang.invoke.ConstantBootstraps (Java 11) only runs for constant-dynamic
     * (CONSTANT_Dynamic) entries, and there are none in this jar: ASM 5, which reads every class
     * here, cannot even parse a class that has one. Its Java 9 calls can never run.
     */
    private static final String CONDY_ONLY = JVMDG + "j11/stub/java_base/J_L_I_ConstantBootstraps";

    private static boolean skipped(String internal) {
        return internal.startsWith("myau/mixin/") || internal.startsWith("org/spongepowered/") || internal.endsWith("module-info")
                || internal.equals(CONDY_ONLY);
    }

    /** The JvmDowngrader stubs reachable from everything else in the jar. */
    private void findReachableStubs(ZipFile mod) throws IOException {
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        for (ZipEntry entry : Collections.list(mod.entries())) {
            String name = entry.getName();
            if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
            String internal = name.substring(0, name.length() - 6);
            if (skipped(internal) || internal.startsWith(JVMDG)) continue;
            collectStubs(read(mod, entry), queue);
        }
        while (!queue.isEmpty()) {
            String stub = queue.poll();
            ZipEntry entry = mod.getEntry(stub + ".class");
            if (entry != null) collectStubs(read(mod, entry), queue);
        }
    }

    /** Adds every JvmDowngrader class named in this class's constant pool. */
    private void collectStubs(byte[] bytes, java.util.ArrayDeque<String> queue) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int at = 0;
        while ((at = text.indexOf(JVMDG, at)) >= 0) {
            int end = at;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (!(Character.isLetterOrDigit(c) || c == '/' || c == '_' || c == '$')) break;
                end++;
            }
            String stub = text.substring(at, end);
            if (reachableStubs.add(stub)) queue.add(stub);
            at = end;
        }
    }

    private int checkAll(ZipFile mod) throws IOException {
        findReachableStubs(mod);
        int checked = 0;
        for (ZipEntry entry : Collections.list(mod.entries())) {
            String name = entry.getName();
            if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
            String internal = name.substring(0, name.length() - 6);
            if (skipped(internal)) continue;
            if (internal.startsWith(JVMDG) && !reachableStubs.contains(internal)) continue;
            byte[] bytes = read(mod, entry);
            int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
            if (major > 52) problem("class version " + major + " (Java 8 is 52): " + internal, internal);
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            check(node);
            checked++;
        }
        return checked;
    }

    private void check(ClassNode node) {
        String from = node.name;
        if (node.superName != null) requireClass(node.superName, from);
        for (String itf : node.interfaces) requireClass(itf, from);
        for (MethodNode method : node.methods) {
            String site = from + "." + method.name + method.desc;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    requireMethod(call.owner, call.name, call.desc, site);
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) insn;
                    requireField(field.owner, field.name, field.desc, site);
                } else if (insn instanceof TypeInsnNode) {
                    requireType(((TypeInsnNode) insn).desc, site);
                } else if (insn instanceof MultiANewArrayInsnNode) {
                    requireType(((MultiANewArrayInsnNode) insn).desc, site);
                } else if (insn instanceof LdcInsnNode) {
                    Object cst = ((LdcInsnNode) insn).cst;
                    if (cst instanceof Type) requireAsmType((Type) cst, site);
                    else if (cst instanceof Handle) requireHandle((Handle) cst, site);
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                    requireHandle(indy.bsm, site);
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle) requireHandle((Handle) arg, site);
                        else if (arg instanceof Type) requireAsmType((Type) arg, site);
                    }
                }
            }
        }
    }

    private void requireHandle(Handle handle, String site) {
        int tag = handle.getTag();
        if (tag <= Opcodes.H_PUTSTATIC) requireField(handle.getOwner(), handle.getName(), handle.getDesc(), site);
        else requireMethod(handle.getOwner(), handle.getName(), handle.getDesc(), site);
    }

    private void requireAsmType(Type type, String site) {
        if (type.getSort() == Type.METHOD) {
            for (Type arg : type.getArgumentTypes()) requireAsmType(arg, site);
            requireAsmType(type.getReturnType(), site);
        } else if (type.getSort() == Type.ARRAY) {
            requireAsmType(type.getElementType(), site);
        } else if (type.getSort() == Type.OBJECT) {
            requireClass(type.getInternalName(), site);
        }
    }

    /** A TypeInsn desc: an internal name, or an array descriptor. */
    private void requireType(String desc, String site) {
        if (desc.startsWith("[")) requireAsmType(Type.getType(desc), site);
        else requireClass(desc, site);
    }

    private boolean requireClass(String name, String site) {
        if (name.startsWith("[")) {
            requireAsmType(Type.getType(name), site);
            return false;
        }
        if (info(name) != null) return true;
        // A library class pointing at another library class that was left out is linked lazily: not reported.
        if (!site.startsWith(DEPS) || platform(name)) problem("missing class " + name, site);
        return false;
    }

    private static boolean platform(String name) {
        for (String prefix : PLATFORM) if (name.startsWith(prefix)) return true;
        return false;
    }

    private void requireMethod(String owner, String name, String desc, String site) {
        if (owner.startsWith("[")) {
            requireAsmType(Type.getType(owner), site);
            return; // clone() and Object's methods
        }
        if (!requireClass(owner, site)) return;
        if (owner.equals("java/lang/invoke/MethodHandle") && (name.equals("invoke") || name.equals("invokeExact"))) return;
        if (owner.equals("java/lang/invoke/VarHandle")) return; // reported as a missing class on Java 8 anyway
        if (!findMethod(owner, name + desc, new HashSet<String>())) problem("missing method " + owner + "." + name + desc, site);
    }

    private void requireField(String owner, String name, String desc, String site) {
        if (!requireClass(owner, site)) return;
        if (!findField(owner, name + ":" + desc, new HashSet<String>())) problem("missing field " + owner + "." + name + ":" + desc, site);
    }

    private boolean findMethod(String owner, String key, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return false;
        Info info = info(owner);
        if (info == null) return false;
        if (info.methods.contains(key)) return true;
        if (findMethod(info.superName, key, seen)) return true;
        for (String itf : info.interfaces) if (findMethod(itf, key, seen)) return true;
        return false;
    }

    private boolean findField(String owner, String key, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return false;
        Info info = info(owner);
        if (info == null) return false;
        if (info.fields.contains(key)) return true;
        for (String itf : info.interfaces) if (findField(itf, key, seen)) return true;
        return findField(info.superName, key, seen);
    }

    private Info info(String name) {
        Info info = infos.get(name);
        if (info != null) return info;
        if (missingClasses.contains(name)) return null;
        ZipFile zip = where.get(name);
        if (zip == null) {
            missingClasses.add(name);
            return null;
        }
        try {
            byte[] bytes = read(zip, zip.getEntry(name + ".class"));
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            info = new Info();
            info.superName = node.superName;
            info.interfaces = node.interfaces.toArray(new String[0]);
            for (MethodNode method : node.methods) info.methods.add(method.name + method.desc);
            for (FieldNode field : node.fields) info.fields.add(field.name + ":" + field.desc);
            infos.put(name, info);
            return info;
        } catch (Exception e) {
            missingClasses.add(name);
            return null;
        }
    }

    private void problem(String what, String site) {
        Set<String> sites = problems.get(what);
        if (sites == null) {
            sites = new java.util.TreeSet<>();
            problems.put(what, sites);
        }
        if (sites.size() < 5) sites.add(site);
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
