package myau.module.modules.nofallmodes;

import myau.events.UpdateEvent;
import myau.module.mode.ModuleMode;
import myau.module.modules.NoFall;

/**
 * A NoFall mode ported from xw. {@code onUpdate} and {@code onMove} are skipped while the player
 * stands in or right above a liquid; {@code onJump} is also delivered while the module is off.
 */
public abstract class NoFallMode extends ModuleMode<NoFall> {
    protected NoFallMode(String name) {
        super(name);
    }

    /** UpdateEvent, delivered even in / above liquids (unlike onUpdate). */
    public void onUpdateAlways(UpdateEvent event) {
    }
}
