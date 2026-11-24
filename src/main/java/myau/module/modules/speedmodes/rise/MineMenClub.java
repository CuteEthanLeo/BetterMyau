package myau.module.modules.speedmodes.rise;

import myau.events.JumpEvent;
import myau.events.MoveEvent;
import myau.events.PacketEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

public class MineMenClub extends SpeedMode {
    private int jumpCount = 0;
    private double velocityX = 0.0;
    private double velocityZ = 0.0;

    public MineMenClub() {
        super("MineMenClub");
    }

    @Override
    public void onJump(JumpEvent event) {
        this.jumpCount++;
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (mc.thePlayer == null || !(event.getPacket() instanceof S12PacketEntityVelocity)) {
            return;
        }
        S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
        if (packet.getEntityID() == mc.thePlayer.getEntityId()) {
            this.velocityX = (double) packet.getMotionX() / 8000.0;
            this.velocityZ = (double) packet.getMotionZ() / 8000.0;
        }
    }

    @Override
    public void onMove(MoveEvent event) {
        double d0 = SpeedUtil.moveAngle();
        double d1 = SpeedUtil.motionAngle();
        if (mc.thePlayer.hurtTime > 8) {
            if (mc.thePlayer.onGround) {
                MoveUtil.strafe();
            }
            if (SpeedUtil.angleDiff(d0, d1) < 90.0) {
                MoveUtil.strafe();
            } else if (parent.mmcAllowMoreStrafing.getValue()) {
                MoveUtil.strafe(SpeedUtil.speed() * 0.7F);
            }
        }
    }

    @Override
    public void onMotion() {
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
        }
    }

    // xw's onStrafe only evaluates a condition with an empty "boost logic" body, so there is
    // nothing to port for it.

    @Override
    public void onEnable() {
        this.velocityX = 0.0;
        this.velocityZ = 0.0;
    }
}
