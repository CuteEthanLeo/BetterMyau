package myau.module.modules.longjumpmodes;

import myau.module.mode.ModuleMode;
import myau.module.modules.LongJump;

/**
 * A LongJump mode ported from xw. {@code onUpdate} only runs while the player is in the air after
 * a jump (see LongJump#updateMode); the other hooks run whenever the module is enabled.
 */
public abstract class LongJumpMode extends ModuleMode<LongJump> {
    protected LongJumpMode(String name) {
        super(name);
    }
}
