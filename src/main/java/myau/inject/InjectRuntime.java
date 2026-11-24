package myau.inject;

import myau.Myau;
import myau.inject.mixin.InjectMixinService;
import myau.inject.rt.InjectDiagnostics;
import myau.inject.rt.LaunchClassSource;
import myau.inject.rt.LoadTimeTransformer;
import myau.inject.rt.PostProcessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Proxy;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 注入运行时的大脑。由 {@link myau.inject.agent.InjectAgent} 经 LaunchClassLoader 反射调用，和模组版处于
 * 同一个类加载器里，所以能直接引用游戏类（{@code net.minecraft.*}、{@code myau.Myau}）和（经 Shadow
 * 重定位的）Mixin 引擎（{@code org.spongepowered.* → myau.inject.shadow.*}）。
 *
 * <p>流程（见实施计划第 2 节）：装 Mixin 服务来源 → 晚启动引擎、加注入配置 → 抓取已加载目标的当前字节
 * → 逐类套用 Mixin 并后处理成可 redefine 的字节 → 回到客户端线程一次性做完：重载音效、{@code new Myau()}、
 * {@code redefineClasses}、挂加载期 transformer、补做启动期一次性逻辑。失败路径尽量不改动游戏。
 */
public final class InjectRuntime {
    private static final String WINDOW_TITLE = "BetterMyau for Minecraft 1.8.9";
    private static final String CONFIG = "mixins.bettermyau.inject.json";
    private static final String TARGETS = "/myau/inject/targets.txt";

    private InjectRuntime() {
    }

    /** @return null 表示成功；非空为失败原因（尽量在改动游戏之前失败）。 */
    public static String run(Instrumentation inst, String arguments, String agentLogPath) {
        InjectDiagnostics.init(agentLogPath);
        InjectDiagnostics.info("运行时启动");
        try {
            final ClassLoader lcl = InjectRuntime.class.getClassLoader();
            // 防止游戏自己的 Mixin / OptiFine transformer 碰我们重定位后的引擎类。
            addTransformerExclusion(lcl, "myau.inject.");

            final Map<String, byte[]> captured = new HashMap<String, byte[]>();
            InjectMixinService.setSource(new LaunchClassSource(lcl, captured));

            // 晚启动我们这套（已重定位的）Mixin 引擎，并加上注入专用配置。
            MixinBootstrap.init();
            Mixins.addConfiguration(CONFIG);
            InjectDiagnostics.info("Mixin 引擎已启动，配置已加入: " + CONFIG);

            final Set<String> targets = readTargets();
            if (targets.isEmpty()) return "读不到注入目标清单（" + TARGETS + "）。";

            // 抓取已加载目标的当前字节（含 Forge/OptiFine 改动）。
            List<Class<?>> loaded = new ArrayList<Class<?>>();
            Set<String> loadedNames = new LinkedHashSet<String>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String internal = c.getName().replace('.', '/');
                if (targets.contains(internal) && inst.isModifiableClass(c)) {
                    loaded.add(c);
                    loadedNames.add(internal);
                }
            }
            captureBytes(inst, loaded, captured);
            InjectDiagnostics.info("已抓取 " + loaded.size() + " 个已加载目标的当前字节");

            // 逐类套用 + 后处理。
            final Proxy proxy = new Proxy();
            final List<ClassDefinition> defs = new ArrayList<ClassDefinition>();
            int applied = 0;
            for (Class<?> c : loaded) {
                String dotted = c.getName();
                byte[] orig = captured.get(dotted.replace('.', '/'));
                if (orig == null) continue;
                byte[] out = proxy.transformClassBytes(dotted, dotted, orig);
                if (out == null || out == orig || Arrays.equals(out, orig)) continue; // 没有 Mixin 命中它
                byte[] fin = PostProcessor.finish(dotted, orig, out);
                defs.add(new ClassDefinition(c, fin));
                applied++;
            }
            InjectDiagnostics.info("套用完成，待 redefine 的类: " + applied);

            // 回到客户端线程一次做完（顺序见计划）。
            final Throwable[] error = new Throwable[1];
            final CountDownLatch latch = new CountDownLatch(1);
            final Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 1) 先重载音效，让覆盖原版的音效生效（此时音乐 / UI 音效线程还没起）。
                        try {
                            mc.getSoundHandler().onResourceManagerReload(mc.getResourceManager());
                        } catch (Throwable soundError) {
                            InjectDiagnostics.warn("重载音效失败（不致命）", soundError);
                        }
                        // 2) 启动客户端本体（必须在 GL 线程：ESP 字段初始化要编译着色器）。
                        new Myau();
                        // 3) 原子套用 Mixin 改动（放在 new Myau() 之后：部分渲染钩子不判空 moduleManager）。
                        if (!defs.isEmpty()) {
                            inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
                        }
                        // 4) 挂加载期 transformer：注入后才加载的目标同样套用。
                        inst.addTransformer(new LoadTimeTransformer(lcl, targets, proxy), false);
                        // 5) 补「抓取之后、挂钩之前」又加载进来的目标。
                        catchUpNewlyLoaded(inst, targets, loadedNames, captured, proxy);
                        // 6) 补做启动期一次性逻辑。
                        startupFixups(mc);
                    } catch (Throwable t) {
                        error[0] = t;
                    } finally {
                        latch.countDown();
                    }
                }
            });

            if (!latch.await(30, TimeUnit.SECONDS)) {
                return "等待客户端线程应用注入超时（30 秒）。游戏可能卡在加载，请重启后再试。";
            }
            if (error[0] != null) {
                tryDisableAll();
                return "应用注入失败: " + describe(error[0]);
            }
            InjectDiagnostics.info("注入成功");
            return null;
        } catch (Throwable t) {
            InjectDiagnostics.warn("注入运行时失败", t);
            return "注入运行时失败: " + describe(t);
        }
    }

    // ───────────────────────── 抓取 / 补齐 ─────────────────────────

    private static void captureBytes(Instrumentation inst, List<Class<?>> loaded,
                                     final Map<String, byte[]> captured) throws Exception {
        if (loaded.isEmpty()) return;
        ClassFileTransformer capture = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain pd, byte[] bytes) {
                if (name != null && bytes != null) captured.put(name, bytes.clone());
                return null;
            }
        };
        inst.addTransformer(capture, true);
        try {
            inst.retransformClasses(loaded.toArray(new Class<?>[0]));
        } finally {
            inst.removeTransformer(capture);
        }
    }

    private static void catchUpNewlyLoaded(Instrumentation inst, Set<String> targets, Set<String> alreadyDone,
                                           Map<String, byte[]> captured, Proxy proxy) {
        List<Class<?>> extra = new ArrayList<Class<?>>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String internal = c.getName().replace('.', '/');
            if (targets.contains(internal) && !alreadyDone.contains(internal) && inst.isModifiableClass(c)) {
                extra.add(c);
            }
        }
        if (extra.isEmpty()) return;
        try {
            captureBytes(inst, extra, captured);
            List<ClassDefinition> defs = new ArrayList<ClassDefinition>();
            for (Class<?> c : extra) {
                String dotted = c.getName();
                byte[] orig = captured.get(dotted.replace('.', '/'));
                if (orig == null) continue;
                byte[] out = proxy.transformClassBytes(dotted, dotted, orig);
                if (out == null || out == orig || Arrays.equals(out, orig)) continue;
                defs.add(new ClassDefinition(c, PostProcessor.finish(dotted, orig, out)));
            }
            if (!defs.isEmpty()) inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
            InjectDiagnostics.info("补齐了 " + defs.size() + " 个新加载的目标");
        } catch (Throwable t) {
            InjectDiagnostics.warn("补齐新加载目标失败（不致命）", t);
        }
    }

    // ───────────────────────── 启动期一次性逻辑 ─────────────────────────

    private static void startupFixups(Minecraft mc) {
        try {
            org.lwjgl.opengl.Display.setTitle(WINDOW_TITLE);
            setCustomIcon();
        } catch (Throwable t) {
            InjectDiagnostics.warn("设置窗口标题/图标失败（不致命）", t);
        }
        try {
            GuiScreen screen = mc.currentScreen;
            if (screen != null) {
                String name = screen.getClass().getName();
                boolean vanillaMenu =
                    name.equals("net.minecraft.client.gui.GuiMainMenu")
                    || name.equals("net.minecraft.client.gui.GuiSelectWorld")
                    || name.equals("net.minecraft.client.multiplayer.GuiMultiplayer");
                if (vanillaMenu) {
                    // 重新显示 → displayGuiScreen 钩子里的 ScreenSwap 把它换成玻璃版。
                    mc.displayGuiScreen(screen);
                } else {
                    // 其它界面不能重开（容器界面关闭会给服务器发包）；只重跑一次 initGui 让样式钩子生效。
                    ScaledResolution res = new ScaledResolution(mc);
                    screen.setWorldAndResolution(mc, res.getScaledWidth(), res.getScaledHeight());
                }
            }
        } catch (Throwable t) {
            InjectDiagnostics.warn("刷新当前界面失败（不致命）", t);
        }
    }

    /** 从 /TUBIAO.png 设置窗口图标，逻辑与 MixinMinecraft.setCustomIcon 一致（注入后那个钩子不会再触发）。 */
    private static void setCustomIcon() {
        try (InputStream stream = InjectRuntime.class.getResourceAsStream("/TUBIAO.png")) {
            if (stream == null) return;
            java.awt.image.BufferedImage original = javax.imageio.ImageIO.read(stream);
            if (original == null) return;
            java.nio.ByteBuffer[] icons = new java.nio.ByteBuffer[]{
                toIconBuffer(original, 16),
                toIconBuffer(original, 32)
            };
            org.lwjgl.opengl.Display.setIcon(icons);
        } catch (Exception e) {
            InjectDiagnostics.warn("设置图标失败", e);
        }
    }

    private static java.nio.ByteBuffer toIconBuffer(java.awt.image.BufferedImage src, int size) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(size * size * 4);
        int[] pixels = img.getRGB(0, 0, size, size, null, 0, size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int px = pixels[y * size + x];
                buf.put((byte) ((px >> 16) & 0xFF));
                buf.put((byte) ((px >> 8) & 0xFF));
                buf.put((byte) (px & 0xFF));
                buf.put((byte) ((px >> 24) & 0xFF));
            }
        }
        buf.flip();
        return buf;
    }

    private static void tryDisableAll() {
        try {
            if (Myau.moduleManager != null) {
                for (myau.module.Module module : Myau.moduleManager.modules.values()) {
                    try {
                        if (module.isEnabled()) module.setEnabled(false);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ───────────────────────── 杂项 ─────────────────────────

    private static Set<String> readTargets() {
        Set<String> targets = new LinkedHashSet<String>();
        InputStream in = InjectRuntime.class.getResourceAsStream(TARGETS);
        if (in == null) return targets;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) targets.add(line.replace('.', '/'));
            }
        } catch (Exception e) {
            InjectDiagnostics.warn("读取目标清单失败", e);
        }
        return targets;
    }

    private static void addTransformerExclusion(ClassLoader lcl, String prefix) {
        try {
            Method m = lcl.getClass().getMethod("addTransformerExclusion", String.class);
            m.invoke(lcl, prefix);
        } catch (Throwable t) {
            InjectDiagnostics.warn("添加 transformer 排除失败（不致命）: " + prefix, t);
        }
    }

    private static String describe(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 8) {
            if (depth > 0) result.append(" <- ");
            result.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) result.append(": ").append(message.trim());
            current = current.getCause();
            depth++;
        }
        return result.toString();
    }
}
