package myau.module.modules.flymodes.blocksmc;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;

/**
 * Fly concept originally taken from CrossSine (LatestNCP), modified by EclipsesDev and mixed with
 * the NextGen FlyNcpClip.
 *
 * Prolonged flying over long distances is not recommended. Do not fly before the teleport (S08)
 * happened, and stand below a block when enabling.
 */
public class BlocksMC extends FlyMode {
    private boolean isFlying = false;
    private boolean isNotUnder = false;
    private boolean isTeleported = false;
    private boolean jumped = false;

    public BlocksMC() {
        super("BlocksMC");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        boolean debug = this.parent.blocksmcDebug.getValue();

        if (this.isFlying) {
            if (mc.thePlayer.onGround && this.parent.blocksmcStopOnLanding.getValue()) {
                if (debug) {
                    FlyUtil.chat("Ground Detected.. Stopping Fly");
                }
                this.parent.setEnabled(false);
            }

            if (!MoveUtil.isMoving() && this.parent.blocksmcStopOnNoMove.getValue()) {
                if (debug) {
                    FlyUtil.chat("No Movement Detected.. Stopping Fly. (Could be flagged)");
                }
                this.parent.setEnabled(false);
            }
        }

        if (this.shouldFly()) {
            if (this.isTeleported) {
                if (this.parent.blocksmcStable.getValue()) {
                    mc.thePlayer.motionY = 0.0;
                }

                this.handleTimerSlow();
                this.handlePlayerFlying();
            } else {
                if (debug) {
                    FlyUtil.chat("Waiting to be Teleported.. Please ensure you're below a block.");
                }
            }
        } else {
            this.handleTeleport();
        }

        MoveUtil.strafe();
    }

    @Override
    public void onDisable() {
        this.isNotUnder = false;
        this.isFlying = false;
        this.isTeleported = false;
        this.jumped = false;
    }

    @Override
    public void onWorld() {
        this.parent.setEnabled(false);
    }

    private void handleTimerSlow() {
        if (!mc.thePlayer.onGround && this.parent.blocksmcTimerSlowed.getValue()) {
            if (mc.thePlayer.ticksExisted % 7 == 0) {
                MoveUtil.setTimer(0.415F);
            } else {
                MoveUtil.setTimer(0.35F);
            }
        } else {
            MoveUtil.setTimer(1.0F);
        }
    }

    private boolean shouldFly() {
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, mc.thePlayer.getEntityBoundingBox().offset(0.0, 1.0, 0.0)).isEmpty()
                || this.isFlying;
    }

    private void handlePlayerFlying() {
        float boostSpeed = this.parent.blocksmcBoostSpeed.getValue();
        switch (MoveUtil.airTicks) {
            case 0:
                if (this.isNotUnder && this.isTeleported) {
                    MoveUtil.strafe(boostSpeed + this.parent.blocksmcExtraSpeed.getValue());
                    MoveUtil.tryJump();
                    this.isFlying = true;
                    this.isNotUnder = false;
                }
                break;
            case 1:
                if (this.isFlying) {
                    MoveUtil.strafe(boostSpeed);
                }
                break;
            default:
                break;
        }
    }

    private void handleTeleport() {
        this.isNotUnder = true;
        if (!this.isTeleported) {
            // Clipping is now patched on BlocksMC
            FlyUtil.sendPosition(mc.thePlayer.posX, mc.thePlayer.posY - 0.05, mc.thePlayer.posZ, false);
            FlyUtil.sendPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, false);

            this.isTeleported = true;
            if (this.parent.blocksmcDebug.getValue()) {
                FlyUtil.chat("Teleported.. Fly Now!");
            }
        }
    }
}
