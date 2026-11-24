package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.JumpEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

public class ReverseStep extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final FloatProperty motion = new FloatProperty("motion", 1.0F, 0.21F, 4.0F);

    private boolean jumped = false;

    public ReverseStep() {
        super("ReverseStep", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }

        // Tracked even while disabled so a jump made before enabling is not pulled down.
        if (player.onGround) {
            this.jumped = false;
        }
        if (player.motionY > 0.0) {
            this.jumped = true;
        }

        if (!this.isEnabled()) {
            return;
        }

        AxisAlignedBB box = player.getEntityBoundingBox();
        if (this.isAllLiquid(box)
                || this.isAllLiquid(AxisAlignedBB.fromBounds(box.maxX, box.maxY, box.maxZ, box.minX, box.minY - 0.01, box.minZ))) {
            return;
        }

        if (!mc.gameSettings.keyBindJump.isKeyDown() && !player.onGround && !player.movementInput.jump && player.motionY <= 0.0
                && player.fallDistance <= 1.0F && !this.jumped) {
            player.motionY = -this.motion.getValue();
        }
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        this.jumped = true;
    }

    /** True when every block the player stands in, at the height of {@code box.minY}, is a liquid. */
    private boolean isAllLiquid(AxisAlignedBB box) {
        AxisAlignedBB playerBox = mc.thePlayer.getEntityBoundingBox();
        int y = MathHelper.floor_double(box.minY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(0, 0, 0);
        for (int x = MathHelper.floor_double(playerBox.minX); x < MathHelper.floor_double(playerBox.maxX) + 1; x++) {
            for (int z = MathHelper.floor_double(playerBox.minZ); z < MathHelper.floor_double(playerBox.maxZ) + 1; z++) {
                if (!(mc.theWorld.getBlockState(pos.set(x, y, z)).getBlock() instanceof BlockLiquid)) {
                    return false;
                }
            }
        }
        return true;
    }
}
