package myau.module.modules.speedmodes.leader;

import myau.Myau;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.mixin.IAccessorEntity;
import myau.module.modules.KillAura;
import myau.module.modules.LeaderScaffold;
import myau.module.modules.speedmodes.SpeedMode;
import myau.util.MoveUtil;

/**
 * LeaderClient Speed "Prediction" (https://github.com/Mornly/LeaderClient, GPLv3).
 * Timer balance while falling: low-timer-ticks ticks at a low timer, then the same number at 2.0.
 * It does not jump by itself; jump yourself.
 * Optional rotation: while only W is held in the air the server yaw is offset by 45 degrees.
 */
public class LeaderPrediction extends SpeedMode {
    private int ticks = 0;
    private boolean finished = false;

    public LeaderPrediction() {
        super("LeaderPrediction");
    }

    private boolean canBoost() {
        return !LeaderScaffold.isAnyScaffoldEnabled() && MoveUtil.isForwardPressed()
                && mc.thePlayer.getFoodStats().getFoodLevel() > 6
                && !mc.thePlayer.isSneaking()
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isInLava()
                && !((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    private void reset() {
        this.finished = false;
        MoveUtil.setTimer(1.0F);
        this.ticks = 0;
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        this.reset();
    }

    /** TickEvent PRE. */
    @Override
    public void onTick() {
        if (!this.canBoost() || mc.thePlayer.onGround) {
            this.reset();
            return;
        }
        if (this.ticks < parent.lpLowTimerTicks.getValue() && !this.finished && mc.thePlayer.motionY < 0) {
            this.ticks++;
            MoveUtil.setTimer(parent.lpTimerBoost.getValue());
            if (this.ticks == parent.lpLowTimerTicks.getValue()) {
                this.finished = true;
            }
        }
        if (this.finished && this.ticks > 0) {
            this.ticks--;
            MoveUtil.setTimer(2.0F);
            if (this.ticks == 0) {
                MoveUtil.setTimer(1.0F);
                this.finished = false;
            }
        }
    }

    private boolean rotationActive() {
        return parent.lpRotation.getValue() && this.canBoost()
                && !Myau.moduleManager.modules.get(KillAura.class).isEnabled();
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || !this.rotationActive()) {
            return;
        }
        float yawOffset = !mc.thePlayer.onGround
                && mc.gameSettings.keyBindForward.isKeyDown()
                && !mc.gameSettings.keyBindLeft.isKeyDown()
                && !mc.gameSettings.keyBindRight.isKeyDown() ? -45.0F : 0.0F;
        event.setRotation(mc.thePlayer.rotationYaw - yawOffset, mc.thePlayer.rotationPitch, 2);
        event.setPervRotation(mc.thePlayer.rotationYaw - yawOffset, 2);
    }

    @Override
    public void onMoveInput() {
        if (this.rotationActive() && RotationState.isActived() && RotationState.getPriority() == 2.0F && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }
}
