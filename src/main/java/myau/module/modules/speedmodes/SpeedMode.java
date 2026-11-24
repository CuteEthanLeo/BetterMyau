package myau.module.modules.speedmodes;

import myau.module.mode.ModuleMode;
import myau.module.modules.Speed;

/** Base class of the Speed modes ported from xw. The settings live in {@link Speed}. */
public abstract class SpeedMode extends ModuleMode<Speed> {
    protected SpeedMode(String name) {
        super(name);
    }

    /** MoveInputEvent (after the movement input was read). */
    public void onMoveInput() {
    }
}
