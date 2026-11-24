package myau.module.modules;

import myau.events.Render2DEvent;
import com.google.common.base.CaseFormat;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;

public class Refill extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final IntProperty delay = new IntProperty("delay", 1, 0, 20);
    public final ModeProperty mode = new ModeProperty("mode", 1, new String[]{"SOUP","POT"});
    private final TimerUtil time = new TimerUtil();

    public Refill() {
        super("Refill", false);
    }

    @EventTarget
    public void onUpdate(TickEvent event) {
        if (this.isEnabled() && mc.thePlayer != null && event.getType() == EventType.PRE) {
            if (mode.getValue() == 0) {
                this.refill(Items.mushroom_stew);
            } else if (mode.getValue() == 1) {
                this.refill(ItemPotion.getItemById(373));
            }
        }
    }

    private void refill(Item targetItem) {
        if (mc.currentScreen instanceof GuiInventory) {
            if (!isHotbarFull() && this.time.hasTimeElapsed(delay.getValue() * 50)) {
                for (int i = 9; i < 36; ++i) {
                    ItemStack itemstack = mc.thePlayer.inventoryContainer.getSlot(i).getStack();
                    if (itemstack != null && itemstack.getItem() == targetItem) {
                        this.islandMoved(itemstack);
                        mc.playerController.windowClick(0, i, 0, 1, mc.thePlayer);
                        break;
                    }
                }
                this.time.reset();
            }
        }
    }

    public static boolean isHotbarFull() {
        for (int i = 0; i <= 36; ++i) {
            ItemStack itemstack = mc.thePlayer.inventory.getStackInSlot(i);
            if (itemstack == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    /** What this module moved lately, for the island. */
    private final myau.ui.glass.IslandIcons islandIcons = new myau.ui.glass.IslandIcons();
    private int islandMoved;
    private long islandLastMove;

    // ---------------------------------------------------------------- dynamic island


    /** The items it just moved, popping in one by one; lingers a moment after the last one. */
    @EventTarget
    public void onIsland(Render2DEvent event) {
        if (mc.thePlayer == null) return;
        long since = System.currentTimeMillis() - this.islandLastMove;
        if (since > 1200L || this.islandMoved == 0) {
            if (this.islandMoved != 0) {
                this.islandMoved = 0;
                this.islandIcons.clear();
            }
            return;
        }
        myau.ui.glass.Island.live("refill", myau.ui.glass.Island.P_PROGRESS, "Refill §f" + this.islandMoved + " §7item" + (this.islandMoved != 1 ? "s" : ""),
                since < 500L ? myau.ui.GlassTheme.accent() : myau.ui.GlassTheme.OK, -1, this.islandIcons.stacks(), this.islandIcons.serial());
    }

    private void islandMoved(net.minecraft.item.ItemStack stack) {
        if (stack == null) return;
        this.islandIcons.push(stack);
        this.islandMoved++;
        this.islandLastMove = System.currentTimeMillis();
    }
}
