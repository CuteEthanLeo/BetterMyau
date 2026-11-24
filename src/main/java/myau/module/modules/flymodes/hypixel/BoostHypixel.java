package myau.module.modules.flymodes.hypixel;

import myau.events.BlockBBEvent;
import myau.events.JumpEvent;
import myau.events.MoveEvent;
import myau.events.PacketEvent;
import myau.events.StepEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;
import myau.util.TickTimer;
import net.minecraft.init.Blocks;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;

public class BoostHypixel extends FlyMode {
    private int state = 1;
    private double moveSpeed = 0.0;
    private double lastDistance = 0.0;
    private final TickTimer tickTimer = new TickTimer();

    public BoostHypixel() {
        super("BoostHypixel");
    }

    @Override
    public void onEnable() {
        if (!mc.thePlayer.onGround) {
            return;
        }

        this.tickTimer.reset();

        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;

        for (int i = 0; i < 10; i++) {
            // Imagine flagging to NCP.
            FlyUtil.sendPosition(x, y, z, true);
        }

        double fallDistance = 3.0125; // add 0.0125 to ensure we get the fall dmg

        while (fallDistance > 0.0) {
            FlyUtil.sendPosition(x, y + 0.0624986421, z, false);
            FlyUtil.sendPosition(x, y + 0.0625, z, false);
            FlyUtil.sendPosition(x, y + 0.0624986421, z, false);
            FlyUtil.sendPosition(x, y + 0.0000013579, z, false);
            fallDistance -= 0.0624986421;
        }

        FlyUtil.sendPosition(x, y, z, true);

        MoveUtil.tryJump();

        mc.thePlayer.posY += 0.42F; // Visual

        this.state = 1;
        this.moveSpeed = 0.1;
        this.lastDistance = 0.0;
    }

    @Override
    public void onMotion() {
        this.tickTimer.update();

        if (this.tickTimer.hasTimePassed(2)) {
            mc.thePlayer.setPosition(mc.thePlayer.posX, mc.thePlayer.posY + 1.0E-5, mc.thePlayer.posZ);

            this.tickTimer.reset();
        }

        mc.thePlayer.motionY = 0.0;
    }

    @Override
    public void onPostMotion() {
        double xDist = mc.thePlayer.posX - mc.thePlayer.prevPosX;
        double zDist = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
        this.lastDistance = Math.sqrt(xDist * xDist + zDist * zDist);
    }

    @Override
    public void onMove(MoveEvent event) {
        if (!MoveUtil.isMoving()) {
            event.zeroXZ();
            return;
        }

        boolean hasSpeed = mc.thePlayer.isPotionActive(Potion.moveSpeed);
        double amplifier = 1.0 + (hasSpeed ? 0.2 * (mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1.0) : 0.0);

        double baseSpeed = 0.29 * amplifier;

        switch (this.state) {
            case 1:
                this.moveSpeed = (hasSpeed ? 1.56 : 2.034) * baseSpeed;
                this.state++;
                break;
            case 2:
                this.moveSpeed *= 2.16;
                this.state++;
                break;
            case 3:
                this.moveSpeed = this.lastDistance - (mc.thePlayer.ticksExisted % 2 == 0 ? 0.0103 : 0.0123) * (this.lastDistance - baseSpeed);
                this.state++;
                break;
            default:
                this.moveSpeed = this.lastDistance - this.lastDistance / 159.8;
                break;
        }

        this.moveSpeed = Math.min(this.moveSpeed, 0.3);

        double yaw = MoveUtil.getDirection();

        event.setX(-Math.sin(yaw) * this.moveSpeed);
        event.setZ(Math.cos(yaw) * this.moveSpeed);

        mc.thePlayer.motionX = event.getX();
        mc.thePlayer.motionZ = event.getZ();
    }

    @Override
    public void onPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet instanceof S08PacketPlayerPosLook) {
            FlyUtil.disable(this.parent);
            FlyUtil.chat("&8[&c&lBoostHypixel-&a&lFly&8] &cSetback detected.");
        } else if (packet instanceof C03PacketPlayer) {
            ((IAccessorC03PacketPlayer) packet).setOnGround(false);
        }
    }

    @Override
    public void onBB(BlockBBEvent event) {
        if (event.getBlock() == Blocks.air && event.getY() < mc.thePlayer.posY) {
            event.setBoundingBox(AxisAlignedBB.fromBounds(
                    event.getX(),
                    event.getY(),
                    event.getZ(),
                    event.getX() + 1.0,
                    mc.thePlayer.posY,
                    event.getZ() + 1.0
            ));
        }
    }

    @Override
    public void onJump(JumpEvent event) {
        event.setCancelled(true);
    }

    @Override
    public void onStep(StepEvent event) {
        event.setHeight(0.0F);
    }
}
