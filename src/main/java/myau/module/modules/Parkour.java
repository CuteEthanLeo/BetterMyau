package myau.module.modules;

import myau.event.EventTarget;
import myau.events.MoveInputEvent;
import myau.module.Module;
import myau.util.MoveUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

public class Parkour extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public Parkour() {
        super("Parkour", false);
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        // Jump at the edge: still flagged on ground, but nothing is left below the feet.
        if (MoveUtil.isMoving() && player.onGround && !player.isSneaking() && !mc.gameSettings.keyBindSneak.isKeyDown()
                && mc.theWorld.getCollidingBoundingBoxes(
                player, player.getEntityBoundingBox().offset(0.0, -0.5, 0.0).expand(-0.001, 0.0, -0.001)
        ).isEmpty()) {
            player.movementInput.jump = true;
        }
    }
}
