package myau.module.modules.flymodes.other;

import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorC03PacketPlayer;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

/**
 * Tatako fly (from the OpenMyau-Plus TatakoFly).
 *
 * Every outgoing movement packet is rewritten to the position the fly was started at, sinking
 * 0.09 per packet and flagged as on ground, while the client moves freely. Server teleports are
 * answered and swallowed, and become the new spoofed position.
 */
public class Tatako extends FlyMode {
    // spoofed position
    private double x = 0.0;
    private double y = 0.0;
    private double z = 0.0;

    public Tatako() {
        super("Tatako");
    }

    @Override
    public void onEnable() {
        if (mc.thePlayer == null) {
            return;
        }

        this.x = mc.thePlayer.posX;
        this.y = mc.thePlayer.posY;
        this.z = mc.thePlayer.posZ;
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer == null) {
            return;
        }

        // These still pass onPacket below, like every other movement packet of this mode.
        this.sendPosLook(mc.thePlayer.posX + 0.05, mc.thePlayer.posY);
        this.sendPosLook(mc.thePlayer.posX, mc.thePlayer.posY + 0.42);
        this.sendPosLook(mc.thePlayer.posX, mc.thePlayer.posY + 0.7532);
        this.sendPosLook(mc.thePlayer.posX, mc.thePlayer.posY + 1.0);
    }

    private void sendPosLook(double posX, double posY) {
        PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(
                posX,
                posY,
                mc.thePlayer.posZ,
                mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch,
                true
        ));
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer == null) {
            return;
        }

        MoveUtil.stop();

        FlyUtil.strafe(this.parent.tatakoHSpeed.getValue(), true);

        if (GameSettings.isKeyDown(mc.gameSettings.keyBindJump)) {
            mc.thePlayer.motionY = this.parent.tatakoVSpeed.getValue().doubleValue();
        }

        if (GameSettings.isKeyDown(mc.gameSettings.keyBindSneak)) {
            mc.thePlayer.motionY = -this.parent.tatakoVSpeed.getValue().doubleValue();
        }
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.SEND) {
            this.handleSendPacket(event);
        } else if (event.getType() == EventType.RECEIVE) {
            this.handleReceivePacket(event);
        }
    }

    private void handleSendPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();

        if (!(packet instanceof C03PacketPlayer)) {
            return;
        }

        this.y -= 0.09;

        IAccessorC03PacketPlayer accessor = (IAccessorC03PacketPlayer) packet;
        accessor.setX(this.x);
        accessor.setY(this.y);
        accessor.setZ(this.z);

        accessor.setOnGround(true);
    }

    private void handleReceivePacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();

        if (!(packet instanceof S08PacketPlayerPosLook)) {
            return;
        }
        S08PacketPlayerPosLook posLook = (S08PacketPlayerPosLook) packet;

        this.x = posLook.getX();
        this.y = posLook.getY();
        this.z = posLook.getZ();

        PacketUtil.sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(
                this.x,
                this.y,
                this.z,
                posLook.getYaw(),
                posLook.getPitch(),
                false
        ));

        event.setCancelled(true);
    }
}
