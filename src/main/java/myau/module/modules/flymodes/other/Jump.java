package myau.module.modules.flymodes.other;

import myau.events.BlockBBEvent;
import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import myau.util.MoveUtil;
import net.minecraft.util.AxisAlignedBB;

public class Jump extends FlyMode {
    public Jump() {
        super("Jump");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer == null) {
            return;
        }
        // movementInput.jump is what EntityLivingBase.isJumping was set from on the last tick
        if (mc.thePlayer.onGround && !mc.thePlayer.movementInput.jump) {
            MoveUtil.tryJump();
        }
        if ((mc.gameSettings.keyBindJump.isKeyDown() && !mc.gameSettings.keyBindSneak.isKeyDown()) || mc.thePlayer.onGround) {
            this.parent.jumpY = mc.thePlayer.posY;
        }
    }

    @Override
    public void onBB(BlockBBEvent event) {
        double jumpY = this.parent.jumpY;
        boolean jumpYCondition = !mc.gameSettings.keyBindJump.isKeyDown() && mc.gameSettings.keyBindSneak.isKeyDown()
                ? (double) event.getY() < jumpY
                : (double) event.getY() <= jumpY;
        if (FlyUtil.isPassable(event.getBlock()) && jumpYCondition) {
            // max Y of 1.0 is what the original uses
            event.setBoundingBox(AxisAlignedBB.fromBounds(
                    event.getX(),
                    event.getY(),
                    event.getZ(),
                    event.getX() + 1.0,
                    1.0,
                    event.getZ() + 1.0
            ));
        }
    }
}
