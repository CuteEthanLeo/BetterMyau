package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.Render2DEvent;
import myau.events.UpdateEvent;
import myau.events.WindowClickEvent;
import myau.mixin.IAccessorItemSword;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.ui.GlassTheme;
import myau.ui.glass.Island;
import myau.util.ItemScore;
import myau.util.ItemScore.Kind;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockEnderChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.IInteractionObject;
import net.minecraft.world.WorldSettings.GameType;
import org.apache.commons.lang3.RandomUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * Takes what is worth taking out of chests, best things first. With "silent" the chest screen
 * never opens: the chest is looted in the background and the island shows what is being
 * taken, so the player keeps looking at the world.
 */
public class ChestStealer extends Module {
    // Normally just a speed and "silent"; the rest is decided here unless "advanced" is on.
    public final ModeProperty speed = new ModeProperty("speed", 1, new String[]{"SLOW", "NORMAL", "FAST", "INSTANT"}, () -> !this.advanced.getValue());
    public final BooleanProperty silent = new BooleanProperty("silent", true);
    public final BooleanProperty advanced = new BooleanProperty("advanced", false);
    public final IntProperty minDelay = new IntProperty("min-delay", 1, 0, 20, () -> this.advanced.getValue());
    public final IntProperty maxDelay = new IntProperty("max-delay", 2, 0, 20, () -> this.advanced.getValue());
    public final IntProperty openDelay = new IntProperty("open-delay", 1, 0, 20, () -> this.advanced.getValue());
    public final BooleanProperty island = new BooleanProperty("island", true, () -> this.advanced.getValue());
    // These two matter for chests that are not opened silently (custom titles get their screen).
    public final BooleanProperty autoClose = new BooleanProperty("auto-close", false, () -> this.advanced.getValue());
    public final BooleanProperty nameCheck = new BooleanProperty("name-check", true, () -> this.advanced.getValue());
    public final BooleanProperty skipTrash = new BooleanProperty("skip-trash", true, () -> this.advanced.getValue());
    public final BooleanProperty moreArmor = new BooleanProperty("more-armor", false, () -> this.advanced.getValue());
    public final BooleanProperty moreSword = new BooleanProperty("more-sword", false, () -> this.advanced.getValue());

    /** A detailed setting only counts while "advanced" is on; otherwise the built-in value does. */
    private boolean flag(BooleanProperty property, boolean builtIn) {
        return this.advanced.getValue() ? property.getValue() : builtIn;
    }

    /** INSTANT speed (LeaderClient). */
    private boolean isInstant() {
        return !this.advanced.getValue() && this.speed.getValue() == 3;
    }

    private int delayMin() { return this.advanced.getValue() ? this.minDelay.getValue() : InvManager.speedMin(this.speed.getValue()); }
    private int delayMax() { return this.advanced.getValue() ? this.maxDelay.getValue() : InvManager.speedMax(this.speed.getValue()); }
    private int delayOpen() { return this.advanced.getValue() ? this.openDelay.getValue() : InvManager.speedOpen(this.speed.getValue()); }

    /** A silent chest that makes no progress for this long is let go of. */
    private static final int IDLE_LIMIT_TICKS = 100;
    private static final int KINDS = Kind.values().length;

    private int clickDelay = 0;
    private int oDelay = 0;

    // The chest being looted
    private Container session;
    private boolean silentSession, plainChest, blocked, warnedFull, tookArmor;
    private int taken, remaining, seen, idleTicks, lastClicked = -1, sameClicks;
    private final Set<Integer> stuck = new HashSet<>();
    /** Set by the mixin when it swallowed the chest screen; the next session is that chest. */
    private static boolean silentOpening;

    // What the island shows
    private final ItemStack[] icons = new ItemStack[Island.MAX_ICONS];
    private int iconCount, iconSerial, shownTaken = -1, shownTotal = -1;
    private String islandText = "";
    private long lingerUntil;
    private float lingerProgress;

    // Scratch space for one scan of the chest
    private final int[] bestSlot = new int[KINDS];
    private final double[] bestScore = new double[KINDS];

    public ChestStealer() {
        super("ChestStealer", false);
    }

    private static ChestStealer instance() {
        return Myau.moduleManager == null ? null : (ChestStealer) Myau.moduleManager.modules.get(ChestStealer.class);
    }

    private boolean isValidGameMode() {
        GameType gameType = mc.playerController.getCurrentGameType();
        return gameType == GameType.SURVIVAL || gameType == GameType.ADVENTURE;
    }

    private static boolean isChestName(IInventory inventory) {
        String name = inventory.getName();
        if (name == null) return false;
        // Servers differ: most send the translation key (shown in the client's language),
        // Hypixel sends plain English text, so accept both no matter the client language.
        name = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(name).trim();
        return name.equals(I18n.format("container.chest")) || name.equals(I18n.format("container.chestDouble"))
                || name.equalsIgnoreCase("Chest") || name.equalsIgnoreCase("Large Chest")
                || name.equals("container.chest") || name.equals("container.chestDouble");
    }

    /**
     * Asked by the mixin on EntityPlayerSP.displayGUIChest: should this chest be opened
     * without its screen? Only plain chests qualify (by title, always checked here), so
     * server menus built from chest windows still open normally.
     */
    public static boolean wantsSilent(IInventory inventory) {
        ChestStealer module = instance();
        if (module == null || inventory == null || !module.isEnabled() || !module.silent.getValue()) return false;
        if (mc.thePlayer == null || mc.playerController == null || !module.isValidGameMode()) return false;
        String id = inventory instanceof IInteractionObject ? ((IInteractionObject) inventory).getGuiID() : "minecraft:container";
        if (!"minecraft:chest".equals(id) && !"minecraft:container".equals(id)) return false;
        if (!isPlainChest(inventory)) {
            System.out.println("[ChestStealer] not silent: id=" + id + ", title='" + inventory.getName() + "', size=" + inventory.getSizeInventory());
            return false;
        }
        silentOpening = true;
        return true;
    }

    /**
     * A chest the player opened themselves: a chest title, no title at all (Hypixel), or a
     * chest-sized window while the crosshair is on a chest block. Server menus have none of these.
     */
    private static boolean isPlainChest(IInventory inventory) {
        if (isChestName(inventory)) return true;
        String name = inventory.getName();
        if (name == null || net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(name).trim().isEmpty()) return true;
        int size = inventory.getSizeInventory();
        return (size == 27 || size == 54) && lookingAtChest() != null;
    }

    /** The chest / ender chest under the crosshair, or null. */
    private static BlockPos lookingAtChest() {
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || mc.theWorld == null) return null;
        BlockPos pos = hit.getBlockPos();
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block instanceof BlockChest || block instanceof BlockEnderChest ? pos : null;
    }

    /** Extra diamond / enchanted iron armour wanted by "more-armor"; InvManager must not bin it. */
    public boolean isMoreArmor(ItemStack itemStack) {
        if (itemStack == null) return false;
        if (!this.flag(this.moreArmor, false)) return false;
        if (!(itemStack.getItem() instanceof ItemArmor)) return false;
        ItemArmor.ArmorMaterial armorMaterial = ((ItemArmor) itemStack.getItem()).getArmorMaterial();
        if (armorMaterial == ItemArmor.ArmorMaterial.DIAMOND) return true;
        return armorMaterial == ItemArmor.ArmorMaterial.IRON && itemStack.isItemEnchanted();
    }

    /** Extra good swords wanted by "more-sword". */
    public boolean isMoreSword(ItemStack itemStack) {
        if (itemStack == null) return false;
        if (!this.flag(this.moreSword, false)) return false;
        if (!(itemStack.getItem() instanceof ItemSword)) return false;
        Item.ToolMaterial swordMaterial = ((IAccessorItemSword) itemStack.getItem()).getMaterial();
        if (swordMaterial == Item.ToolMaterial.EMERALD) return true;
        if (EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, itemStack) != 0) return true;
        return swordMaterial == Item.ToolMaterial.IRON && itemStack.isItemEnchanted();
    }

    // ---------------------------------------------------------------- session

    private void beginSession(Container container) {
        this.session = container;
        this.silentSession = silentOpening && !(mc.currentScreen instanceof GuiContainer);
        silentOpening = false;
        this.plainChest = this.silentSession || (container instanceof ContainerChest && isPlainChest(((ContainerChest) container).getLowerChestInventory()));
        this.blocked = this.warnedFull = this.tookArmor = false;
        this.taken = this.remaining = this.seen = this.idleTicks = this.sameClicks = 0;
        this.lastClicked = -1;
        this.stuck.clear();
        for (int i = 0; i < this.iconCount; i++) this.icons[i] = null;
        this.iconCount = 0;
        this.shownTaken = this.shownTotal = -1;
        this.lingerUntil = 0;
        this.oDelay = this.delayOpen() + 1;
        this.markOpenedChest();
    }

    /** Tell ChestESP which chest this is, so it can dim chests that were already opened. */
    private void markOpenedChest() {
        BlockPos pos = lookingAtChest();
        if (pos != null) ChestESP.markOpened(pos);
    }

    private void endSession() {
        if (this.session == null) return;
        if (this.taken > 0) {
            // Leave the finished count on the island for a moment.
            this.lingerUntil = System.currentTimeMillis() + 900L;
            this.lingerProgress = this.remaining > 0 ? this.taken / (float) (this.taken + this.remaining) : 1.0F;
            this.updateIslandText(this.remaining > 0 ? this.taken + this.remaining : this.taken);
        }
        if (this.tookArmor) InvManager.requestEquip();
        this.session = null;
        this.silentSession = false;
    }

    /**
     * Close a chest that has no screen. Not closeScreen(): that also closes whatever screen
     * the player has opened in the meantime (chat, a menu).
     */
    private void closeSilently(Container container) {
        if (mc.thePlayer == null) return;
        if (mc.getNetHandler() != null) mc.getNetHandler().addToSendQueue(new C0DPacketCloseWindow(container.windowId));
        mc.thePlayer.inventory.setItemStack(null);
        if (mc.thePlayer.openContainer == container) {
            container.onContainerClosed(mc.thePlayer);
            mc.thePlayer.openContainer = mc.thePlayer.inventoryContainer;
        }
    }

    private void close(Container container) {
        if (this.silentSession) {
            this.closeSilently(container);
        } else {
            mc.thePlayer.closeScreen();
        }
    }

    @Override
    public void onDisabled() {
        if (this.session != null && this.silentSession) this.closeSilently(this.session);
        this.endSession();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.session = null;
        this.silentSession = false;
        silentOpening = false;
        this.lingerUntil = 0;
    }

    // ---------------------------------------------------------------- tick

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) return;
        if (this.clickDelay > 0) this.clickDelay--;
        if (this.oDelay > 0) this.oDelay--;

        Container open = mc.thePlayer.openContainer;
        if (!(open instanceof ContainerChest)) {
            if (this.session != null) {
                // The player opened their inventory over a chest that has no screen: the
                // server still thinks the chest is open, so close it properly.
                if (this.silentSession && mc.currentScreen instanceof GuiContainer && mc.getNetHandler() != null) {
                    mc.getNetHandler().addToSendQueue(new C0DPacketCloseWindow(this.session.windowId));
                }
                this.endSession();
            }
            silentOpening = false;
            return;
        }
        if (open != this.session) {
            this.endSession();
            this.beginSession(open);
        }
        if (!this.isEnabled() || !this.isValidGameMode()) {
            if (this.silentSession) {
                this.closeSilently(open);
                this.endSession();
            }
            return;
        }
        IInventory inventory = ((ContainerChest) open).getLowerChestInventory();
        if ((this.flag(this.nameCheck, true) || this.silentSession) && !this.plainChest) return;

        this.idleTicks++;
        if (this.silentSession && this.idleTicks > IDLE_LIMIT_TICKS) {
            this.closeSilently(open);
            this.endSession();
            return;
        }
        if (this.oDelay > 0 || this.clickDelay > 0) return;

        int size = inventory.getSizeInventory();
        // INSTANT (LeaderClient): take every item that passes the filters in this tick; otherwise one click per tick
        boolean clicked = false;
        for (int guard = 0; guard < (this.isInstant() ? 64 : 1); guard++) {
            int action = this.choose(open, size);
            if (action == NOTHING) {
                break;
            }
            int slot = action & 0xff, hotbar = (action >> 8) - 1;
            ItemStack stack = open.getSlot(slot).getStack();
            // The click is predicted locally, so the same slot coming up again means the
            // server put the item back: it refuses, and asking again will not help.
            boolean repeat = slot == this.lastClicked;
            if (repeat) {
                if (++this.sameClicks >= 2) {
                    this.stuck.add(slot);
                    this.taken = Math.max(0, this.taken - 1);
                    return;
                }
            } else {
                this.lastClicked = slot;
                this.sameClicks = 0;
                this.taken++;
                if (stack != null) {
                    this.pushIcon(stack);
                    if (ItemScore.kind(stack).armor()) this.tookArmor = true;
                }
            }
            if (hotbar >= 0) {
                // No room: trade it for the worse one in the hotbar, which stays in the chest.
                mc.playerController.windowClick(open.windowId, slot, hotbar, 2, mc.thePlayer);
            } else {
                mc.playerController.windowClick(open.windowId, slot, 0, 1, mc.thePlayer);
            }
            this.blocked = false;
            this.idleTicks = 0;
            clicked = true;
        }
        if (clicked) {
            return;
        }

        // Nothing (more) to take. An empty chest right after opening may just not have
        // received its contents yet; give it a few ticks before deciding.
        if (this.seen == 0 && this.taken == 0 && this.idleTicks < 8) return;
        this.blocked = true;
        if (this.remaining > 0 && !this.warnedFull) {
            this.warnedFull = true;
            HUD.notify("Inventory full", this.remaining + " item" + (this.remaining == 1 ? "" : "s") + " left in the chest", false);
        }
        if (this.silentSession || this.flag(this.autoClose, false)) {
            this.close(open);
            this.endSession();
        }
    }

    private static final int NOTHING = -1;

    /**
     * Looks through the chest and picks the next click.
     *
     * @return {@link #NOTHING}, or the chest slot in the low byte and, when the item has to be
     * traded with a hotbar slot instead of shift-clicked, that hotbar slot + 1 in the next byte
     */
    private int choose(Container container, int size) {
        boolean all = !this.flag(this.skipTrash, true);
        java.util.Arrays.fill(this.bestSlot, -1);
        java.util.Arrays.fill(this.bestScore, 0);
        // The best of each gear kind in the chest; only that one can be an upgrade.
        for (int i = 0; i < size; i++) {
            ItemStack stack = this.stackAt(container, i);
            if (stack == null) continue;
            Kind kind = ItemScore.kind(stack);
            if (!kind.gear()) continue;
            double score = ItemScore.score(stack);
            if (score > this.bestScore[kind.ordinal()]) {
                this.bestScore[kind.ordinal()] = score;
                this.bestSlot[kind.ordinal()] = i;
            }
        }

        InvManager manager = (InvManager) Myau.moduleManager.modules.get(InvManager.class);
        int blocks = ItemScore.count(Kind.BLOCK), projectiles = ItemScore.count(Kind.PROJECTILE), arrows = ItemScore.count(Kind.ARROW);
        boolean rod = ItemScore.count(Kind.ROD) > 0;
        boolean bow = ItemScore.count(Kind.BOW) > 0;

        int choice = NOTHING, choiceRank = Integer.MAX_VALUE, wanted = 0, stacks = 0;
        for (int i = 0; i < size; i++) {
            ItemStack stack = this.stackAt(container, i);
            if (stack == null) continue;
            stacks++;
            Kind kind = ItemScore.kind(stack);
            int rank = -1;
            boolean upgrade = false;
            if (kind.gear()) {
                if (this.bestSlot[kind.ordinal()] == i && this.bestScore[kind.ordinal()] > ItemScore.bestOwnedScore(kind) + 1.0E-4) {
                    rank = kind.ordinal();
                    upgrade = true;
                    if (kind == Kind.BOW) bow = true;
                } else if (this.isMoreArmor(stack) || this.isMoreSword(stack)) {
                    rank = 40;
                }
            } else {
                switch (kind) {
                    case GAPPLE: rank = 10; break;
                    case PEARL: rank = 11; break;
                    case SPECIAL: rank = 12; break;
                    case BLOCK:
                        if (blocks < manager.blockLimit()) {
                            rank = 20;
                            blocks += stack.stackSize;
                        }
                        break;
                    case PROJECTILE:
                        if (projectiles < manager.projectileLimit()) {
                            rank = 21;
                            projectiles += stack.stackSize;
                        }
                        break;
                    case ROD:
                        // One rod is enough.
                        if (!rod) {
                            rank = 22;
                            rod = true;
                        }
                        break;
                    case ARROW:
                        // Arrows are only worth carrying with a bow (owned, or in this chest).
                        if (arrows < manager.arrowLimit() && (bow || this.bestSlot[Kind.BOW.ordinal()] != -1)) {
                            rank = 23;
                            arrows += stack.stackSize;
                        }
                        break;
                    default:
                        break;
                }
            }
            if (rank == -1 && all) rank = 50;
            if (rank == -1) continue;
            wanted++;
            if (this.stuck.contains(i)) continue;

            int action = i;
            if (!ItemScore.canFit(stack)) {
                // A real upgrade can still come along if the worse one is in the hotbar.
                int hotbar = upgrade ? ItemScore.worstHotbarSlot(kind) : -1;
                if (hotbar == -1) continue;
                action = i | (hotbar + 1) << 8;
            }
            // Rank first, then slot order, so it reads like someone emptying the chest.
            int order = rank * 256 + i;
            if (order < choiceRank) {
                choiceRank = order;
                choice = action;
            }
        }
        this.remaining = wanted;
        this.seen = stacks;
        return choice;
    }

    private ItemStack stackAt(Container container, int index) {
        Slot slot = container.getSlot(index);
        return slot != null && slot.getHasStack() ? slot.getStack() : null;
    }

    @EventTarget
    public void onWindowClick(WindowClickEvent event) {
        this.clickDelay = RandomUtils.nextInt(this.delayMin() + 1, this.delayMax() + 2);
    }

    // ---------------------------------------------------------------- island

    private void pushIcon(ItemStack stack) {
        if (this.iconCount == this.icons.length) {
            System.arraycopy(this.icons, 1, this.icons, 0, this.icons.length - 1);
            this.iconCount--;
        }
        this.icons[this.iconCount++] = stack.copy();
        this.iconSerial++;
    }

    private void updateIslandText(int total) {
        if (this.taken == this.shownTaken && total == this.shownTotal) return;
        this.shownTaken = this.taken;
        this.shownTotal = total;
        this.islandText = "Chest §f" + this.taken + "§7/" + total;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.flag(this.island, true)) return;
        boolean looting = this.session != null && this.isEnabled() && (this.taken > 0 || (this.remaining > 0 && !this.blocked));
        if (looting) {
            int total = this.taken + this.remaining;
            this.updateIslandText(total);
            Island.live("chest", Island.P_PROGRESS, this.islandText, GlassTheme.accent(),
                    total > 0 ? this.taken / (float) total : 0.0F, this.icons, this.iconSerial);
        } else if (System.currentTimeMillis() < this.lingerUntil) {
            Island.live("chest", Island.P_PROGRESS, this.islandText, this.lingerProgress >= 1.0F ? GlassTheme.OK : GlassTheme.WARN,
                    this.lingerProgress, this.icons, this.iconSerial);
        }
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
}
