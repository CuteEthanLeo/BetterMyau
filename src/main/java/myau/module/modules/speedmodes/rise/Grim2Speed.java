package myau.module.modules.speedmodes.rise;

import myau.events.JumpEvent;
import myau.events.PacketEvent;
import myau.events.StrafeEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

public class Grim2Speed extends SpeedMode {
    private boolean shouldJump = false;
    private int groundTicks = 0;
    private int strafeTicks = 0;
    private boolean sentDoublePacket = false;

    public Grim2Speed() {
        super("Grim2Speed");
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        this.groundTicks = mc.thePlayer.onGround ? this.groundTicks + 1 : 0;
        if (this.strafeTicks > -1) {
            double d3 = 0.03;
            if (this.strafeTicks % 2 == 0) {
                d3 = mc.thePlayer.onGround ? 0.085 : 0.03;
            }
            SpeedUtil.moveFlying(d3 * (double) parent.grim2Speed.getValue());
        }
        this.strafeTicks++;
    }

    @Override
    public void onMotion() {
        this.shouldJump = false;
        if (this.strafeTicks % 2 == 0) {
            this.sendDoublePacket();
            this.sentDoublePacket = true;
        }
    }

    /** Called for both the PRE and the POST jump event, like in xw (cancelling POST does nothing). */
    @Override
    public void onJump(JumpEvent event) {
        // xw checks player.isJumping, which vanilla copies from movementInput.jump every tick.
        if (!mc.thePlayer.movementInput.jump) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (mc.thePlayer == null) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            if (this.strafeTicks % 2 == 1) {
                this.strafeTicks++;
            }
            MoveUtil.setTimer(1.0F);
        }
        if (event.getPacket() instanceof S12PacketEntityVelocity) {
            this.sentDoublePacket = false;
            if (((S12PacketEntityVelocity) event.getPacket()).getEntityID() == mc.thePlayer.getEntityId()) {
                this.shouldJump = true;
            }
        }
    }

    @Override
    public void onEnable() {
        this.sendDoublePacket();
        this.strafeTicks = 0;
        this.groundTicks = 0;
        MoveUtil.setTimer(1.0F);
    }

    @Override
    public void onDisable() {
        MoveUtil.setTimer(1.0F);
    }

    private void sendDoublePacket() {
        if (!parent.grim2HighPingMode.getValue()) {
            PacketUtil.sendPacket(new C03PacketPlayer(true));
            PacketUtil.sendPacket(new C03PacketPlayer(false));
        } else {
            PacketUtil.sendPacket(new C03PacketPlayer(false));
            PacketUtil.sendPacket(new C03PacketPlayer(false));
        }
    }
}
