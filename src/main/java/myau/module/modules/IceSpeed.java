package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;

public class IceSpeed extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int NCP = 0, AAC = 1, SPARTAN = 2;
    private static final float DEFAULT_SLIPPERINESS = 0.98F;

    public final ModeProperty mode = new ModeProperty("mode", NCP, new String[]{"NCP", "AAC", "Spartan"});

    public IceSpeed() {
        super("IceSpeed", false);
    }

    private static void setIceSlipperiness(float slipperiness) {
        Blocks.ice.slipperiness = slipperiness;
        Blocks.packed_ice.slipperiness = slipperiness;
    }

    @Override
    public void onEnabled() {
        if (this.mode.getValue() == NCP) {
            setIceSlipperiness(0.39F);
        }
    }

    @Override
    public void onDisabled() {
        setIceSlipperiness(DEFAULT_SLIPPERINESS);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        int mode = this.mode.getValue();
        if (mode == NCP) {
            setIceSlipperiness(0.39F);
        } else {
            setIceSlipperiness(DEFAULT_SLIPPERINESS);
        }

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        if (!player.onGround || player.isOnLadder() || player.isSneaking() || !player.isSprinting() || !MoveUtil.isMoving()) {
            return;
        }

        Block below = mc.theWorld.getBlockState(player.getPosition().down()).getBlock();
        if (below != Blocks.ice && below != Blocks.packed_ice) {
            return;
        }

        switch (mode) {
            case AAC:
                player.motionX *= 1.342;
                player.motionZ *= 1.342;
                setIceSlipperiness(0.6F);
                break;
            case SPARTAN:
                Block upBlock = mc.theWorld.getBlockState(new BlockPos(player).up(2)).getBlock();

                if (upBlock != Blocks.air) {
                    player.motionX *= 1.342;
                    player.motionZ *= 1.342;
                } else {
                    player.motionX *= 1.18;
                    player.motionZ *= 1.18;
                }

                setIceSlipperiness(0.6F);
                break;
            default:
                break;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
