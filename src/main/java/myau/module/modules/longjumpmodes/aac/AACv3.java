package myau.module.modules.longjumpmodes.aac;

import myau.events.UpdateEvent;
import myau.module.modules.longjumpmodes.LongJumpMode;
import net.minecraft.util.EnumFacing;

public class AACv3 extends LongJumpMode {
    public AACv3() {
        super("AACv3");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        if (mc.thePlayer.fallDistance > 0.5F && !this.parent.teleported) {
            double value = 3.0;
            EnumFacing horizontalFacing = mc.thePlayer.getHorizontalFacing();
            double x = 0.0;
            double z = 0.0;
            switch (horizontalFacing) {
                case NORTH:
                    z = -value;
                    break;
                case EAST:
                    x = value;
                    break;
                case SOUTH:
                    z = value;
                    break;
                case WEST:
                    x = -value;
                    break;
                default:
                    break;
            }
            mc.thePlayer.setPosition(mc.thePlayer.posX + x, mc.thePlayer.posY, mc.thePlayer.posZ + z);
            this.parent.teleported = true;
        }
    }
}
