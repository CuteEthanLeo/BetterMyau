package myau.module.modules.flymodes;

import myau.module.mode.ModuleMode;
import myau.module.modules.Fly;

/** One mode of {@link Fly}. Settings live in the Fly module; read them through {@code parent}. */
public abstract class FlyMode extends ModuleMode<Fly> {
    protected FlyMode(String name) {
        super(name);
    }

    /** LoadWorldEvent while this mode is running. */
    public void onWorld() {
    }
}
