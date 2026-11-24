package myau.module.modules.nofallmodes.aac;

import myau.events.JumpEvent;
import myau.events.MoveEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntity;
import myau.module.modules.nofallmodes.NoFallMode;

public class LAAC extends NoFallMode {
    private boolean jumped = false;

    public LAAC() {
        super("LAAC");
    }

    private boolean isFree() {
        return !mc.thePlayer.isOnLadder() && !mc.thePlayer.isInWater() && !((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.onGround) {
            this.jumped = false;
        }
        if (mc.thePlayer.motionY > 0.0) {
            this.jumped = true;
        }
        if (!this.jumped && mc.thePlayer.onGround && this.isFree()) {
            mc.thePlayer.motionY = -6.0;
        }
    }

    @Override
    public void onJump(JumpEvent event) {
        this.jumped = true;
    }

    @Override
    public void onMove(MoveEvent event) {
        if (!this.jumped && !mc.thePlayer.onGround && this.isFree() && mc.thePlayer.motionY < 0.0) {
            event.zeroXZ();
        }
    }
}
