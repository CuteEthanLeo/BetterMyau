package myau.module.modules.flymodes.hypixel;

import myau.events.BlockBBEvent;
import myau.events.JumpEvent;
import myau.events.PacketEvent;
import myau.events.StepEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.flymodes.FlyMode;
import myau.util.MoveUtil;
import myau.util.TickTimer;
import myau.util.TimerUtil;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;

public class Hypixel extends FlyMode {
    private final TickTimer tickTimer = new TickTimer();
    private final TimerUtil msTimer = new TimerUtil();

    public Hypixel() {
        super("Hypixel");
    }

    @Override
    public void onEnable() {
        this.msTimer.reset();
        this.tickTimer.reset();
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        int boostDelay = this.parent.hypixelBoostDelay.getValue();
        if (this.parent.hypixelBoost.getValue() && !this.msTimer.hasTimeElapsed(boostDelay)) {
            long timeLeft = boostDelay - this.msTimer.getElapsedTime();
            MoveUtil.setTimer(1.0F + this.parent.hypixelBoostTimer.getValue() * ((float) timeLeft / (float) boostDelay));
        } else {
            MoveUtil.setTimer(1.0F);
        }

        this.tickTimer.update();

        if (this.tickTimer.hasTimePassed(2)) {
            mc.thePlayer.setPosition(mc.thePlayer.posX, mc.thePlayer.posY + 1.0E-5, mc.thePlayer.posZ);
            this.tickTimer.reset();
        }
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C03PacketPlayer) {
            ((IAccessorC03PacketPlayer) event.getPacket()).setOnGround(false);
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
