package myau.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import myau.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes one game down as a {@link MatchRecord} for the review prompt: fights, kills, deaths, beds,
 * healing, armour upgrades, the result. Players get E-numbers by first appearance and their names are
 * never stored. Event wording must stay the same as tools/ai/synth_matches.py (the student was
 * trained on that). Driven by AIAdvisor; everything here runs on the client thread.
 */
public final class MatchRecorder {
    public static final File MATCH_DIR = new File(Config.CONFIG_DIR, "AI/matches");
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_EVENTS = 34;
    private static final String[] ARMOR_RANK = {"无", "皮", "金", "锁链", "铁", "钻"};

    private MatchRecord record;
    private MatchRecord last;
    private long startMs;
    private final Map<String, String> ids = new HashMap<>();
    private final Map<Integer, Long> fightUntil = new HashMap<>();
    private final Map<Integer, Long> attackedAt = new HashMap<>();
    private final Map<Integer, Float> seenHealth = new HashMap<>();
    private float lastHealth = 20.0F;
    private float healthBeforeDeath = 20.0F;
    private boolean dead;
    private boolean lowShown;
    private String armorShown;
    private int gapples = -1, pearls = -1;
    private long crowdShownAt, inFightUntil;
    private int lastAttackedId = -1;
    private long lastAttackMs;

    public boolean recording() {
        return this.record != null;
    }

    /** The game being recorded, or the last finished one. */
    public MatchRecord latest() {
        return this.record != null ? this.record : this.last;
    }

    // ---------------------------------------------------------------- lifecycle

    public void start(String mode, String scale, String myTeam) {
        this.finish(null);
        this.record = new MatchRecord();
        this.record.mode = mode;
        this.record.scale = scale;
        this.record.myTeam = myTeam;
        this.startMs = System.currentTimeMillis();
        this.ids.clear();
        this.fightUntil.clear();
        this.attackedAt.clear();
        this.seenHealth.clear();
        this.dead = false;
        this.lowShown = false;
        this.armorShown = null;
        this.gapples = this.pearls = -1;
        this.lastHealth = mc.thePlayer == null ? 20.0F : mc.thePlayer.getHealth();
        for (String key : new String[]{"kills", "deaths"}) this.record.stats.put(key, 0);
        this.event("开局");
    }

    /** Ends the game: sets the result (null keeps "未知"), saves it, keeps it for {@code .ai review}. */
    public MatchRecord finish(String result) {
        MatchRecord r = this.record;
        if (r == null) return null;
        this.record = null;
        if (result != null) r.result = result;
        r.duration = clock(System.currentTimeMillis() - this.startMs);
        r.events.add(new MatchRecord.Event(r.duration, r.result));
        if (r.events.size() > 3) save(r);
        this.last = r;
        return r;
    }

    /** The record shortened the way synth_matches.py does, for the prompt. */
    public static MatchRecord forPrompt(MatchRecord full, String question) {
        MatchRecord r = new MatchRecord();
        r.mode = full.mode;
        r.scale = full.scale;
        r.myTeam = full.myTeam;
        r.result = full.result;
        r.duration = full.duration;
        r.stats.putAll(full.stats);
        r.question = question;
        List<MatchRecord.Event> events = full.events;
        if (events.size() > MAX_EVENTS) {
            int cut = events.size() - 30;
            while (cut < events.size() && !startsAny(events.get(cut).e, "交战", "被 ", "护甲", "我方床", "拆了")) cut++;
            List<MatchRecord.Event> shorter = new ArrayList<>(events.subList(0, 2));
            if (cut < events.size()) shorter.add(new MatchRecord.Event(events.get(cut).t, "（中间省略）"));
            shorter.addAll(events.subList(Math.min(cut, events.size()), events.size()));
            events = shorter;
        }
        r.events.addAll(events);
        return r;
    }

    // ---------------------------------------------------------------- inputs

    /** Every client tick while a game is recorded. */
    public void tick(CombatTracker combat) {
        EntityPlayerSP me = mc.thePlayer;
        if (this.record == null || me == null || mc.theWorld == null) return;
        long now = System.currentTimeMillis();
        float health = me.getHealth();

        // death and respawn
        if (!this.dead && (health <= 0.0F || me.isDead)) {
            this.dead = true;
            this.add("deaths", 1);
            EntityPlayer killer = playerById(combat.lastAttacker(6000L));
            if (me.posY < 0.0) {
                this.add("void_falls", 1);
                this.event(killer == null ? "掉虚空死亡" : "掉虚空死亡（被 " + this.id(killer) + " 击退）");
            } else if (killer != null) {
                this.event("被 " + this.id(killer) + " 击杀（" + SnapshotBuilder.armorTier(killer) + "甲 "
                        + SnapshotBuilder.itemName(killer.getHeldItem()) + "），死前血量" + Math.max(1, Math.round(this.healthBeforeDeath)));
            } else {
                this.event("死亡");
            }
            this.inFightUntil = 0L;
        } else if (this.dead && health > 0.0F && !me.isDead) {
            this.dead = false;
            this.lastHealth = health;
            this.lowShown = false;
            this.event("复活");
        }
        if (this.dead) return;

        if (health < this.lastHealth) this.add("dmg_taken", Math.round(this.lastHealth - health));
        if (health > 0.0F) this.healthBeforeDeath = health;
        if (health <= 6.0F && !this.lowShown) {
            this.lowShown = true;
            this.event("残血 " + Math.max(1, Math.round(health)));
        } else if (health > 10.0F) {
            this.lowShown = false;
        }
        this.lastHealth = health;

        // armour upgrades
        String armor = SnapshotBuilder.armorTier(me);
        if (this.armorShown == null) {
            this.armorShown = armor;
        } else if (rank(armor) > rank(this.armorShown)) {
            this.armorShown = armor;
            this.event("护甲升级 " + armor);
        }

        // golden apples eaten, pearls thrown (counts going down)
        int g = count(me, Items.golden_apple), p = count(me, Items.ender_pearl);
        if (this.gapples >= 0 && g < this.gapples) {
            this.add("gapples", this.gapples - g);
            this.event("吃金苹果（血" + Math.max(1, Math.round(health)) + "）");
        }
        if (this.pearls >= 0 && p < this.pearls) {
            this.add("pearls", this.pearls - p);
            this.event("扔珍珠");
        }
        this.gapples = g;
        this.pearls = p;

        // fights, crowds, kills, damage dealt
        int close = 0;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityOtherPlayerMP)) continue;
            EntityOtherPlayerMP other = (EntityOtherPlayerMP) entity;
            if (myau.util.TeamUtil.isSameTeam(other) || myau.util.TeamUtil.isBot(other)) continue;
            int eid = other.getEntityId();
            double dist = me.getDistanceToEntity(other);
            if (dist <= 6.0 && other.getHealth() > 0) close++;
            boolean engaged = dist <= 4.5 && (combat.hitMeRecently(other) || recentlyAttacked(eid, now, 2000L));
            if (engaged) {
                Long until = this.fightUntil.get(eid);
                if (until == null || now > until) {
                    this.event("交战 " + this.id(other) + "（" + SnapshotBuilder.armorTier(other) + "甲 "
                            + SnapshotBuilder.itemName(other.getHeldItem()) + "），我血" + Math.round(health));
                }
                this.fightUntil.put(eid, now + 15000L);
                this.inFightUntil = now + 6000L;
            }
            Float before = this.seenHealth.get(eid);
            float hp = other.getHealth();
            if (before != null && hp < before && recentlyAttacked(eid, now, 1000L)) this.add("dmg_dealt", Math.round(before - hp));
            this.seenHealth.put(eid, hp);
            if ((hp <= 0.0F || other.deathTime > 0) && before != null && before > 0.0F && recentlyAttacked(eid, now, 10000L)) {
                this.add("kills", 1);
                this.event("击杀 " + this.id(other));
                this.attackedAt.remove(eid);
            }
        }
        if (close >= 2 && now - this.crowdShownAt > 20000L) {
            this.crowdShownAt = now;
            this.event("被 " + close + " 人围攻");
        }
        if (this.inFightUntil != 0L && now > this.inFightUntil) {
            this.inFightUntil = 0L;
            this.event("脱离交战，血" + Math.max(1, Math.round(health)));
        }
    }

    /** We swung at an entity (AttackEvent). */
    public void onAttack(Entity target) {
        if (this.record == null || !(target instanceof EntityPlayer)) return;
        this.add("swings", 1);
        this.lastAttackedId = target.getEntityId();
        this.lastAttackMs = System.currentTimeMillis();
        this.attackedAt.put(target.getEntityId(), this.lastAttackMs);
    }

    /** The server says an entity got hurt (S19 opcode 2): a hit if it is what we just swung at. */
    public void onHurt(int entityId) {
        if (this.record == null) return;
        if (entityId == this.lastAttackedId && System.currentTimeMillis() - this.lastAttackMs < 700L) this.add("hits", 1);
    }

    /**
     * A chat line or a title from the server (call on the client thread). Only titles decide the
     * result: chat says "失败" for all sorts of things (a purchase that failed, ...).
     */
    public void onServerText(String raw, boolean title, String myName, String ownTeam) {
        if (this.record == null || raw == null) return;
        String text = EnumChatFormatting.getTextWithoutFormattingCodes(raw);
        if (text == null) return;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean bedLine = (lower.contains("bed") && (lower.contains("destroy") || lower.contains("broken")))
                || (text.contains("床") && (text.contains("破坏") || text.contains("摧毁") || text.contains("拆")));
        if (bedLine) {
            if (lower.contains("your bed") || text.contains("你的床") || text.contains("你们的床")) {
                EntityPlayer by = playerNamedIn(text, myName);
                this.event("我方床被 " + (by == null ? "敌人" : this.id(by)) + " 拆了");
            } else if (myName != null && text.contains(myName)) {
                this.add("beds_broken", 1);
                String color = colorIn(text, ownTeam);
                this.event("拆了 " + (color == null ? "敌方" : color) + " 队的床");
            }
            return;
        }
        if ((lower.contains("final kill") || text.contains("最终击杀")) && myName != null && text.contains(myName)) {
            // "<victim> was killed by <killer>. FINAL KILL!": our name first means we are out
            if (!text.trim().startsWith(myName)) this.upgradeLastKill();
        }
        if (!title) return;
        if (lower.contains("victory") || text.contains("胜利") || text.contains("你赢了")) {
            this.finish("胜利");
        } else if (lower.contains("game over") || text.contains("游戏结束") || text.contains("你输了") || text.contains("失败")) {
            this.finish("失败");
        }
    }

    /** SkyWars has no respawn: our death ends our game. */
    public void endIfFinalDeath() {
        if (this.record != null && this.dead && GameContext.SKYWARS.equals(this.record.mode)) this.finish("失败");
    }

    // ---------------------------------------------------------------- helpers

    private void upgradeLastKill() {
        List<MatchRecord.Event> events = this.record.events;
        for (int i = events.size() - 1; i >= Math.max(0, events.size() - 4); i--) {
            MatchRecord.Event e = events.get(i);
            if (e.e.startsWith("击杀 ")) {
                e.e = "最终" + e.e;
                this.add("final_kills", 1);
                return;
            }
        }
    }

    private String id(EntityPlayer player) {
        return this.ids.computeIfAbsent(player.getName(), k -> "E" + (this.ids.size() + 1));
    }

    private void event(String text) {
        if (this.record == null) return;
        this.record.events.add(new MatchRecord.Event(clock(System.currentTimeMillis() - this.startMs), text));
    }

    private void add(String stat, int amount) {
        if (amount > 0) this.record.stats.merge(stat, amount, Integer::sum);
    }

    private boolean recentlyAttacked(int entityId, long now, long withinMs) {
        Long t = this.attackedAt.get(entityId);
        return t != null && now - t <= withinMs;
    }

    private static EntityPlayer playerById(int id) {
        if (id < 0 || mc.theWorld == null) return null;
        Entity e = mc.theWorld.getEntityByID(id);
        return e instanceof EntityPlayer ? (EntityPlayer) e : null;
    }

    private static EntityPlayer playerNamedIn(String text, String myName) {
        if (mc.theWorld == null) return null;
        for (EntityPlayer p : mc.theWorld.playerEntities) {
            if (!p.getName().equals(myName) && text.contains(p.getName())) return p;
        }
        return null;
    }

    /** First team colour word in a bed line that is not ours ("Blue Bed was destroyed by ..."). */
    private static String colorIn(String text, String ownTeam) {
        String[][] words = {{"red", "红"}, {"blue", "蓝"}, {"green", "绿"}, {"yellow", "黄"}, {"aqua", "青"},
                {"white", "白"}, {"pink", "粉"}, {"gray", "灰"}, {"grey", "灰"}};
        String lower = text.toLowerCase(Locale.ROOT);
        for (String[] w : words) {
            if ((lower.contains(w[0]) || text.contains(w[1])) && !w[1].equals(ownTeam)) return w[1];
        }
        return null;
    }

    private static int rank(String armor) {
        for (int i = 0; i < ARMOR_RANK.length; i++) if (ARMOR_RANK[i].equals(armor)) return i;
        return 0;
    }

    private static int count(EntityPlayer player, Item item) {
        int n = 0;
        for (ItemStack s : player.inventory.mainInventory) if (s != null && s.getItem() == item) n += s.stackSize;
        return n;
    }

    private static boolean startsAny(String text, String... prefixes) {
        for (String p : prefixes) if (text.startsWith(p)) return true;
        return false;
    }

    static String clock(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60L, s % 60L);
    }

    private static void save(MatchRecord r) {
        if (!MATCH_DIR.exists() && !MATCH_DIR.mkdirs()) return;
        File file = new File(MATCH_DIR, new SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".json");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write(GSON.toJson(r));
        } catch (Exception ignored) {
        }
    }
}
