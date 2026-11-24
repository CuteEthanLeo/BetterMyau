package myau.inject.mixin;

import java.io.InputStream;

/**
 * 字节与类加载的来源，让同一套 Mixin 引擎接线既能跑在游戏里（LaunchClassLoader），也能跑在离线
 * 自检里（直接读 jar）。{@link InjectMixinService} 的类/字节/资源查询全部走这个接口。
 *
 * <p>目标类的字节优先用「抓取到的当前字节」：对已加载的目标做过一次空 retransform 后拿到的定义，
 * 已含 Forge 和 OptiFine 的改动。没抓到的再退回从加载器读原始资源。
 */
public interface GameClassSource {
    /**
     * 返回类字节；name 可为内部名或点名。
     *
     * @return 抓取到的当前字节，或从加载器读到的原始字节；找不到返回 null。
     */
    byte[] classBytes(String name);

    /** 用于 Class.forName 的加载器（游戏里是 LaunchClassLoader）。 */
    ClassLoader loader();

    /** 读取资源（如 Mixin 配置 json）；找不到返回 null。 */
    InputStream resource(String path);
}
