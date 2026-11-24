package myau.module.modules;

import myau.events.Render2DEvent;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import myau.util.TickScheduler;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

/** Eats mushroom stew from the hotbar on low health, gets rid of the bowl and restocks the hotbar. */
public class AutoSoup extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final FloatProperty health = new FloatProperty("health", 15.0F, 0.0F, 20.0F);
    public final IntProperty delay = new IntProperty("delay", 150, 0, 500);
    public final BooleanProperty openInventory = new BooleanProperty("open-inv", true);
    public final IntProperty startDelay = new IntProperty("start-delay", 100, 0, 1000, this.openInventory::getValue);
    public final BooleanProperty autoClose = new BooleanProperty("auto-close", false, this.openInventory::getValue);
    public final BooleanProperty autoCloseNoSoup = new BooleanProperty("auto-close-no-soup", true, () -> this.autoClose.getValue());
    public final IntProperty autoCloseDelay = new IntProperty(
            "close-delay", 500, 0, 1000, () -> this.openInventory.getValue() && this.autoClose.getValue()
    );
    public final BooleanProperty simulateInventory = new BooleanProperty("simulate-inventory", false, () -> !this.openInventory.getValue());
    public final ModeProperty bowl = new ModeProperty("bowl", 0, new String[]{"Drop", "Move", "Stay"});

    private final TimerUtil timer = new TimerUtil();
    private final TimerUtil startTimer = new TimerUtil();
    private final TimerUtil closeTimer = new TimerUtil();
    private boolean canCloseInventory = false;
    private boolean inventoryWasOpen = false;

    public AutoSoup() {
        super("AutoSoup", false);
    }

    /** Hotbar slot (0-8) holding {@code item}, or -1. */
    private int findInHotbar(Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    /** Main inventory slot (9-35) holding {@code item}, or -1. */
    private int findInInventory(Item item) {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasSpaceInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return true;
            }
        }
        return false;
    }

    private void syncSlot() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
    }

    /** Shift-clicks a container slot of the player inventory, opening / closing it server-side when simulating. */
    private void shiftClick(int containerSlot) {
        boolean simulate = this.simulateInventory.getValue() && !this.openInventory.getValue() && !(mc.currentScreen instanceof GuiInventory);
        if (simulate) {
            PacketUtil.sendPacket(new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT));
        }
        mc.playerController.windowClick(0, containerSlot, 0, 1, mc.thePlayer);
        if (simulate) {
            PacketUtil.sendPacket(new C0DPacketCloseWindow(0));
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.canCloseInventory = false;
        this.inventoryWasOpen = false;
    }

    @EventTarget(Priority.LOW)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        final EntityPlayerSP player = mc.thePlayer;
        boolean inventoryOpen = mc.currentScreen instanceof GuiInventory;
        if (inventoryOpen && !this.inventoryWasOpen) {
            // The start delay counts from the moment the inventory was opened.
            this.startTimer.reset();
        }
        this.inventoryWasOpen = inventoryOpen;

        if (!this.timer.hasTimeElapsed(this.delay.getValue())) {
            return;
        }

        final int soupInHotbar = this.findInHotbar(Items.mushroom_stew);
        if (player.getHealth() <= this.health.getValue() && soupInHotbar != -1) {
            final int previous = player.inventory.currentItem;
            player.inventory.currentItem = soupInHotbar;
            // Syncs the held slot (C09), sends the use packet and applies the use client-side.
            float hpBefore = player.getHealth();
            mc.playerController.sendUseItem(player, mc.theWorld, player.inventory.getStackInSlot(soupInHotbar));
            myau.ui.glass.Island.event("AutoSoup", "Soup §7@ " + String.format("%.1f", hpBefore) + "hp", myau.ui.GlassTheme.OK, 1.2f);
            final boolean drop = this.bowl.getValue() == 0;
            // Switching away in the same tick would break vanilla ordering: finish on the next tick.
            TickScheduler.nextTick(() -> {
                if (mc.thePlayer == null || mc.getNetHandler() == null) {
                    return;
                }
                int current = mc.thePlayer.inventory.currentItem;
                // Keep a slot the user picked in the meantime; otherwise go back to the old one.
                int restore = current != soupInHotbar ? current : previous;
                if (drop) {
                    mc.thePlayer.inventory.currentItem = soupInHotbar;
                    this.syncSlot();
                    PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.DROP_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
                }
                mc.thePlayer.inventory.currentItem = restore;
                this.syncSlot();
            });
            this.timer.reset();
            return;
        }

        int bowlInHotbar = this.findInHotbar(Items.bowl);
        if (this.bowl.getValue() == 1 && bowlInHotbar != -1) {
            if (this.openInventory.getValue() && !inventoryOpen) {
                return;
            }
            boolean bowlMovable = false;
            for (int i = 9; i < 36; i++) {
                ItemStack stack = player.inventory.getStackInSlot(i);
                if (stack == null || stack.getItem() == Items.bowl && stack.stackSize < 64) {
                    bowlMovable = true;
                    break;
                }
            }
            if (bowlMovable) {
                this.shiftClick(36 + bowlInHotbar);
            }
        }

        int soupInInventory = this.findInInventory(Items.mushroom_stew);
        if (soupInInventory != -1 && this.hasSpaceInHotbar()) {
            if (this.openInventory.getValue()) {
                if (!inventoryOpen || !this.startTimer.hasTimeElapsed(this.startDelay.getValue())) {
                    return;
                }
            }
            this.canCloseInventory = false;
            // Main inventory slots 9-35 have the same index in the player container.
            this.islandMoved(player.inventoryContainer.getSlot(soupInInventory).getStack());
            this.shiftClick(soupInInventory);
            this.timer.reset();
            this.closeTimer.reset();
        } else {
            this.canCloseInventory = true;
        }

        if (this.openInventory.getValue() && this.autoClose.getValue() && this.canCloseInventory
                && this.closeTimer.hasTimeElapsed(this.autoCloseDelay.getValue())) {
            if (!this.autoCloseNoSoup.getValue() && soupInInventory == -1) {
                return;
            }
            if (mc.currentScreen instanceof GuiInventory) {
                player.closeScreen();
            }
            this.closeTimer.reset();
            this.canCloseInventory = false;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.health.getValue())};
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
        myau.ui.glass.Island.live("autosoup", myau.ui.glass.Island.P_PROGRESS, "Restock §f" + this.islandMoved + " §7item" + (this.islandMoved != 1 ? "s" : ""),
                since < 500L ? myau.ui.GlassTheme.accent() : myau.ui.GlassTheme.OK, -1, this.islandIcons.stacks(), this.islandIcons.serial());
    }

    private void islandMoved(net.minecraft.item.ItemStack stack) {
        if (stack == null) return;
        this.islandIcons.push(stack);
        this.islandMoved++;
        this.islandLastMove = System.currentTimeMillis();
    }
}
