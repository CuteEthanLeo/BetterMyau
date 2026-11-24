package myau.module.modules.speedmodes.rise;

import myau.events.StrafeEvent;
import myau.mixin.IAccessorKeyBinding;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;

public class WatchdogPrediction extends SpeedMode {
    private double savedMotionX = 0.0;
    private double savedMotionY = 0.0;
    private double savedMotionZ = 0.0;
    private boolean hasSavedMotion = false;
    private boolean boostPending = false;

    public WatchdogPrediction() {
        super("WatchdogPrediction");
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        int airTicks = MoveUtil.airTicks;
        if (player.onGround && MoveUtil.isMoving() && !player.isInWater()) {
            player.jump();
        }
        if (airTicks == 5 && player.hurtTime > 7) {
            this.saveMotion(player);
        } else if (this.hasSavedMotion && player.hurtTime > 7 && airTicks == 6) {
            this.clampSaved();
            double d0 = airTicks <= 1 ? 0.5460000157356262 : 1.0;
            player.motionX = this.savedMotionX * d0;
            player.motionY = this.savedMotionY - 0.02 * d0;
            player.motionZ = this.savedMotionZ * d0;
            SpeedUtil.moveFlying(0.1);
            this.hasSavedMotion = false;
            this.boostPending = true;
        } else if (this.boostPending && player.hurtTime > 7 && airTicks == 7) {
            SpeedUtil.moveFlying(0.06);
            this.boostPending = false;
        }
        if (player.isInWater()) {
            ((IAccessorKeyBinding) mc.gameSettings.keyBindJump).setPressed(true);
            if ((airTicks - 1) % 3 == 0 && player.hurtTime > 1) {
                this.saveMotion(player);
            } else if (this.hasSavedMotion && player.hurtTime > 2) {
                this.clampSaved();
                double d2 = airTicks <= 1 ? 0.5460000157356262 : 1.0;
                player.motionX = this.savedMotionX * d2;
                player.motionY = this.savedMotionY * d2;
                player.motionZ = this.savedMotionZ * d2;
                SpeedUtil.moveFlying(0.087);
                this.hasSavedMotion = false;
                this.boostPending = true;
            } else if (this.boostPending && player.hurtTime > 2) {
                this.boostPending = false;
                SpeedUtil.moveFlying(0.086);
            }
        }
    }

    private void saveMotion(EntityPlayerSP player) {
        this.savedMotionX = player.motionX;
        this.savedMotionY = player.motionY;
        this.savedMotionZ = player.motionZ;
        this.hasSavedMotion = true;
        player.motionY = 0.0;
        MoveUtil.stopXZ();
    }

    private void clampSaved() {
        if (Math.abs(this.savedMotionX) < 0.005) {
            this.savedMotionX = 0.0;
        }
        if (Math.abs(this.savedMotionY) < 0.005) {
            this.savedMotionY = 0.0;
        }
        if (Math.abs(this.savedMotionZ) < 0.005) {
            this.savedMotionZ = 0.0;
        }
    }

    @Override
    public void onDisable() {
        MoveUtil.setTimer(1.0F);
        if (mc.gameSettings.keyBindJump.isKeyDown()) {
            ((IAccessorKeyBinding) mc.gameSettings.keyBindJump).setPressed(false);
        }
        if (this.hasSavedMotion) {
            mc.thePlayer.motionX = this.savedMotionX * 0.91;
            mc.thePlayer.motionY = this.savedMotionY;
            mc.thePlayer.motionZ = this.savedMotionZ * 0.91;
            this.hasSavedMotion = false;
        }
    }

    @Override
    public void onEnable() {
        this.hasSavedMotion = false;
        this.boostPending = false;
    }
}
