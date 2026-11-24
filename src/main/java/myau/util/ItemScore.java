package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAppleGold;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;

/**
 * One way of sorting and valuing items, shared by ChestStealer and InvManager, so the chest
 * side and the inventory side of a comparison always agree on which item is better.
 * <p>
 * Scores are only comparable within one {@link Kind}.
 */
public final class ItemScore {
    private ItemScore() { }

    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Uses left below which an item starts losing value; gold tools (32 uses) never reach it. */
    private static final int COMFORTABLE_USES = 60;

    public enum Kind {
        SWORD, HELMET, CHESTPLATE, LEGGINGS, BOOTS, PICKAXE, SHOVEL, AXE, BOW,
        ROD, BLOCK, PROJECTILE, ARROW, GAPPLE, PEARL, SPECIAL, JUNK, OTHER;

        /** Kinds where only the single best one matters. */
        public boolean gear() {
            return this.ordinal() <= BOW.ordinal();
        }

        public boolean armor() {
            return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
        }
    }

    private static final Kind[] ARMOR = {Kind.HELMET, Kind.CHESTPLATE, Kind.LEGGINGS, Kind.BOOTS};

    /** @param armorType 0 helmet .. 3 boots, as in {@link ItemArmor#armorType} */
    public static Kind armor(int armorType) {
        return ARMOR[Math.max(0, Math.min(3, armorType))];
    }

    public static Kind kind(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return Kind.OTHER;
        Item item = stack.getItem();
        if (item instanceof ItemSword) return Kind.SWORD;
        if (item instanceof ItemArmor) return armor(((ItemArmor) item).armorType);
        if (item instanceof ItemPickaxe) return Kind.PICKAXE;
        if (item instanceof ItemSpade) return Kind.SHOVEL;
        if (item instanceof ItemAxe) return Kind.AXE;
        if (item instanceof ItemBow) return Kind.BOW;
        if (item instanceof ItemFishingRod) return Kind.ROD;
        if (item instanceof ItemAppleGold) return Kind.GAPPLE;
        if (item instanceof ItemEnderPearl) return Kind.PEARL;
        if (item == Items.arrow) return Kind.ARROW;
        if (ItemUtil.isProjectile(stack)) return Kind.PROJECTILE;
        if (ItemUtil.isBlock(stack)) return Kind.BLOCK;
        // Before "special": rotten flesh and the like are food, which counts as special.
        if (isJunk(stack)) return Kind.JUNK;
        if (!ItemUtil.isNotSpecialItem(stack)) return Kind.SPECIAL;
        return Kind.OTHER;
    }

    /**
     * Things nobody carries on purpose. Deliberately short, and anything enchanted or renamed
     * is never junk (servers hand out named sticks, bones and the like as real items).
     */
    private static boolean isJunk(ItemStack stack) {
        if (stack.isItemEnchanted() || stack.hasDisplayName()) return false;
        Item item = stack.getItem();
        return item == Items.wheat_seeds || item == Items.melon_seeds || item == Items.pumpkin_seeds
                || item == Items.rotten_flesh || item == Items.spider_eye || item == Items.poisonous_potato
                || item == Items.string || item == Items.feather || item == Items.bone || item == Items.flint
                || item == Items.stick || item == Items.bowl || item == Items.glass_bottle || item == Items.leather
                || item == Item.getItemFromBlock(Blocks.sapling) || item == Item.getItemFromBlock(Blocks.deadbush)
                || item == Item.getItemFromBlock(Blocks.red_flower) || item == Item.getItemFromBlock(Blocks.yellow_flower)
                || item == Item.getItemFromBlock(Blocks.tallgrass);
    }

    public static double score(ItemStack stack) {
        return score(stack, true);
    }

    /**
     * @param wear when true a nearly broken item is worth less (see {@link #wearFactor})
     * @return how good the item is among items of its {@link Kind}; 0 for no item
     */
    public static double score(ItemStack stack, boolean wear) {
        if (stack == null) return 0;
        double value;
        switch (kind(stack)) {
            case SWORD:
                value = ItemUtil.getAttackBonus(stack) + level(Enchantment.knockback, stack) * 0.1;
                break;
            case HELMET:
            case CHESTPLATE:
            case LEGGINGS:
            case BOOTS:
                value = ItemUtil.getArmorProtection(stack)
                        + (level(Enchantment.fireProtection, stack) + level(Enchantment.blastProtection, stack)) * 0.02
                        + level(Enchantment.unbreaking, stack) * 0.02;
                break;
            case PICKAXE:
                // What a pickaxe can mine matters before how fast it mines: gold is the fastest
                // material but cannot break obsidian, diamond can.
                value = ItemUtil.getToolEfficiency(stack) + ((ItemTool) stack.getItem()).getToolMaterial().getHarvestLevel() * 3.0;
                break;
            case SHOVEL:
            case AXE:
                value = ItemUtil.getToolEfficiency(stack);
                break;
            case BOW:
                value = ItemUtil.getBowAttackBonus(stack) + level(Enchantment.punch, stack) * 0.1;
                break;
            default:
                return stack.stackSize;
        }
        return wear ? value * wearFactor(stack) : value;
    }

    private static int level(Enchantment enchantment, ItemStack stack) {
        return stack.isItemEnchanted() ? EnchantmentHelper.getEnchantmentLevel(enchantment.effectId, stack) : 0;
    }

    /**
     * 1 for an item with plenty of uses left (or an unbreakable one), falling linearly below
     * {@value #COMFORTABLE_USES} uses. A diamond sword about to break loses to a fresh iron
     * one, and gold tools rank where their 32 uses put them, not at the top.
     */
    public static double wearFactor(ItemStack stack) {
        if (stack == null || !stack.isItemStackDamageable()) return 1;
        int left = stack.getMaxDamage() - stack.getItemDamage();
        left *= 1 + level(Enchantment.unbreaking, stack);
        if (left >= COMFORTABLE_USES) return 1;
        return Math.max(0.12, left / (double) COMFORTABLE_USES);
    }

    // ---------------------------------------------------------------- the player's inventory

    /**
     * The best item of a gear kind among slots {@code start, start+1, ...} (wrapping, hotbar
     * and main inventory only). The first one found wins a tie, so an item already sitting in
     * the preferred hotbar slot is not swapped for an equal one.
     *
     * @return the slot, or -1 when there is none
     */
    public static int bestSlot(Kind kind, int start, boolean wear) {
        if (mc.thePlayer == null) return -1;
        int best = -1;
        double bestScore = 0;
        start = Math.max(0, start);
        for (int i = 0; i < 36; i++) {
            int slot = (start + i) % 36;
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || kind(stack) != kind) continue;
            double value = score(stack, wear);
            if (value > bestScore) {
                best = slot;
                bestScore = value;
            }
        }
        return best;
    }

    /**
     * The best armour piece of a kind, worn pieces (slots 36..39) included. The last one found
     * wins a tie, so the piece being worn is kept over an equal one in the inventory.
     */
    public static int bestArmorSlot(Kind kind, boolean wear) {
        if (mc.thePlayer == null) return -1;
        int best = -1;
        double bestScore = 0;
        for (int slot = 0; slot < 40; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || kind(stack) != kind) continue;
            double value = score(stack, wear);
            if (value >= bestScore) {
                best = slot;
                bestScore = value;
            }
        }
        return best;
    }

    /** The score of the best item of this kind the player has (worn armour included), 0 for none. */
    public static double bestOwnedScore(Kind kind) {
        if (mc.thePlayer == null) return 0;
        double best = 0;
        for (int slot = 0; slot < 40; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack != null && kind(stack) == kind) best = Math.max(best, score(stack, true));
        }
        return best;
    }

    /** How many items of this kind are in the hotbar and main inventory, counting stack sizes. */
    public static int count(Kind kind) {
        if (mc.thePlayer == null) return 0;
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack != null && kind(stack) == kind) total += stack.stackSize;
        }
        return total;
    }

    /** The hotbar slot (0..8) holding the worst item of this kind, or -1 when the hotbar has none. */
    public static int worstHotbarSlot(Kind kind) {
        if (mc.thePlayer == null) return -1;
        int worst = -1;
        double worstScore = Double.MAX_VALUE;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || kind(stack) != kind) continue;
            double value = score(stack, true);
            if (value < worstScore) {
                worst = slot;
                worstScore = value;
            }
        }
        return worst;
    }

    /**
     * Whether at least part of the stack fits: a free slot, or an unfinished stack of the same
     * item. "No free slot" alone is not "full": blocks still merge into a half stack.
     */
    public static boolean canFit(ItemStack stack) {
        if (stack == null || mc.thePlayer == null) return false;
        if (mc.thePlayer.inventory.getFirstEmptyStack() != -1) return true;
        if (!stack.isStackable()) return false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack own = mc.thePlayer.inventory.getStackInSlot(slot);
            if (own == null || own.stackSize >= own.getMaxStackSize()) continue;
            if (own.getItem() == stack.getItem() && (!own.getHasSubtypes() || own.getMetadata() == stack.getMetadata())
                    && ItemStack.areItemStackTagsEqual(own, stack)) {
                return true;
            }
        }
        return false;
    }
}
