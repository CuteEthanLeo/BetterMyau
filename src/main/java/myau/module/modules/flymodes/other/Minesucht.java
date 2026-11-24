package myau.module.modules.flymodes.other;

import myau.events.UpdateEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import net.minecraft.util.Vec3;

public class Minesucht extends FlyMode {
    private long minesuchtTP = 0L;

    public Minesucht() {
        super("Minesucht");
    }

    @Override
    public void onUpdate(UpdateEvent event) {
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;

        if (!mc.gameSettings.keyBindForward.isKeyDown()) {
            return;
        }

        if (System.currentTimeMillis() - this.minesuchtTP > 99L) {
            Vec3 look = mc.thePlayer.getLook(1.0F);
            Vec3 vec = mc.thePlayer.getPositionEyes(1.0F).addVector(look.xCoord * 7.0, look.yCoord * 7.0, look.zCoord * 7.0);

            if (mc.thePlayer.fallDistance > 0.8) {
                FlyUtil.sendPosition(x, y + 50.0, z, false);
                FlyUtil.sendPosition(x, y + 20.0, z, true);
                mc.thePlayer.fall(100.0F, 100.0F);
                mc.thePlayer.fallDistance = 0.0F;
            }
            FlyUtil.sendPosition(vec.xCoord, y + 50.0, vec.zCoord, true);
            FlyUtil.sendPosition(x, y, z, false);
            FlyUtil.sendPosition(vec.xCoord, y, vec.zCoord, true);
            FlyUtil.sendPosition(x, y, z, false);
            this.minesuchtTP = System.currentTimeMillis();
        } else {
            FlyUtil.sendPosition(x, y, z, false);
            FlyUtil.sendPosition(x, y, z, true);
        }
    }
}
