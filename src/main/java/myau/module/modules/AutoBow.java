package myau.module.modules;

import myau.events.Render2DEvent;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

/** Releases the bow as soon as it is fully charged. */
public class AutoBow extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final BooleanProperty waitForBowAimbot = new BooleanProperty("wait-for-bow-aimbot", true);

    public AutoBow() {
        super("AutoBow", false);
    }

    // HIGH: runs before ProjectileAimbot, so hasTarget() describes the rotation already sent last tick.
    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        ItemStack held = player.getHeldItem();
        if (!player.isUsingItem() || held == null || !(held.getItem() instanceof ItemBow) || player.getItemInUseDuration() <= 20) {
            return;
        }
        if (this.waitForBowAimbot.getValue()) {
            ProjectileAimbot aimbot = (ProjectileAimbot) Myau.moduleManager.modules.get(ProjectileAimbot.class);
            if (aimbot != null && aimbot.isEnabled() && !aimbot.hasTarget()) {
                return;
            }
        }
        player.stopUsingItem();
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
    }

    // ---------------------------------------------------------------- dynamic island


    /** How far the bow is drawn. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !mc.thePlayer.isUsingItem()) return;
        net.minecraft.item.ItemStack bow = mc.thePlayer.getHeldItem();
        if (bow == null || !(bow.getItem() instanceof net.minecraft.item.ItemBow)) return;
        float charge = Math.min(1.0F, mc.thePlayer.getItemInUseDuration() / 20.0F);
        myau.ui.glass.Island.live("bow", myau.ui.glass.Island.P_PROGRESS, "Bow §f" + Math.round(charge * 100) + "%",
                charge >= 1.0F ? myau.ui.GlassTheme.OK : myau.ui.GlassTheme.accent(), charge);
    }
}
