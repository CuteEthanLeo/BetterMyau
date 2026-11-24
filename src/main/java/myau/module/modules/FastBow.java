package myau.module.modules;

import myau.events.Render2DEvent;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorEntityPlayer;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

/** Fires a fully charged arrow every tick by sending the charge time as a burst of look packets. */
public class FastBow extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final IntProperty packets = new IntProperty("packets", 20, 3, 20);

    public FastBow() {
        super("FastBow", false);
    }

    // LOWEST: after every module that sets a silent rotation, so getNewYaw/Pitch is the rotation in effect.
    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (!player.isUsingItem()) {
            return;
        }
        ItemStack currentItem = player.inventory.getCurrentItem();
        if (currentItem == null || !(currentItem.getItem() instanceof ItemBow)) {
            return;
        }
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(BlockPos.ORIGIN, 255, player.getCurrentEquippedItem(), 0.0F, 0.0F, 0.0F));
        float yaw = event.getNewYaw();
        float pitch = event.getNewPitch();
        for (int i = 0; i < this.packets.getValue(); i++) {
            PacketUtil.sendPacket(new C05PacketPlayerLook(yaw, pitch, true));
        }
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        ((IAccessorEntityPlayer) player).setItemInUseCount(currentItem.getMaxItemUseDuration() - 1);
    }

    // ---------------------------------------------------------------- dynamic island


    /** While shooting: how many packets each shot uses. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !mc.thePlayer.isUsingItem()) return;
        net.minecraft.item.ItemStack bow = mc.thePlayer.getHeldItem();
        if (bow == null || !(bow.getItem() instanceof net.minecraft.item.ItemBow)) return;
        float charge = Math.min(1.0F, mc.thePlayer.getItemInUseDuration() / 20.0F);
        myau.ui.glass.Island.live("bow", myau.ui.glass.Island.P_PROGRESS, "FastBow §f" + this.packets.getValue() + "x §7" + Math.round(charge * 100) + "%",
                myau.ui.GlassTheme.accent(), charge);
    }
}
