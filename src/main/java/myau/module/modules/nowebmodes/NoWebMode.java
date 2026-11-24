package myau.module.modules.nowebmodes;

import myau.mixin.IAccessorEntity;
import myau.module.mode.ModuleMode;
import myau.module.modules.NoWeb;

/** A NoWeb mode ported from xw; only {@code onUpdate} is used. */
public abstract class NoWebMode extends ModuleMode<NoWeb> {
    protected NoWebMode(String name) {
        super(name);
    }

    protected static boolean isInWeb() {
        return ((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    protected static void clearWeb() {
        ((IAccessorEntity) mc.thePlayer).setIsInWeb(false);
    }
}
