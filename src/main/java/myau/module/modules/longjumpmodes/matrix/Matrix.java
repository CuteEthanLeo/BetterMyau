package myau.module.modules.longjumpmodes.matrix;

import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.longjumpmodes.LongJumpMode;
import myau.util.MoveUtil;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

public class Matrix extends LongJumpMode {
    private boolean boosted = false;
    private boolean canBoost = false;
    private boolean receivedFlag = false;
    private boolean touchGround = false;

    public Matrix() {
        super("Matrix");
    }

    private boolean isNoGround() {
        return this.parent.matrixBypassMethod.getModeString().equals("NoGround");
    }

    @Override
    public void onEnable() {
        this.boosted = false;
        this.canBoost = false;
        this.receivedFlag = false;
        this.touchGround = false;
        if (this.isNoGround()) {
            if (mc.thePlayer != null && mc.thePlayer.onGround) {
                MoveUtil.tryJump();
            }
            this.touchGround = true;
        }
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S08PacketPlayerPosLook) {
                this.receivedFlag = true;
            }
        } else if (event.getPacket() instanceof C03PacketPlayer) {
            // xw: MotionEvent.onGround = false
            if (this.isNoGround() && !this.canBoost) {
                ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(false);
            }
        }
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (!mc.thePlayer.onGround && this.touchGround) {
            this.touchGround = false;
        }
        if (mc.thePlayer.onGround && !this.touchGround) {
            if (MoveUtil.isMoving()) {
                MoveUtil.tryJump();
            }
            this.boosted = false;
            if (this.isNoGround()) {
                this.canBoost = true;
            }
        }
        if (mc.thePlayer.fallDistance >= 0.25F && !this.boosted && !this.isNoGround()) {
            this.canBoost = true;
        }
        if (this.canBoost) {
            MoveUtil.strafe(this.parent.matrixBoostSpeed.getValue());
            mc.thePlayer.motionY = 0.42;
            this.boosted = true;
        }
        if (this.receivedFlag && this.boosted) {
            if (this.parent.autoDisable.getValue()) {
                this.parent.setEnabled(false);
            }
            this.canBoost = false;
            this.receivedFlag = false;
        }
    }
}
