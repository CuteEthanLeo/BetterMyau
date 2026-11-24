package myau.injector;

/** 一个候选 JVM 进程的注入判定结果。 */
public final class TargetDescriptor {
    public final String pid;
    public final String javaVersion;
    public final String kind;        // 启动器 / 客户端描述
    public final boolean compatible; // 能否注入
    public final boolean modPresent; // 是否装了 BetterMyau 模组版
    public final String status;      // 中文说明

    public TargetDescriptor(String pid, String javaVersion, String kind,
                            boolean compatible, boolean modPresent, String status) {
        this.pid = pid;
        this.javaVersion = javaVersion;
        this.kind = kind;
        this.compatible = compatible;
        this.modPresent = modPresent;
        this.status = status;
    }

    @Override
    public String toString() {
        return "PID " + pid + " · " + kind + " · " + status;
    }
}
