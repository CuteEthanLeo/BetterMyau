package myau.module.modules.nofallmodes.other;

import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.modules.nofallmodes.NoFallMode;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * Water-bucket MLG from LeaderClient NoFall (https://github.com/Mornly/LeaderClient), GPLv3.
 * Switches to the bucket while falling, places it when looking at the top face of the block below,
 * then picks the water back up 150 ms later and switches back. With mlg-rotation the real pitch is forced to 90.
 */
public class MLG extends NoFallMode {
    private static final long PLACE_DELAY = 500L;
    private static final long PICKUP_WAIT = 150L;
    private long lastPlace = 0L;
    private int lastSlot = -1;

    public MLG() {
        super("MLG");
    }

    @Override
    public void onEnable() {
        this.lastPlace = 0L;
        this.lastSlot = -1;
    }

    @Override
    public void onDisable() {
        this.lastPlace = 0L;
        this.lastSlot = -1;
    }

    private boolean fallCheck() {
        return !mc.thePlayer.onGround && mc.thePlayer.fallDistance >= parent.mlgDistance.getValue();
    }

    /** TickEvent PRE. */
    @Override
    public void onTick() {
        if (mc.isGamePaused() || mc.thePlayer.capabilities.isFlying || mc.thePlayer.capabilities.isCreativeMode) {
            return;
        }
        if (!this.fallCheck()) {
            return;
        }
        boolean rotate = parent.mlgRotation.getValue();
        MovingObjectPosition mop = getTarget(mc.playerController.getBlockReachDistance(), mc.thePlayer.rotationYaw, rotate ? 90.0F : mc.thePlayer.rotationPitch);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || mop.sideHit != EnumFacing.UP) {
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - this.lastPlace) < PLACE_DELAY) {
            return;
        }
        if (!isItem(mc.thePlayer.getHeldItem(), Items.water_bucket)) {
            this.attemptSwitch();
        }
        if (rotate && mc.thePlayer.rotationPitch < 85.0F) {
            return;
        }
        this.lastPlace = now;
        this.useCurrentItem();
    }

    /** UpdateEvent PRE, also while standing in the placed water. */
    @Override
    public void onUpdateAlways(UpdateEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        long sincePlace = Math.abs(System.currentTimeMillis() - this.lastPlace);
        if (parent.mlgRotation.getValue() && (this.fallCheck() || sincePlace < PLACE_DELAY) && this.getWaterBucketSlot() != -1) {
            mc.thePlayer.rotationPitch = 90.0F;
        }
        if (sincePlace > PICKUP_WAIT && isItem(mc.thePlayer.getHeldItem(), Items.bucket)) {
            this.useCurrentItem();
            if (this.lastSlot != -1) {
                mc.thePlayer.inventory.currentItem = this.lastSlot;
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                this.lastSlot = -1;
            }
        }
    }

    private void attemptSwitch() {
        int slot = this.getWaterBucketSlot();
        if (slot != -1) {
            this.lastSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = slot;
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        }
    }

    private int getWaterBucketSlot() {
        for (int slot = 0; slot < InventoryPlayer.getHotbarSize(); ++slot) {
            if (isItem(mc.thePlayer.inventory.getStackInSlot(slot), Items.water_bucket)) {
                return slot;
            }
        }
        return -1;
    }

    private static MovingObjectPosition getTarget(double reach, float yaw, float pitch) {
        Vec3 eyeVec = mc.thePlayer.getPositionEyes(1.0F);
        float y = -yaw * 0.017453292F;
        float p = -pitch * 0.017453292F;
        float f = MathHelper.cos(y - 3.1415927F);
        float f2 = MathHelper.sin(y - 3.1415927F);
        float f3 = -MathHelper.cos(p);
        float f4 = MathHelper.sin(p);
        Vec3 lookVec = new Vec3(f2 * f3, f4, f * f3);
        Vec3 sumVec = eyeVec.addVector(lookVec.xCoord * reach, lookVec.yCoord * reach, lookVec.zCoord * reach);
        return mc.theWorld.rayTraceBlocks(eyeVec, sumVec, false, false, false);
    }

    private void useCurrentItem() {
        mc.getNetHandler().addToSendQueue(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
    }

    private static boolean isItem(ItemStack itemStack, Item item) {
        return itemStack != null && itemStack.getItem() == item;
    }
}
