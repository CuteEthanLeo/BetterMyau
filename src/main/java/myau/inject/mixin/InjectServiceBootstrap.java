package myau.inject.mixin;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/**
 * 向 Mixin 的 {@code MixinService} 登记本服务的类名。{@code MixinService} 会先跑所有 bootstrap 收集
 * 合法服务类名，再从 ServiceLoader 里挑第一个名字在册且 {@code isValid()} 的服务。我们不需要
 * LaunchWrapper 版 bootstrap 那些（给 LCL 加排除、注册 Proxy）——引擎由 {@code InjectRuntime} 手动驱动，
 * 不走 LCL 的 transformer 流水线——所以 {@link #bootstrap()} 为空。
 */
public final class InjectServiceBootstrap implements IMixinServiceBootstrap {
    @Override public String getName() { return "BetterMyauInject"; }

    @Override public String getServiceClassName() { return "myau.inject.mixin.InjectMixinService"; }

    @Override public void bootstrap() { }
}
