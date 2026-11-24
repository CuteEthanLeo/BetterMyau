package myau.ai;

import myau.Myau;
import myau.module.modules.BedNuker;
import myau.util.ItemUtil;
import myau.util.TargetTracker;
import myau.util.TargetUtil;
import myau.util.TeamUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link Situation} from the live world. Only things the client can really observe go in;
 * tools/ai/synth_situations.py generates training situations from the same fields, so anything added
 * here must be added there (and to PromptFormat / render.py) too.
 */
public final class SnapshotBuilder {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] HEALTH_WORDS = {"hp", "health", "❤", "lives", "血"};
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    private static final Map<Integer, String> POTIONS = new HashMap<>();

    static {
        POTIONS.put(Potion.moveSpeed.id, "速度");
        POTIONS.put(Potion.moveSlowdown.id, "缓慢");
        POTIONS.put(Potion.digSpeed.id, "急迫");
        POTIONS.put(Potion.damageBoost.id, "力量");
        POTIONS.put(Potion.jump.id, "跳跃提升");
        POTIONS.put(Potion.regeneration.id, "生命恢复");
        POTIONS.put(Potion.resistance.id, "抗性提升");
        POTIONS.put(Potion.fireResistance.id, "防火");
        POTIONS.put(Potion.invisibility.id, "隐身");
        POTIONS.put(Potion.weakness.id, "虚弱");
        POTIONS.put(Potion.poison.id, "中毒");
    }

    /** The situation plus the real players behind E1..En (same order), for highlighting an answer. */
    public static final class Snapshot {
        public final Situation situation;
        public final List<EntityPlayer> enemies;

        Snapshot(Situation situation, List<EntityPlayer> enemies) {
            this.situation = situation;
            this.enemies = enemies;
        }

        /** The player an answer's "先打：E3" points at, or null. */
        public EntityPlayer resolve(String id) {
            if (id == null || !id.startsWith("E")) return null;
            try {
                int index = Integer.parseInt(id.substring(1)) - 1;
                return index >= 0 && index < this.enemies.size() ? this.enemies.get(index) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private SnapshotBuilder() {
    }

    /**
     * @param kind     "target" or "ask"
     * @param question the player's question for "ask", ignored for "target"
     */
    public static Snapshot build(GameContext ctx, CombatTracker combat, double range, int maxEnemies,
                                 boolean teamCheck, String kind, String question) {
        EntityPlayerSP me = mc.thePlayer;
        Situation s = new Situation();
        s.kind = kind;
        s.question = "ask".equals(kind) ? question : null;
        String mode = ctx.mode();
        s.mode = mode;
        s.time = ctx.elapsed();

        Map<String, String> teamStates = Collections.emptyMap();
        String ownTeam = null;
        Set<BlockPos> ownBeds = ownBeds();
        if (GameContext.BEDWARS.equals(mode)) {
            teamStates = ctx.teamLines();
            ownTeam = ctx.ownTeam();
            s.myTeam = ownTeam;
            String ownState = ownTeam == null ? null : teamStates.get(ownTeam);
            if (ownState != null) {
                s.myBed = "✔".equals(ownState);
            } else if (!ownBeds.isEmpty()) {
                s.myBed = Boolean.TRUE;
            }
            for (Map.Entry<String, String> e : teamStates.entrySet()) {
                if (!e.getKey().equals(ownTeam)) s.teams.add(new Situation.TeamState(e.getKey(), e.getValue()));
            }
            Map<String, Integer> sizes = GameContext.tabTeamSizes();
            int largest = 0;
            for (int n : sizes.values()) largest = Math.max(largest, n);
            int teamCount = Math.max(teamStates.size(), sizes.size());
            s.scale = GameContext.bedwarsScale(teamCount, largest);
            if (largest > 1 && ownTeam != null) {
                s.matesTotal = largest - 1;
                Integer ours = sizes.get(ownTeam);
                s.matesAlive = ours == null ? 0 : Math.max(0, Math.min(largest - 1, ours - 1));
            }
        } else if (GameContext.SKYWARS.equals(mode)) {
            s.playersLeft = ctx.playersLeft();
        }

        // ---- me
        Situation.Me m = s.me;
        m.hp = Math.round(me.getHealth());
        m.max = Math.round(me.getMaxHealth());
        m.absorption = Math.round(me.getAbsorptionAmount());
        m.armor = armorTier(me);
        m.prot = protection(me);
        ItemStack held = me.getHeldItem();
        m.held = itemName(held);
        m.sharp = sharpness(held);
        m.gapple = count(me, Items.golden_apple);
        m.pearl = count(me, Items.ender_pearl);
        m.blocks = countBlocks(me);
        for (PotionEffect effect : me.getActivePotionEffects()) {
            String name = POTIONS.get(effect.getPotionID());
            if (name != null) m.effects.add(name + roman(effect.getAmplifier() + 1));
        }
        m.voidEdge = nearVoid(me);

        // ---- teammates (players on our team, nearest first)
        // only when the team size is known: in solo modes every player can share one tab-list team
        if (s.matesTotal != null) {
            List<EntityPlayer> mates = new ArrayList<>();
            for (EntityPlayer p : mc.theWorld.playerEntities) {
                if (p != me && !p.isDead && p.getHealth() > 0 && !p.isSpectator() && TeamUtil.isSameTeam(p)
                        && me.getDistanceToEntity(p) <= range) {
                    mates.add(p);
                }
            }
            mates.sort((a, b) -> Float.compare(me.getDistanceToEntity(a), me.getDistanceToEntity(b)));
            for (EntityPlayer p : mates.subList(0, Math.min(3, mates.size()))) {
                Situation.Mate t = new Situation.Mate();
                t.dist = PromptFormat.roundDist(me.getDistanceToEntity(p));
                t.hp = health(p);
                t.absorption = Math.round(p.getAbsorptionAmount());
                t.armor = armorTier(p);
                s.mates.add(t);
            }
        }

        // ---- enemies, nearest first (centre distance, like the "距" the model reads)
        List<EntityPlayer> enemies = new ArrayList<>();
        for (EntityLivingBase e : TargetUtil.getTargets(range, true, false, false, teamCheck, true)) {
            if (e instanceof EntityPlayer) enemies.add((EntityPlayer) e);
        }
        enemies.sort((a, b) -> Float.compare(me.getDistanceToEntity(a), me.getDistanceToEntity(b)));
        if (enemies.size() > maxEnemies) enemies = new ArrayList<>(enemies.subList(0, maxEnemies));
        boolean bedwars = GameContext.BEDWARS.equals(mode);
        for (EntityPlayer p : enemies) {
            Situation.Enemy e = new Situation.Enemy();
            if (bedwars) {
                e.team = GameContext.teamColor(p);
                String state = e.team == null ? null : teamStates.get(e.team);
                if (state != null) e.bed = "✔".equals(state);
            }
            e.dist = PromptFormat.roundDist(me.getDistanceToEntity(p));
            e.hp = health(p);
            e.absorption = Math.round(p.getAbsorptionAmount());
            e.invis = p.isInvisible();
            e.armor = e.invis ? "?" : armorTier(p);
            e.prot = e.invis ? 0 : protection(p);
            ItemStack weapon = p.getHeldItem();
            e.weapon = itemName(weapon);
            e.sharp = sharpness(weapon);
            e.dy = (int) Math.round(p.posY - me.posY);
            e.facingMe = TargetTracker.isFacing(p, me, 30.0F);
            e.hittingMe = combat.hitMeRecently(p);
            e.bowDrawn = weapon != null && weapon.getItem() instanceof ItemBow && p.isUsingItem();
            e.voidEdge = TargetTracker.wouldFallIntoVoid(p, me);
            e.nearMyBed = nearAny(p, ownBeds, 10.0);
            s.enemies.add(e);
        }
        return new Snapshot(s, enemies);
    }

    // ---------------------------------------------------------------- helpers

    /** Whole hit points; the below-name scoreboard wins when it shows health (some servers hide the real value). */
    static Integer health(EntityPlayer player) {
        Scoreboard scoreboard = player.getWorldScoreboard();
        ScoreObjective objective = scoreboard == null ? null : scoreboard.getObjectiveInDisplaySlot(2);
        if (objective != null && objective.getDisplayName() != null && scoreboard.entityHasObjective(player.getName(), objective)) {
            String title = objective.getDisplayName().toLowerCase(Locale.ROOT);
            for (String word : HEALTH_WORDS) {
                if (title.contains(word)) {
                    Score score = scoreboard.getValueFromObjective(player.getName(), objective);
                    if (score.getScorePoints() > 0) return score.getScorePoints();
                    break;
                }
            }
        }
        float hp = player.getHealth();
        if (Float.isNaN(hp) || hp <= 0.0F || hp > 1000.0F) return null;
        return Math.round(hp);
    }

    /** Material of the leggings (Bed Wars upgrades legs and boots), else boots, chestplate, helmet. */
    static String armorTier(EntityPlayer player) {
        for (int slot : new int[]{1, 0, 2, 3}) {
            ItemStack stack = player.getCurrentArmor(slot);
            if (stack != null && stack.getItem() instanceof ItemArmor) {
                switch (((ItemArmor) stack.getItem()).getArmorMaterial()) {
                    case LEATHER:
                        return "皮";
                    case CHAIN:
                        return "锁链";
                    case IRON:
                        return "铁";
                    case GOLD:
                        return "金";
                    case DIAMOND:
                        return "钻";
                    default:
                        return "?";
                }
            }
        }
        return "无";
    }

    static int protection(EntityPlayer player) {
        int best = 0;
        for (int slot = 0; slot < 4; slot++) {
            ItemStack stack = player.getCurrentArmor(slot);
            if (stack != null) best = Math.max(best, EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, stack));
        }
        return best;
    }

    static int sharpness(ItemStack stack) {
        return stack == null ? 0 : EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, stack);
    }

    static String itemName(ItemStack stack) {
        if (stack == null) return "空手";
        Item item = stack.getItem();
        if (item instanceof ItemSword) {
            switch (((ItemSword) item).getToolMaterialName()) {
                case "WOOD":
                    return "木剑";
                case "STONE":
                    return "石剑";
                case "IRON":
                    return "铁剑";
                case "EMERALD":
                    return "钻石剑";
                case "GOLD":
                    return "金剑";
                default:
                    return "剑";
            }
        }
        if (item instanceof ItemBow) return "弓";
        if (item instanceof ItemAxe) return "斧";
        if (item instanceof ItemPickaxe) return "镐";
        if (item instanceof ItemFishingRod) return "钓竿";
        if (item == Items.golden_apple) return "金苹果";
        if (item == Items.ender_pearl) return "珍珠";
        if (item == Items.fire_charge) return "火焰弹";
        if (item == Items.snowball || item == Items.egg) return "雪球";
        if (item == Items.potionitem) return "药水";
        if (item instanceof ItemBlock) {
            Block block = ((ItemBlock) item).getBlock();
            if (block == Blocks.tnt) return "TNT";
            if (block == Blocks.wool) return "羊毛";
            return "方块";
        }
        return "其他";
    }

    private static int count(EntityPlayer player, Item item) {
        int n = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() == item) n += stack.stackSize;
        }
        return n;
    }

    private static int countBlocks(EntityPlayer player) {
        int n = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (ItemUtil.isBlock(stack)) n += stack.stackSize;
        }
        return n;
    }

    private static String roman(int level) {
        return level >= 1 && level < ROMAN.length ? ROMAN[level] : String.valueOf(level);
    }

    /** Standing next to a drop with nothing below it, in any of 8 directions. */
    static boolean nearVoid(EntityPlayer player) {
        if (mc.theWorld == null) return false;
        int baseY = MathHelper.floor_double(player.posY) - 1;
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI / 4.0 * i;
            int x = MathHelper.floor_double(player.posX + Math.cos(angle) * 1.2);
            int z = MathHelper.floor_double(player.posZ + Math.sin(angle) * 1.2);
            boolean solid = false;
            for (int y = baseY; y >= 0 && !solid; y--) {
                solid = !mc.theWorld.isAirBlock(new BlockPos(x, y, z));
            }
            if (!solid) return true;
        }
        return false;
    }

    private static Set<BlockPos> ownBeds() {
        BedNuker bedNuker = Myau.moduleManager == null ? null : (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
        return bedNuker == null ? Collections.emptySet() : bedNuker.getOwnBeds();
    }

    private static boolean nearAny(Entity entity, Set<BlockPos> beds, double radius) {
        for (BlockPos bed : beds) {
            if (entity.getDistanceSq(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5) <= radius * radius) return true;
        }
        return false;
    }
}
