package myau.inject.rt;

import myau.inject.mixin.GameClassSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏内的 {@link GameClassSource}：类字节优先用抓取到的「当前定义」（含 Forge / OptiFine 改动），
 * 其余从 LaunchClassLoader 读原始资源；类加载与资源都走 LaunchClassLoader。
 */
public final class LaunchClassSource implements GameClassSource {
    private static final String REMAPPER = "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper";
    private static final String TRANSFORMER = "net.minecraft.launchwrapper.IClassTransformer";

    private final ClassLoader loader;
    private final Map<String, byte[]> captured; // 键为内部名（斜杠）
    private final Map<String, byte[]> resolved = new ConcurrentHashMap<String, byte[]>(); // 混淆兜底的结果缓存

    public LaunchClassSource(ClassLoader loader, Map<String, byte[]> captured) {
        this.loader = loader;
        this.captured = captured;
    }

    @Override
    public byte[] classBytes(String name) {
        String internal = name.replace('.', '/');
        byte[] hit = captured.get(internal);
        if (hit != null) return hit;
        InputStream in = loader.getResourceAsStream(internal + ".class");
        if (in == null) return fromObfuscated(internal);
        try {
            return readAll(in);
        } catch (Exception e) {
            return null;
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 正式环境里原版 jar 是混淆的：net/minecraft/client/Minecraft 实际存成 ave.class，按反混淆名读不到。
     * 仿照 Mixin 的 LaunchWrapper 服务：用 FML 映射表反查混淆名 → 读原始字节 → 按顺序跑一遍
     * LaunchClassLoader 的 transformer（Forge 补丁、反混淆、OptiFine 等），得到和游戏里一致的定义。
     * 任何一步失败都返回 null（与改动前的行为一致）。
     */
    private byte[] fromObfuscated(String internal) {
        byte[] cached = resolved.get(internal);
        if (cached != null) return cached;
        try {
            Class<?> remapperClass = Class.forName(REMAPPER, false, loader);
            Object remapper = remapperClass.getField("INSTANCE").get(null);
            String obf = (String) remapperClass.getMethod("unmap", String.class).invoke(remapper, internal);
            if (obf == null || obf.equals(internal)) return null;

            String obfDotted = obf.replace('/', '.');
            String deobfDotted = internal.replace('/', '.');
            byte[] bytes = (byte[]) loader.getClass().getMethod("getClassBytes", String.class).invoke(loader, obfDotted);
            if (bytes == null) return null;

            List<?> transformers = (List<?>) loader.getClass().getMethod("getTransformers").invoke(loader);
            Method transform = Class.forName(TRANSFORMER, false, loader)
                .getMethod("transform", String.class, String.class, byte[].class);
            for (Object t : transformers) {
                if (t == null) continue;
                bytes = (byte[]) transform.invoke(t, obfDotted, deobfDotted, bytes);
                if (bytes == null) return null;
            }
            resolved.put(internal, bytes);
            return bytes;
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public ClassLoader loader() {
        return loader;
    }

    @Override
    public InputStream resource(String path) {
        return loader.getResourceAsStream(path);
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
