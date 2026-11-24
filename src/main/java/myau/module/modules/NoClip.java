package myau.module.modules;

import myau.event.EventTarget;
import myau.events.MoveEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.util.MoveUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

public class NoClip extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final FloatProperty speed = new FloatProperty("speed", 0.5F, 0.0F, 10.0F);

    public NoClip() {
        super("NoClip", false);
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null) {
            mc.thePlayer.noClip = false;
        }
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        float speed = this.speed.getValue();

        if (MoveUtil.isMoving()) {
            MoveUtil.strafe(speed, event);
        } else {
            event.zeroXZ();
            MoveUtil.stopXZ();
        }

        player.noClip = true;
        player.onGround = false;

        player.capabilities.isFlying = false;

        double ySpeed = 0.0;

        if (mc.gameSettings.keyBindJump.isKeyDown()) {
            ySpeed += speed;
        }
        if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            ySpeed -= speed;
        }

        player.motionY = ySpeed;
        event.setY(ySpeed);
    }
}
