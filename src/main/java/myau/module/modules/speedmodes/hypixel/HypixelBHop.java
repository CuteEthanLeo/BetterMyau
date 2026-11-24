package myau.module.modules.speedmodes.hypixel;

import myau.event.types.EventType;
import myau.events.JumpEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

/**
 * Ported from LiquidBounce Nextgen SpeedHypixelBHop (GPLv3).
 * anticheat: Watchdog (NCP), anticheatVersion 12.12.2023, tested on hypixel.net
 */
public class HypixelBHop extends SpeedMode {
    private static final double BASE_HORIZONTAL_MODIFIER = 0.0004;
    private static final double HORIZONTAL_SPEED_AMPLIFIER = 0.0007;
    private static final double VERTICAL_SPEED_AMPLIFIER = 0.0004;
    /** Vanilla max speed w/o Speed: 0.2857671997172534, Speed I: 0.2919055664000211, Speed II: 0.2999088445964323 */
    private static final double AT_LEAST = 0.281;
    private static final double BASH = 0.2857671997172534;
    private static final double SPEED_EFFECT_CONST = 0.008003278196411223;

    private boolean wasFlagged = false;
    private int damageBoostTicks = -1;
    private boolean damageBoostFall = false;

    public HypixelBHop() {
        super("HypixelBHop");
    }

    @Override
    public void onEnable() {
        this.wasFlagged = false;
        this.damageBoostTicks = -1;
    }

    @Override
    public void onDisable() {
        this.wasFlagged = false;
    }

    @Override
    public void onMoveInput() {
        if (mc.thePlayer.onGround && MoveUtil.isMoving()) {
            mc.thePlayer.movementInput.jump = true;
        }
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        // damage boost: one tick after our velocity packet, keep the new speed but in the input direction
        if (this.damageBoostTicks >= 0 && --this.damageBoostTicks < 0) {
            double speed = MoveUtil.getSpeed();
            if (this.damageBoostFall) {
                speed = Math.max(speed, BASH * SpeedUtil.speedAmplifier());
            }
            MoveUtil.strafe(speed);
        }
        if (player.onGround) {
            MoveUtil.strafe();
            return;
        }
        double horizontalMod = parent.bhopHorizontalAcceleration.getValue()
                ? BASE_HORIZONTAL_MODIFIER + HORIZONTAL_SPEED_AMPLIFIER * SpeedUtil.speedAmplifier()
                : 0.0;
        double yMod = parent.bhopVerticalAcceleration.getValue() && player.motionY < 0 && player.fallDistance < 1
                ? VERTICAL_SPEED_AMPLIFIER
                : 0.0;
        player.motionX *= 1.0 + horizontalMod;
        player.motionY *= 1.0 + yMod;
        player.motionZ *= 1.0 + horizontalMod;
    }

    @Override
    public void onJump(JumpEvent event) {
        // Nextgen PlayerJumpEvent fires at the head of jump(), before vanilla adds the sprint boost
        if (event.getType() != EventType.PRE) {
            return;
        }
        double atLeast = this.wasFlagged ? 0.0 : AT_LEAST + SPEED_EFFECT_CONST * SpeedUtil.speedAmplifier();
        MoveUtil.strafe(Math.max(MoveUtil.getSpeed(), atLeast));
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE) {
            return;
        }
        if (event.getPacket() instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
            if (packet.getEntityID() == mc.thePlayer.getEntityId()) {
                // fall damage velocity: no horizontal part, motionY about -0.0783 (-626 / 8000 on 1.8)
                this.damageBoostFall = packet.getMotionX() == 0 && packet.getMotionZ() == 0
                        && packet.getMotionY() >= -628 && packet.getMotionY() <= -624;
                this.damageBoostTicks = 1;
            }
        } else if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.wasFlagged = true;
        }
    }
}
