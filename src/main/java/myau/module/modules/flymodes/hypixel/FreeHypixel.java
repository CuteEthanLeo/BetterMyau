package myau.module.modules.flymodes.hypixel;

import myau.events.MoveEvent;
import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.util.MoveUtil;
import myau.util.TickTimer;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class FreeHypixel extends FlyMode {
    private final TickTimer timer = new TickTimer();
    private float startYaw = 0.0F;
    private float startPitch = 0.0F;

    public FreeHypixel() {
        super("FreeHypixel");
    }

    @Override
    public void onEnable() {
        this.timer.reset();

        mc.thePlayer.setPositionAndUpdate(mc.thePlayer.posX, mc.thePlayer.posY + 0.42, mc.thePlayer.posZ);

        this.startYaw = mc.thePlayer.rotationYaw;
        this.startPitch = mc.thePlayer.rotationPitch;
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (this.timer.hasTimePassed(10)) {
            mc.thePlayer.capabilities.isFlying = true;
            return;
        } else {
            mc.thePlayer.rotationYaw = this.startYaw;
            mc.thePlayer.rotationPitch = this.startPitch;
            MoveUtil.stop();
        }

        if (this.parent.getStartY() == new BigDecimal(mc.thePlayer.posY).setScale(3, RoundingMode.HALF_DOWN).doubleValue()) {
            this.timer.update();
        }
    }

    @Override
    public void onMove(MoveEvent event) {
        if (!this.timer.hasTimePassed(10)) {
            event.zero();
        }
    }
}
