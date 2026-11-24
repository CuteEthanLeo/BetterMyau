package myau.injectcheck;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * 离线自检：不启动游戏，用重定位后的 Mixin 引擎把注入配置套用到真实的 SRG 目标字节（minecraft-srg +
 * forge-srg）上，再把结果交给 JVM 校验器验证（在一次性子加载器里 define + resolve），逐个目标报告是否
 * 套用成功、是否通过校验。HotSpot 对 redefine 形状的接受性已由 spike 单独验证，这里补上「真实目标字节上
 * 套用 + 产物可验证」这一环。
 *
 * <p>全程反射驱动：引擎类名是重定位后的 {@code myau.inject.shadow.*}，自检本身在 app 加载器里，只通过
 * 一个独立的 {@code engineLoader}（载荷 + 游戏 SRG jar + 引擎依赖）反射调用。
 *
 * 用法：{@code SelfCheck <payloadJar> <mcSrgJar> <forgeSrgJar> <dep1> <dep2> ...}
 */
public final class SelfCheck {
    private static final String CONFIG = "mixins.bettermyau.inject.json";
    private static final String TARGETS = "myau/inject/targets.txt";
    private static final String P = "myau.inject.shadow.";

    private final URLClassLoader engineLoader;
    private final List<JarFile> jars = new ArrayList<JarFile>();
    private Object proxy;
    private Method transformClassBytes;
    private Method postFinish;
    private Class<?> postRejection;

    private SelfCheck(String[] args) throws Exception {
        List<URL> urls = new ArrayList<URL>();
        for (String a : args) {
            java.io.File f = new java.io.File(a);
            urls.add(f.toURI().toURL());
            if (a.endsWith(".jar")) jars.add(new JarFile(f));
        }
        // 父加载器用平台加载器，隔离出一套干净环境。
        engineLoader = new URLClassLoader(urls.toArray(new URL[0]), bootstrapParent());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: SelfCheck <payloadJar> <mcSrgJar> <forgeSrgJar> [deps...]");
            System.exit(2);
        }
        int code = new SelfCheck(args).run();
        System.exit(code);
    }

    private int run() throws Exception {
        // 1) 装来源 + 启动引擎 + 加配置。
        Object source = Proxy.newProxyInstance(engineLoader,
            new Class<?>[]{engineLoader.loadClass("myau.inject.mixin.GameClassSource")}, new SourceHandler());
        Class<?> service = engineLoader.loadClass("myau.inject.mixin.InjectMixinService");
        service.getMethod("setSource", engineLoader.loadClass("myau.inject.mixin.GameClassSource"))
            .invoke(null, source);

        engineLoader.loadClass(P + "asm.launch.MixinBootstrap").getMethod("init").invoke(null);
        engineLoader.loadClass(P + "asm.mixin.Mixins")
            .getMethod("addConfiguration", String.class).invoke(null, CONFIG);

        Class<?> proxyClass = engineLoader.loadClass(P + "asm.mixin.transformer.Proxy");
        proxy = proxyClass.getConstructor().newInstance();
        transformClassBytes = proxyClass.getMethod("transformClassBytes", String.class, String.class, byte[].class);

        Class<?> post = engineLoader.loadClass("myau.inject.rt.PostProcessor");
        postFinish = post.getMethod("finish", String.class, byte[].class, byte[].class);
        postRejection = engineLoader.loadClass("myau.inject.rt.PostProcessor$Rejection");

        System.out.println("[SelfCheck] 引擎已启动，配置已加载");

        // 2) 逐目标套用 + 校验。
        Set<String> targets = readTargets();
        int total = 0, missing = 0, notApplied = 0, applied = 0, verifyOk = 0;
        List<String> failures = new ArrayList<String>();
        for (String target : targets) {
            total++;
            byte[] orig = classBytes(target);
            if (orig == null) { missing++; continue; } // 比如 Patcher 专属的 FovHandler 目标
            byte[] out;
            try {
                out = (byte[]) transformClassBytes.invoke(proxy, target.replace('/', '.'), target.replace('/', '.'), orig);
            } catch (Throwable t) {
                failures.add("套用抛异常 " + target + ": " + root(t));
                continue;
            }
            if (out == null || Arrays.equals(out, orig)) { notApplied++; continue; }
            applied++;
            byte[] fin;
            try {
                fin = (byte[]) postFinish.invoke(null, target.replace('/', '.'), orig, out);
            } catch (Throwable t) {
                failures.add("后处理拒绝 " + target + ": " + root(t));
                continue;
            }
            String verr = verify(target, fin);
            if (verr == null) verifyOk++;
            else failures.add("校验失败 " + target + ": " + verr);
        }

        System.out.println("[SelfCheck] 目标 " + total + "：套用+校验通过 " + verifyOk
            + "，套用成功 " + applied + "，未命中 " + notApplied + "，目标类缺失 " + missing);
        if (!failures.isEmpty()) {
            System.err.println("[SelfCheck] 失败 " + failures.size() + " 条:");
            for (String f : failures) System.err.println("  - " + f);
            return 1;
        }
        System.out.println("[SelfCheck] 全部通过");
        return 0;
    }

    /** 在一次性子加载器里 define + link（触发字节码校验）。返回 null 表示通过，否则是错误描述。 */
    private String verify(final String internal, final byte[] bytes) {
        final String dotted = internal.replace('/', '.');
        ClassLoader child = new ClassLoader(engineLoader) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(dotted)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) c = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) resolveClass(c);
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        };
        try {
            Class.forName(dotted, false, child); // 触发 define + 链接校验
            return null;
        } catch (LinkageError e) {
            return root(e);
        } catch (Throwable t) {
            return root(t);
        }
    }

    // GameClassSource 的反射代理：classBytes / loader / resource。
    private final class SourceHandler implements InvocationHandler {
        @Override
        public Object invoke(Object p, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("classBytes")) return classBytes(((String) args[0]).replace('.', '/'));
            if (name.equals("loader")) return engineLoader;
            if (name.equals("resource")) return resourceStream((String) args[0]);
            if (name.equals("toString")) return "SelfCheckSource";
            if (name.equals("hashCode")) return System.identityHashCode(p);
            if (name.equals("equals")) return p == args[0];
            return null;
        }
    }

    private byte[] classBytes(String name) {
        return readEntry(name.replace('.', '/') + ".class");
    }

    private InputStream resourceStream(String path) {
        byte[] b = readEntry(path);
        return b == null ? null : new java.io.ByteArrayInputStream(b);
    }

    private byte[] readEntry(String path) {
        for (JarFile jar : jars) {
            java.util.zip.ZipEntry e = jar.getEntry(path);
            if (e != null) {
                try (InputStream in = jar.getInputStream(e)) {
                    return readAll(in);
                } catch (Exception ignored) {
                }
            }
        }
        // 兜底：JDK 类（java.*/javax.*）和其它依赖（lwjgl 等）从 engineLoader 读——它委托到 bootstrap/rt.jar。
        // 游戏里是 LaunchClassLoader 透明做这件事，自检这里显式补上。
        if (path.endsWith(".class")) {
            try (InputStream in = engineLoader.getResourceAsStream(path)) {
                if (in != null) return readAll(in);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Set<String> readTargets() {
        Set<String> targets = new LinkedHashSet<String>();
        byte[] b = readEntry(TARGETS);
        if (b == null) return targets;
        for (String line : new String(b, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) targets.add(line.replace('.', '/'));
        }
        return targets;
    }

    private static ClassLoader bootstrapParent() {
        // Java 8：系统加载器的父即扩展加载器；用它做父，避免 app classpath 干扰。
        return ClassLoader.getSystemClassLoader().getParent();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
        return out.toByteArray();
    }

    private static String root(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int d = 0;
        while (c != null && d < 6) {
            if (d > 0) sb.append(" <- ");
            sb.append(c.getClass().getName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage().trim());
            c = c.getCause();
            d++;
        }
        String s = sb.toString();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
