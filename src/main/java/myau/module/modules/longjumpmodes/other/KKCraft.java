package myau.module.modules.longjumpmodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.longjumpmodes.LongJumpMode;

public class KKCraft extends LongJumpMode {
    private boolean started = false;
    private int ticks = 0;

    public KKCraft() {
        super("KKCraft");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.onGround) {
            if (this.started) {
                this.parent.setEnabled(false);
                return;
            }
            if (!mc.gameSettings.keyBindJump.isKeyDown()) {
                mc.thePlayer.jump();
                this.started = true;
            }
        } else {
            if (this.ticks >= 2 && this.ticks <= 8) {
                mc.thePlayer.motionY += 0.07;
            }
            this.ticks++;
        }
    }

    @Override
    public void onEnable() {
        this.ticks = 0;
        this.started = false;
    }
}
