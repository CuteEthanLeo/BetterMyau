package myau.module.modules.flymodes.vulcan;

import myau.events.BlockBBEvent;
import myau.events.PacketEvent;
import myau.module.modules.flymodes.FlyMode;
import myau.module.modules.flymodes.FlyUtil;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;

public class VulcanGhost extends FlyMode {
    public VulcanGhost() {
        super("VulcanGhost");
    }

    @Override
    public void onEnable() {
        FlyUtil.chat("Ensure that you sneak on landing.");
        FlyUtil.chat("After landing, go backward (Air) and go forward to landing location, then sneak again.");
        FlyUtil.chat("And then you can turn off fly.");
    }

    @Override
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            event.setCancelled(true);
        }
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
