package myau.module.modules;

import myau.events.Render2DEvent;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.events.WindowClickEvent;
import myau.Myau;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemScore;
import myau.util.ItemUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldSettings.GameType;
import org.apache.commons.lang3.RandomUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

public class InvManager extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int actionDelay = 0;
    private int oDelay = 0;
    private boolean inventoryOpen = false;
    private final TimerUtil autoArmorTime = new TimerUtil();
    /** Ticks left in which armour taken from a chest may be put on without opening the inventory. */
    private int pendingEquipTicks = 0;
    // What the user normally sees: how fast, and three switches. Everything else is decided
    // here unless "advanced" is on, in which case the detailed settings below apply instead.
    public final ModeProperty speed = new ModeProperty("speed", 1, new String[]{"SLOW", "NORMAL", "FAST", "INSTANT"}, () -> !this.advanced.getValue());
    public final BooleanProperty autoArmor = new BooleanProperty("auto-armor", true);
    public final BooleanProperty dropTrash = new BooleanProperty("drop-trash", false);
    public final BooleanProperty advanced = new BooleanProperty("advanced", false);
    public final IntProperty minDelay = new IntProperty("min-delay", 1, 0, 20, () -> this.advanced.getValue());
    public final IntProperty maxDelay = new IntProperty("max-delay", 2, 0, 20, () -> this.advanced.getValue());
    public final IntProperty openDelay = new IntProperty("open-delay", 1, 0, 20, () -> this.advanced.getValue());
    public final IntProperty autoArmorInterval = new IntProperty("auto-armor-interval", 0, 0, 100, () -> this.advanced.getValue() && this.autoArmor.getValue());
    public final BooleanProperty equipAfterSteal = new BooleanProperty("equip-after-steal", true, () -> this.advanced.getValue() && this.autoArmor.getValue());
    public final BooleanProperty dropWorseGear = new BooleanProperty("drop-worse-gear", true, () -> this.advanced.getValue() && this.dropTrash.getValue());
    public final BooleanProperty dropJunk = new BooleanProperty("drop-junk", false, () -> this.advanced.getValue() && this.dropTrash.getValue());
    public final BooleanProperty checkDurability = new BooleanProperty("check-durability", true, () -> this.advanced.getValue());
    public final IntProperty swordSlot = new IntProperty("sword-slot", 1, 0, 9, () -> this.advanced.getValue());
    public final IntProperty pickaxeSlot = new IntProperty("pickaxe-slot", 3, 0, 9, () -> this.advanced.getValue());
    public final IntProperty shovelSlot = new IntProperty("shovel-slot", 4, 0, 9, () -> this.advanced.getValue());
    public final IntProperty axeSlot = new IntProperty("axe-slot", 5, 0, 9, () -> this.advanced.getValue());
    public final IntProperty blocksSlot = new IntProperty("blocks-slot", 2, 0, 9, () -> this.advanced.getValue());
    public final IntProperty blocks = new IntProperty("blocks", 128, 64, 2304, () -> this.advanced.getValue());
    public final IntProperty projectileSlot = new IntProperty("projectile-slot", 7, 0, 9, () -> this.advanced.getValue());
    public final IntProperty projectiles = new IntProperty("projectiles", 64, 16, 2304, () -> this.advanced.getValue());
    public final IntProperty goldAppleSlot = new IntProperty("gold-apple-slot", 9, 0, 9, () -> this.advanced.getValue());
    public final IntProperty arrow = new IntProperty("arrow", 256, 0, 2304, () -> this.advanced.getValue());
    public final IntProperty bowSlot = new IntProperty("bow-slot", 8, 0, 9, () -> this.advanced.getValue());

    // Click pacing of the three speeds, in ticks: SLOW, NORMAL, FAST.
    private static final int[] SPEED_MIN = {2, 1, 0}, SPEED_MAX = {4, 1, 1}, SPEED_OPEN = {3, 1, 0};

    /** Delay values for a "speed" setting; shared with ChestStealer so both feel the same. */
    public static int speedMin(int speed) { return SPEED_MIN[Math.max(0, Math.min(2, speed))]; }
    public static int speedMax(int speed) { return SPEED_MAX[Math.max(0, Math.min(2, speed))]; }
    public static int speedOpen(int speed) { return SPEED_OPEN[Math.max(0, Math.min(2, speed))]; }

    // A detailed setting only counts while "advanced" is on; otherwise the built-in value does,
    // whatever is stored in the config from before.
    private int val(IntProperty property, int builtIn) {
        return this.advanced.getValue() ? property.getValue() : builtIn;
    }

    private boolean flag(BooleanProperty property, boolean builtIn) {
        return this.advanced.getValue() ? property.getValue() : builtIn;
    }

    /**
     * INSTANT speed (LeaderClient); pair it with Disabler prediction-inventory.
     * Not "everything in one tick": an armour action ends the tick, so armour goes one piece per tick and the
     * sorting / dropping behind it waits until the armour is done. Only consecutive non-armour actions are batched.
     */
    private boolean isInstant() {
        return !this.advanced.getValue() && this.speed.getValue() == 3;
    }

    private int delayMin() { return this.advanced.getValue() ? this.minDelay.getValue() : speedMin(this.speed.getValue()); }
    private int delayMax() { return this.advanced.getValue() ? this.maxDelay.getValue() : speedMax(this.speed.getValue()); }
    private int delayOpen() { return this.advanced.getValue() ? this.openDelay.getValue() : speedOpen(this.speed.getValue()); }

    /** How many blocks / projectiles / arrows are worth carrying; ChestStealer stops there too. */
    public int blockLimit() { return this.val(this.blocks, 128); }
    public int projectileLimit() { return this.val(this.projectiles, 64); }
    public int arrowLimit() { return this.val(this.arrow, 256); }

    private boolean isValidGameMode() {
        GameType gameType = mc.playerController.getCurrentGameType();
        return gameType == GameType.SURVIVAL || gameType == GameType.ADVENTURE;
    }

    private int convertSlotIndex(int slot) {
        if (slot >= 36) {
            return 8 - (slot - 36);
        } else {
            return slot <= 8 ? slot + 36 : slot;
        }
    }

    private void clickSlot(int windowId, int slotId, int mouseButtonClicked, int mode) {
        if (slotId >= 0 && slotId < mc.thePlayer.inventoryContainer.inventorySlots.size()) {
            this.islandMoved(mc.thePlayer.inventoryContainer.getSlot(slotId).getStack());
        }
        mc.playerController.windowClick(windowId, slotId, mouseButtonClicked, mode, mc.thePlayer);
    }

    private int getStackSize(int slot) {
        if (slot == -1) {
            return 0;
        } else {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            return stack != null ? stack.stackSize : 0;
        }
    }

    public InvManager() {
        super("InvManager", false);
    }

    /**
     * ChestStealer took an armour upgrade: put it on once, without the inventory screen.
     * This is the only thing InvManager does with the inventory closed.
     */
    public static void requestEquip() {
        if (Myau.moduleManager == null) return;
        InvManager manager = (InvManager) Myau.moduleManager.modules.get(InvManager.class);
        if (manager != null && manager.isEnabled() && manager.autoArmor.getValue() && manager.flag(manager.equipAfterSteal, true)) {
            manager.pendingEquipTicks = 60;
        }
    }

    /**
     * One step towards wearing the best armour.
     *
     * @return true when a click was made
     */
    private boolean tickArmor() {
        int windowId = mc.thePlayer.inventoryContainer.windowId;
        for (int i = 0; i < 4; i++) {
            int checked = ItemUtil.findArmorInventorySlot(i, true);
            int unchecked = ItemUtil.findArmorInventorySlot(i, false);
            if (checked == -1 && unchecked == -1) continue;
            int playerArmorSlot = 39 - i;
            if (checked == playerArmorSlot || unchecked == playerArmorSlot) continue;
            int from = checked != -1 ? checked : unchecked;
            if (mc.thePlayer.inventory.getStackInSlot(playerArmorSlot) == null) {
                this.clickSlot(windowId, this.convertSlotIndex(from), 0, 1);
                this.autoArmorTime.reset();
            } else if (mc.thePlayer.inventory.getFirstEmptyStack() != -1) {
                // Take the worse piece off; the better one goes on at the next step.
                this.clickSlot(windowId, this.convertSlotIndex(playerArmorSlot), 0, 1);
            } else if (from < 9) {
                // No free slot to take it off into: trade places with the hotbar instead.
                // Nothing is ever on the cursor, so closing the inventory mid-way loses nothing.
                this.clickSlot(windowId, this.convertSlotIndex(playerArmorSlot), from, 2);
                this.autoArmorTime.reset();
            } else {
                // Bring the better piece to the hotbar first; the trade happens at the next step.
                this.clickSlot(windowId, this.convertSlotIndex(from), 8, 2);
            }
            return true;
        }
        return false;
    }

    /** A new item must beat the one already in its hotbar slot by this much, so near-equal gear is not swapped back and forth. */
    private static final double GEAR_MARGIN = 1.10;

    /**
     * Keeps gear already sitting in its preferred hotbar slot unless the candidate is clearly
     * better. Without this, two similar swords trade places as their durability drops.
     */
    private int gearSlot(ItemScore.Kind kind, int preferred, int candidate) {
        if (candidate == -1 || preferred < 0 || preferred > 8 || candidate == preferred) return candidate;
        ItemStack current = mc.thePlayer.inventory.getStackInSlot(preferred);
        if (current == null || ItemScore.kind(current) != kind) return candidate;
        ItemStack better = mc.thePlayer.inventory.getStackInSlot(candidate);
        boolean wear = this.flag(this.checkDurability, true);
        return ItemScore.score(better, wear) > ItemScore.score(current, wear) * GEAR_MARGIN ? candidate : preferred;
    }

    /**
     * Blocks, projectiles and golden apples: if the preferred hotbar slot already holds that
     * kind it stays, instead of chasing the biggest stack every time one item is used up.
     */
    private int stackSlot(int preferred, ItemUtil.ItemType type) {
        if (preferred >= 0 && preferred <= 8) {
            ItemStack current = mc.thePlayer.inventory.getStackInSlot(preferred);
            if (current != null && type.contains(current)) return preferred;
        }
        return ItemUtil.findInventorySlot(preferred, type);
    }

    /** Whether InvManager is in charge of this kind of gear (its slot is not set to 0). */
    private boolean manages(ItemScore.Kind kind) {
        switch (kind) {
            case SWORD: return this.val(this.swordSlot, 1) != 0;
            case PICKAXE: return this.val(this.pickaxeSlot, 3) != 0;
            case SHOVEL: return this.val(this.shovelSlot, 4) != 0;
            case AXE: return this.val(this.axeSlot, 5) != 0;
            case BOW: return this.val(this.bowSlot, 8) != 0;
            default: return kind.armor() && this.autoArmor.getValue();
        }
    }

    private static final int ACTED_NONE = 0, ACTED_ARMOR = 1, ACTED_OTHER = 2;

    /** One inventory action (the old per-tick body). INSTANT calls it repeatedly in the same tick. */
    private int doOneAction() {
        ArrayList<Integer> equippedArmorSlots = new ArrayList<>(Arrays.asList(-1, -1, -1, -1));
        ArrayList<Integer> inventoryArmorSlots = new ArrayList<>(Arrays.asList(-1, -1, -1, -1));
        for (int i = 0; i < 4; i++) {
            equippedArmorSlots.set(i, ItemUtil.findArmorInventorySlot(i, true));
            inventoryArmorSlots.set(i, ItemUtil.findArmorInventorySlot(i, false));
        }
        int preferredSwordHotbarSlot = this.val(this.swordSlot, 1) - 1;
        int inventorySwordSlot = ItemUtil.findSwordInInventorySlot(preferredSwordHotbarSlot, this.flag(this.checkDurability, true));
        if (inventorySwordSlot == -1) inventorySwordSlot = ItemUtil.findSwordInInventorySlot(preferredSwordHotbarSlot, false);
        inventorySwordSlot = this.gearSlot(ItemScore.Kind.SWORD, preferredSwordHotbarSlot, inventorySwordSlot);
        int preferredPickaxeHotbarSlot = this.val(this.pickaxeSlot, 3) - 1;
        int inventoryPickaxeSlot = ItemUtil.findInventorySlot("pickaxe", preferredPickaxeHotbarSlot, this.flag(this.checkDurability, true));
        if (inventoryPickaxeSlot == -1) inventoryPickaxeSlot = ItemUtil.findInventorySlot("pickaxe", preferredPickaxeHotbarSlot, false);
        inventoryPickaxeSlot = this.gearSlot(ItemScore.Kind.PICKAXE, preferredPickaxeHotbarSlot, inventoryPickaxeSlot);
        int preferredShovelHotbarSlot = this.val(this.shovelSlot, 4) - 1;
        int inventoryShovelSlot = ItemUtil.findInventorySlot("shovel", preferredShovelHotbarSlot, this.flag(this.checkDurability, true));
        if (inventoryShovelSlot == -1) inventoryShovelSlot = ItemUtil.findInventorySlot("shovel", preferredShovelHotbarSlot, false);
        inventoryShovelSlot = this.gearSlot(ItemScore.Kind.SHOVEL, preferredShovelHotbarSlot, inventoryShovelSlot);
        int preferredAxeHotbarSlot = this.val(this.axeSlot, 5) - 1;
        int inventoryAxeSlot = ItemUtil.findInventorySlot("axe", preferredAxeHotbarSlot, this.flag(this.checkDurability, true));
        if (inventoryAxeSlot == -1) inventoryAxeSlot = ItemUtil.findInventorySlot("axe", preferredAxeHotbarSlot, false);
        inventoryAxeSlot = this.gearSlot(ItemScore.Kind.AXE, preferredAxeHotbarSlot, inventoryAxeSlot);
        int preferredBlocksHotbarSlot = this.val(this.blocksSlot, 2) - 1;
        int inventoryBlocksSlot = this.stackSlot(preferredBlocksHotbarSlot, ItemUtil.ItemType.Block);
        int preferredProjectileHotbarSlot = this.val(this.projectileSlot, 7) - 1;
        int inventoryProjectileSlot = this.stackSlot(preferredProjectileHotbarSlot, ItemUtil.ItemType.Projectile);
        if (inventoryProjectileSlot == -1) inventoryProjectileSlot = this.stackSlot(preferredProjectileHotbarSlot, ItemUtil.ItemType.FishRod);
        int preferredGoldAppleHotbarSlot = this.val(this.goldAppleSlot, 9) - 1;
        int inventoryGoldAppleSlot = this.stackSlot(preferredGoldAppleHotbarSlot, ItemUtil.ItemType.GoldApple);
        int preferredBowHotbarSlot = this.val(this.bowSlot, 8) - 1;
        int inventoryBowSlot = ItemUtil.findBowInventorySlot(preferredBowHotbarSlot, this.flag(this.checkDurability, true));
        if (inventoryBowSlot == -1) inventoryBowSlot = ItemUtil.findBowInventorySlot(preferredBowHotbarSlot, false);
        inventoryBowSlot = this.gearSlot(ItemScore.Kind.BOW, preferredBowHotbarSlot, inventoryBowSlot);
        if (this.autoArmor.getValue() && this.autoArmorTime.hasTimeElapsed(this.val(this.autoArmorInterval, 0) * 50L)) {
            if (this.tickArmor()) return ACTED_ARMOR;
        }
        LinkedHashSet<Integer> usedHotbarSlots = new LinkedHashSet<>();
        if (preferredSwordHotbarSlot >= 0 && preferredSwordHotbarSlot <= 8 && inventorySwordSlot != -1) {
            usedHotbarSlots.add(preferredSwordHotbarSlot);
            if (inventorySwordSlot != preferredSwordHotbarSlot) {
                this.clickSlot(mc.thePlayer.inventoryContainer.windowId, this.convertSlotIndex(inventorySwordSlot), preferredSwordHotbarSlot, 2);
                return ACTED_OTHER;
            }
        }
        if (preferredPickaxeHotbarSlot >= 0 && preferredPickaxeHotbarSlot <= 8 && !usedHotbarSlots.contains(preferredPickaxeHotbarSlot) && inventoryPickaxeSlot != -1) {
            usedHotbarSlots.add(preferredPickaxeHotbarSlot);
            if (inventoryPickaxeSlot != preferredPickaxeHotbarSlot) {
                this.clickSlot(mc.thePlayer.inventoryContainer.windowId, this.convertSlotIndex(inventoryPickaxeSlot), preferredPickaxeHotbarSlot, 2);
                return ACTED_OTHER;
            }
        }
        if (preferredShovelHotbarSlot >= 0 && preferredShovelHotbarSlot <= 8 && !usedHotbarSlots.contains(preferredShovelHotbarSlot) && inventoryShovelSlot != -1) {
            usedHotbarSlots.add(preferredShovelHotbarSlot);
            if (inventoryShovelSlot != preferredShovelHotbarSlot) {
                this.clickSlot(mc.thePlayer.inventoryContainer.windowId, this.convertSlotIndex(inventoryShovelSlot), preferredShovelHotbarSlot, 2);
                return ACTED_OTHER;
            }
        }
        if (preferredAxeHotbarSlot >= 0 && preferredAxeHotbarSlot <= 8 && !usedHotbarSlots.contains(preferredAxeHotbarSlot) && inventoryAxeSlot != -1) {
            usedHotbarSlots.add(preferredAxeHotbarSlot);
            if (inventoryAxeSlot != preferredAxeHotbarSlot) {
                this.clickSlot(mc.thePlayer.inventoryContainer.windowId, this.convertSlotIndex(inventoryAxeSlot), preferredAxeHotbarSlot, 2);
                return ACTED_OTHER;
            }
        }
        if (preferredBlocksHotbarSlot >= 0 && preferredBlocksHotbarSlot <= 8 && !usedHotbarSlots.contains(preferredBlocksHotbarSlot) && inventoryBlocksSlot != -1) {
            usedHotbarSlots.add(preferredBlocksHotbarSlot);
            if (inventoryBlocksSlot != preferredBlocksHotbarSlot) {
                this.clickSlot(mc.thePlayer.inventoryContainer.windowId, this.convertSlotIndex(inventoryBlocksSlot), preferredBlocksHotbarSlot, 2);
                return ACTED_OTHER;
            }
        }
        if (preferredProjectileHotbarSlot >= 0 && preferredProjectileHotbarSlot <= 8 && !usedHotbarSlots.contains(preferredProjectileHotbarSlot) && inventoryProjectileSlot != -1) {
            usedHotbarSlots.add(preferredProjectileHotbarSlot);
            if (inventoryProjectileSlot != preferredProjectileHotbarSlot) {
                this.clickSlot(mc.thePlayer.inventoryContainer.windowId, this.convertSlotIndex(inventoryProjectileSlot), preferredProjectileHotbarSlot, 2);
                return ACTED_OTHER;
            }
        }
        if (preferredGoldAppleHotbarSlot >= 0 && preferredGoldAppleHotbarSlot <= 8 && !usedHotbarSlots.contains(preferredGoldAppleHotbarSlot) && inventoryGoldAppleSlot != -1) {
            usedHotbarSlots.add(preferredGoldAppleHotbarSlot);
            if (inventoryGoldAppleSlot != preferredGoldAppleHotbarSlot) {
                this.clickSlot(mc.thePlayer.inventoryContainer.windowId, this.convertSlotIndex(inventoryGoldAppleSlot), preferredGoldAppleHotbarSlot, 2);
                return ACTED_OTHER;
            }
        }
        if (preferredBowHotbarSlot >= 0 && preferredBowHotbarSlot <= 8 && !usedHotbarSlots.contains(preferredBowHotbarSlot) && inventoryBowSlot != -1) {
            usedHotbarSlots.add(preferredBowHotbarSlot);
            if (inventoryBowSlot != preferredBowHotbarSlot) {
                this.clickSlot(mc.thePlayer.inventoryContainer.windowId, this.convertSlotIndex(inventoryBowSlot), preferredBowHotbarSlot, 2);
                return ACTED_OTHER;
            }
        }
        if (this.dropTrash.getValue()) {
            ChestStealer stealer = (ChestStealer) Myau.moduleManager.modules.get(ChestStealer.class);
            ItemStack projectileStack = inventoryProjectileSlot == -1 ? null : mc.thePlayer.inventory.getStackInSlot(inventoryProjectileSlot);
            boolean rodKept = projectileStack != null && ItemUtil.ItemType.FishRod.contains(projectileStack);
            // What is being kept so far; a stack is dropped once its kind is at the limit.
            int blockCount = this.getStackSize(inventoryBlocksSlot);
            int projectileCount = rodKept ? 0 : this.getStackSize(inventoryProjectileSlot);
            int arrowCount = 0;
            for (int i = 0; i < 36; i++) {
                if (equippedArmorSlots.contains(i)
                        || inventoryArmorSlots.contains(i)
                        || inventorySwordSlot == i
                        || inventoryPickaxeSlot == i
                        || inventoryShovelSlot == i
                        || inventoryAxeSlot == i
                        || inventoryBlocksSlot == i
                        || inventoryProjectileSlot == i
                        || inventoryGoldAppleSlot == i
                        || inventoryBowSlot == i) {
                    continue;
                }
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack == null) continue;
                ItemScore.Kind kind = ItemScore.kind(stack);
                boolean drop = false;
                switch (kind) {
                    case BLOCK:
                        if (blockCount >= this.blockLimit()) drop = true;
                        else blockCount += stack.stackSize;
                        break;
                    case PROJECTILE:
                        if (projectileCount >= this.projectileLimit()) drop = true;
                        else projectileCount += stack.stackSize;
                        break;
                    case ARROW:
                        if (arrowCount >= this.arrowLimit()) drop = true;
                        else arrowCount += stack.stackSize;
                        break;
                    case ROD:
                        if (rodKept) drop = this.flag(this.dropWorseGear, true);
                        else rodKept = true;
                        break;
                    case JUNK:
                        drop = this.flag(this.dropJunk, false);
                        break;
                    default:
                        // Not the best of its kind (the best ones were skipped above).
                        // Renamed gear is a special item on some servers; extra armour
                        // and swords ChestStealer was told to collect stay too.
                        int kept = kind == ItemScore.Kind.SWORD ? inventorySwordSlot
                                : kind == ItemScore.Kind.PICKAXE ? inventoryPickaxeSlot
                                : kind == ItemScore.Kind.SHOVEL ? inventoryShovelSlot
                                : kind == ItemScore.Kind.AXE ? inventoryAxeSlot
                                : kind == ItemScore.Kind.BOW ? inventoryBowSlot : -1;
                        // The kept one may be slightly worse (swap margin): never drop a better one.
                        boolean notWorse = kept != -1 && ItemScore.score(stack, this.flag(this.checkDurability, true))
                                >= ItemScore.score(mc.thePlayer.inventory.getStackInSlot(kept), this.flag(this.checkDurability, true));
                        drop = kind.gear() && this.flag(this.dropWorseGear, true) && this.manages(kind)
                                && !notWorse
                                && !stack.hasDisplayName()
                                && (stealer == null || (!stealer.isMoreArmor(stack) && !stealer.isMoreSword(stack)));
                        break;
                }
                if (drop) {
                    this.clickSlot(mc.thePlayer.inventoryContainer.windowId, this.convertSlotIndex(i), 1, 4);
                    return ACTED_OTHER;
                }
            }
        }
        return ACTED_NONE;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.PRE) {
            if (this.actionDelay > 0) {
                this.actionDelay--;
            }
            if (this.oDelay > 0) {
                this.oDelay--;
            }
            if (this.pendingEquipTicks > 0) this.pendingEquipTicks--;
            if (!(mc.currentScreen instanceof GuiInventory)) {
                this.inventoryOpen = false;
                if (this.pendingEquipTicks > 0 && this.actionDelay <= 0 && mc.currentScreen == null
                        && mc.thePlayer.openContainer == mc.thePlayer.inventoryContainer
                        && this.isEnabled() && this.isValidGameMode() && this.autoArmor.getValue()) {
                    if (!this.tickArmor()) this.pendingEquipTicks = 0;
                }
            } else if (!(((GuiInventory) mc.currentScreen).inventorySlots instanceof ContainerPlayer)) {
                this.inventoryOpen = false;
            } else {
                if (!this.inventoryOpen) {
                    this.inventoryOpen = true;
                    this.oDelay = this.delayOpen() + 1;
                    this.autoArmorTime.reset();
                }
                if (this.oDelay <= 0 && (this.isInstant() || this.actionDelay <= 0)) {
                    if (this.isEnabled() && this.isValidGameMode()) {
                        if (this.isInstant()) {
                            // LeaderClient "Instant": batch non-armour actions; an armour action stops the batch for
                            // this tick (one piece per tick, the rest continues on the following ticks)
                            for (int guard = 0; guard < 64; guard++) {
                                if (this.doOneAction() != ACTED_OTHER) {
                                    break;
                                }
                            }
                        } else {
                            this.doOneAction();
                        }
                    }
                }
            }
        }
    }

    @EventTarget
    public void onClick(WindowClickEvent event) {
        this.actionDelay = RandomUtils.nextInt(this.delayMin() + 1, this.delayMax() + 2);
    }

    @Override
    public void verifyValue(String mode) {
        switch (mode) {
            case "min-delay":
                if (this.minDelay.getValue() > this.maxDelay.getValue()) {
                    this.maxDelay.setValue(this.minDelay.getValue());
                }
                break;
            case "max-delay":
                if (this.minDelay.getValue() > this.maxDelay.getValue()) {
                    this.minDelay.setValue(this.maxDelay.getValue());
                }
        }
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
        myau.ui.glass.Island.live("inv", myau.ui.glass.Island.P_PROGRESS, "Sorting §f" + this.islandMoved + " §7item" + (this.islandMoved != 1 ? "s" : ""),
                since < 500L ? myau.ui.GlassTheme.accent() : myau.ui.GlassTheme.OK, -1, this.islandIcons.stacks(), this.islandIcons.serial());
    }

    private void islandMoved(net.minecraft.item.ItemStack stack) {
        if (stack == null) return;
        this.islandIcons.push(stack);
        this.islandMoved++;
        this.islandLastMove = System.currentTimeMillis();
    }
}
