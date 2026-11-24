package myau.module.modules.flymodes.other;

import myau.events.BlockBBEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import net.minecraft.util.AxisAlignedBB;

public class Collide extends FlyMode {
    public Collide() {
        super("Collide");
    }

    @Override
    public void onBB(BlockBBEvent event) {
        if (!mc.gameSettings.keyBindJump.isKeyDown() && mc.gameSettings.keyBindSneak.isKeyDown()) {
            return;
        }
        if (FlyUtil.isPassable(event.getBlock())) {
            event.setBoundingBox(new AxisAlignedBB(-2.0, -1.0, -2.0, 2.0, 1.0, 2.0).offset(event.getX(), event.getY(), event.getZ()));
        }
    }
}
