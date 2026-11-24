package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.JumpEvent;
import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntity;
import myau.mixin.IAccessorKeyBinding;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.util.MoveUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

public class Strafe extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final FloatProperty strength = new FloatProperty("strength", 0.5F, 0.0F, 1.0F);
    public final BooleanProperty noMoveStop = new BooleanProperty("no-move-stop", false);
    public final BooleanProperty onGroundStrafe = new BooleanProperty("on-ground-strafe", false);
    public final BooleanProperty allDirectionsJump = new BooleanProperty("all-directions-jump", false);

    private boolean wasDown = false;
    private boolean jump = false;

    public Strafe() {
        super("Strafe", false);
    }

    @Override
    public void onEnabled() {
        this.wasDown = false;
        this.jump = false;
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.jump) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        if (player.onGround && mc.gameSettings.keyBindJump.isKeyDown() && this.allDirectionsJump.getValue() && MoveUtil.isMoving()
                && !(MoveUtil.isInLiquid() || player.isOnLadder() || ((IAccessorEntity) player).getIsInWeb())) {
            if (mc.gameSettings.keyBindJump.isKeyDown()) {
                ((IAccessorKeyBinding) mc.gameSettings.keyBindJump).setPressed(false);
                this.wasDown = true;
            }
            // Jump towards the movement direction instead of the view direction
            float yaw = player.rotationYaw;
            player.rotationYaw = MoveUtil.getMoveYaw();
            MoveUtil.tryJump();
            player.rotationYaw = yaw;
            this.jump = true;
            if (this.wasDown) {
                ((IAccessorKeyBinding) mc.gameSettings.keyBindJump).setPressed(true);
                this.wasDown = false;
            }
        } else {
            this.jump = false;
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        if (!MoveUtil.isMoving()) {
            if (this.noMoveStop.getValue()) {
                player.motionX = 0.0;
                player.motionZ = 0.0;
            }
            return;
        }

        float strength = this.strength.getValue();
        float shotSpeed = (float) MoveUtil.getSpeed();
        float speed = shotSpeed * strength;
        double motionX = player.motionX * (1 - strength);
        double motionZ = player.motionZ * (1 - strength);

        if (!player.onGround || this.onGroundStrafe.getValue()) {
            double yaw = MoveUtil.getDirection();
            player.motionX = -Math.sin(yaw) * speed + motionX;
            player.motionZ = Math.cos(yaw) * speed + motionZ;
        }
    }
}
