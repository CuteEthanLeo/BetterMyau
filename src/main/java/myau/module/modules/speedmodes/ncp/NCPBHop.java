package myau.module.modules.speedmodes.ncp;

import myau.events.MoveEvent;
import myau.module.modules.speedmodes.SpeedMode;
import myau.module.modules.speedmodes.SpeedUtil;
import myau.util.MoveUtil;
import net.minecraft.potion.Potion;
import net.minecraft.util.MovementInput;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class NCPBHop extends SpeedMode {
    private int level = 1;
    private double moveSpeed = 0.2873;
    private double lastDist = 0.0;
    private int timerDelay = 0;

    public NCPBHop() {
        super("NCPBHop");
    }

    @Override
    public void onEnable() {
        MoveUtil.setTimer(1.0F);
        this.level = SpeedUtil.collides(mc.thePlayer.motionY) || mc.thePlayer.isCollidedVertically ? 1 : 4;
    }

    @Override
    public void onDisable() {
        MoveUtil.setTimer(1.0F);
        this.moveSpeed = this.getBaseMoveSpeed();
        this.level = 0;
    }

    @Override
    public void onMotion() {
        double xDist = mc.thePlayer.posX - mc.thePlayer.prevPosX;
        double zDist = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
        this.lastDist = Math.sqrt(xDist * xDist + zDist * zDist);
    }

    @Override
    public void onMove(MoveEvent event) {
        ++this.timerDelay;
        this.timerDelay %= 5;
        if (this.timerDelay != 0) {
            MoveUtil.setTimer(1.0F);
        } else if (MoveUtil.isMoving()) {
            MoveUtil.setTimer(1.3F);
            mc.thePlayer.motionX *= 1.0199999809265137;
            mc.thePlayer.motionZ *= 1.0199999809265137;
        }
        if (mc.thePlayer.onGround && MoveUtil.isMoving()) {
            this.level = 2;
        }
        if (round(mc.thePlayer.posY - (double) ((int) mc.thePlayer.posY)) == round(0.138)) {
            mc.thePlayer.motionY -= 0.08;
            event.setY(event.getY() - 0.09316090325960147);
            mc.thePlayer.posY -= 0.09316090325960147;
        }
        if (this.level == 1 && MoveUtil.isMoving()) {
            this.level = 2;
            this.moveSpeed = 1.35 * this.getBaseMoveSpeed() - 0.01;
        } else if (this.level == 2) {
            this.level = 3;
            mc.thePlayer.motionY = 0.399399995803833;
            event.setY(0.399399995803833);
            this.moveSpeed *= 2.149;
        } else if (this.level == 3) {
            this.level = 4;
            double difference = 0.66 * (this.lastDist - this.getBaseMoveSpeed());
            this.moveSpeed = this.lastDist - difference;
        } else {
            if (SpeedUtil.collides(mc.thePlayer.motionY) || mc.thePlayer.isCollidedVertically) {
                this.level = 1;
            }
            this.moveSpeed = this.lastDist - this.lastDist / 159.0;
        }
        this.moveSpeed = Math.max(this.moveSpeed, this.getBaseMoveSpeed());
        MovementInput movementInput = mc.thePlayer.movementInput;
        float forward = movementInput.moveForward;
        float strafe = movementInput.moveStrafe;
        float yaw = mc.thePlayer.rotationYaw;
        if (forward == 0.0F && strafe == 0.0F) {
            event.zeroXZ();
        } else if (forward != 0.0F) {
            if (strafe >= 1.0F) {
                yaw += (float) (forward > 0.0F ? -45 : 45);
                strafe = 0.0F;
            } else if (strafe <= -1.0F) {
                yaw += (float) (forward > 0.0F ? 45 : -45);
                strafe = 0.0F;
            }
            if (forward > 0.0F) {
                forward = 1.0F;
            } else if (forward < 0.0F) {
                forward = -1.0F;
            }
        }
        double mx2 = Math.cos(Math.toRadians((double) yaw + 90.0));
        double mz2 = Math.sin(Math.toRadians((double) yaw + 90.0));
        event.setX((double) forward * this.moveSpeed * mx2 + (double) strafe * this.moveSpeed * mz2);
        event.setZ((double) forward * this.moveSpeed * mz2 - (double) strafe * this.moveSpeed * mx2);
        mc.thePlayer.stepHeight = 0.6F;
        if (!MoveUtil.isMoving()) {
            event.zeroXZ();
        }
    }

    private double getBaseMoveSpeed() {
        double baseSpeed = 0.2873;
        if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
            // Same operator precedence as xw (the "+ 1" sits outside the amplifier product).
            baseSpeed *= 1.0 + 0.2 * (double) mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1.0;
        }
        return baseSpeed;
    }

    private static double round(double value) {
        return new BigDecimal(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
