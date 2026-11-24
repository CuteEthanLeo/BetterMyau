package myau.injectbuild;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Merges the Java 8-downgraded ViaVersion jars into one and repairs what JvmDowngrader gets wrong:
 * the {@code toString()} it writes for records calls {@code StringBuilder.append(byte)} and
 * {@code append(short)}, which no Java has (javac widens those to {@code append(int)}). On the
 * JVM bytes and shorts already are ints, so the call is pointed at {@code append(I)}.
 * <p>
 * Args: {@code <out jar> <in jar>...}. The first copy of a duplicate entry wins.
 */
public final class ViaJarFix {
    private ViaJarFix() { }

    private static final String[] BUILDERS = {"java/lang/StringBuilder", "java/lang/StringBuffer"};
    private static int fixedCalls, fixedClasses;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("usage: ViaJarFix <out jar> <in jar>...");
        File out = new File(args[0]);
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        Set<String> written = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            for (int i = 1; i < args.length; i++) {
                try (ZipFile in = new ZipFile(args[i])) {
                    for (ZipEntry entry : Collections.list(in.entries())) {
                        String name = entry.getName();
                        if (entry.isDirectory() || !written.add(name)) continue;
                        byte[] bytes = read(in, entry);
                        if (name.endsWith(".class") && needsFix(bytes)) bytes = fix(bytes);
                        zip.putNextEntry(new ZipEntry(name));
                        zip.write(bytes);
                        zip.closeEntry();
                    }
                }
            }
        }
        System.out.println("[ViaJarFix] " + written.size() + " entries; repaired " + fixedCalls + " append(byte/short) calls in " + fixedClasses + " classes");
    }

    private static boolean needsFix(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        return text.contains("(B)Ljava/lang/StringBuilder;") || text.contains("(S)Ljava/lang/StringBuilder;")
                || text.contains("(B)Ljava/lang/StringBuffer;") || text.contains("(S)Ljava/lang/StringBuffer;");
    }

    private static byte[] fix(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        final int[] fixedHere = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, name, desc, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && methodName.equals("append") && isBuilder(owner)
                                && (methodDesc.equals("(B)L" + owner + ";") || methodDesc.equals("(S)L" + owner + ";"))) {
                            methodDesc = "(I)L" + owner + ";";
                            fixedHere[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDesc, itf);
                    }
                };
            }
        }, 0);
        if (fixedHere[0] == 0) return bytes;
        fixedCalls += fixedHere[0];
        fixedClasses++;
        return writer.toByteArray();
    }

    private static boolean isBuilder(String owner) {
        for (String builder : BUILDERS) if (builder.equals(owner)) return true;
        return false;
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
