package myau.util;

import myau.Myau;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;

import java.awt.*;
import java.util.List;
import java.util.stream.Collectors;

public class TeamUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static boolean isEntityLoaded(Entity entity) {
        if (entity == null) return false;
        return TeamUtil.mc.theWorld.loadedEntityList.contains(entity);
    }

    /** Loaded entities, farthest from the camera first. Sorts on every call. */
    public static List<Entity> getLoadedEntitiesSorted() {
        List<Entity> source = TeamUtil.mc.theWorld.loadedEntityList;
        int n = source.size();
        Entity[] entities = new Entity[n];
        double[] distances = new double[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            Entity entity = source.get(i);
            entities[i] = entity;
            distances[i] = mc.getRenderManager().getDistanceToCamera(entity.posX, entity.posY, entity.posZ);
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> {
            int byDistance = Double.compare(distances[b], distances[a]);
            return byDistance != 0 ? byDistance : Integer.compare(entities[a].getEntityId(), entities[b].getEntityId());
        });
        List<Entity> sorted = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) sorted.add(entities[order[i]]);
        return sorted;
    }

    private static long renderFrame, sortedFrame = -1;
    private static Object sortedWorld;
    private static List<Entity> sortedForRender = java.util.Collections.emptyList();

    /** Called once per rendered frame (GlassTheme.beginFrame). */
    public static void beginRenderFrame() {
        renderFrame++;
    }

    /** A number that changes every rendered frame, for per-frame caches. */
    public static long renderFrame() {
        return renderFrame;
    }

    // Whether a player is a bot, a friend or a target is asked for by NameTags, ESP, Tracers,
    // Radar and the vanilla name-tag hook, for every player, every frame; the bot test alone
    // walks the whole tab list. None of it changes more than once a game tick.
    private static final class Flags {
        int tick = Integer.MIN_VALUE, known;
        boolean bot, friend, target;
    }

    private static final java.util.concurrent.ConcurrentHashMap<EntityPlayer, Flags> flags = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int BOT = 1, FRIEND = 2, TARGET = 4;
    private static volatile Object flagsWorld;

    /** @return this tick's cache entry for the player, or null when nothing can be cached */
    private static Flags flags(EntityPlayer player) {
        EntityPlayer self = mc.thePlayer;
        if (self == null || player == null) return null;
        if (flagsWorld != mc.theWorld) {
            // New world: the old players (and through them the old world) must not be kept alive.
            flags.clear();
            flagsWorld = mc.theWorld;
        }
        Flags entry = flags.get(player);
        if (entry == null) {
            if (flags.size() > 512) flags.clear();
            entry = new Flags();
            flags.put(player, entry);
        }
        int tick = self.ticksExisted;
        if (entry.tick != tick) {
            entry.known = 0;
            entry.tick = tick;
        }
        return entry;
    }

    /**
     * {@link #getLoadedEntitiesSorted()} shared by every render handler of the current frame
     * (NameTags, ESP, Tracers, Radar, ItemESP, Indicators each used to sort the whole entity
     * list themselves). Read-only. Only for render code: ticks run before the frame starts.
     */
    public static List<Entity> getRenderEntitiesSorted() {
        if (sortedFrame != renderFrame || sortedWorld != mc.theWorld) {
            sortedForRender = java.util.Collections.unmodifiableList(getLoadedEntitiesSorted());
            sortedFrame = renderFrame;
            sortedWorld = mc.theWorld;
        }
        return sortedForRender;
    }

    public static float getHealthScore(EntityLivingBase entityLivingBase) {
        return entityLivingBase.getHealth() * (20.0f / (float) entityLivingBase.getTotalArmorValue());
    }

    private static final java.util.regex.Pattern TRAILING_CODE = java.util.regex.Pattern.compile("§\\S$");
    private static final java.util.regex.Pattern RESET_CODE = java.util.regex.Pattern.compile("(?i)§r");

    public static String stripName(Entity entity) {
        String text = TRAILING_CODE.matcher(entity.getDisplayName().getFormattedText()).replaceAll("");
        return RESET_CODE.matcher(text).replaceAll("§f").trim();
    }

    public static Color getTeamColor(EntityPlayer player, float alpha) {
        int colorCode = 0xFFFFFF;
        ScorePlayerTeam playerTeam = (ScorePlayerTeam) player.getTeam();
        if (playerTeam != null) {
            String colorPrefix = FontRenderer.getFormatFromString(playerTeam.getColorPrefix());
            if (colorPrefix.length() >= 2) {
                colorCode = TeamUtil.mc.fontRendererObj.getColorCode(colorPrefix.charAt(1));
            }
        }
        return new Color(colorCode & 0xFFFFFF | (int)(alpha * 255) << 24, true);
    }

    public static boolean isBot(EntityPlayer player) {
        Flags entry = flags(player);
        if (entry == null) return computeBot(player);
        if ((entry.known & BOT) == 0) {
            entry.bot = computeBot(player);
            entry.known |= BOT;
        }
        return entry.bot;
    }

    private static boolean computeBot(EntityPlayer player) {
        if (player == TeamUtil.mc.thePlayer) {
            return false;
        }
        // AntiBot module (off by default): its checks add to the built-in ones
        if (Myau.moduleManager != null) {
            myau.module.modules.AntiBot antiBot = (myau.module.modules.AntiBot) Myau.moduleManager.modules.get(myau.module.modules.AntiBot.class);
            if (antiBot != null && antiBot.isBot(player)) {
                return true;
            }
        }
        NetworkPlayerInfo playerInfo = mc.getNetHandler().getPlayerInfo(player.getName());
        if (playerInfo == null) {
            return true;
        }
        if (!ServerUtil.isHypixel()) return false;
        if (player.getName().startsWith("§k")) {
            return player.isInvisible();
        }
        if (playerInfo.getResponseTime() < 1) {
            return true;
        }
        ScorePlayerTeam playerTeam = playerInfo.getPlayerTeam();
        if (playerTeam == null) return false;
        if (!playerTeam.getTeamName().isEmpty()) return false;
        return playerTeam.getColorPrefix().equals("§c");
    }

    public static boolean isSameTeam(EntityPlayer player) {
        if (player == TeamUtil.mc.thePlayer) {
            return true;
        }
        // Teams module (off by default): extra team rules (armour colour, name colour ...)
        if (Myau.moduleManager != null) {
            myau.module.modules.Teams teams = (myau.module.modules.Teams) Myau.moduleManager.modules.get(myau.module.modules.Teams.class);
            if (teams != null && teams.isInYourTeam(player)) {
                return true;
            }
        }
        NetworkPlayerInfo selfInfo = mc.getNetHandler().getPlayerInfo(TeamUtil.mc.thePlayer.getUniqueID());
        if (selfInfo == null) {
            return false;
        }
        ScorePlayerTeam selfTeam = selfInfo.getPlayerTeam();
        if (selfTeam == null) {
            return false;
        }
        NetworkPlayerInfo targetInfo = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
        if (targetInfo == null) {
            return false;
        }
        ScorePlayerTeam targetTeam = targetInfo.getPlayerTeam();
        if (targetTeam == null) {
            return false;
        }
        return selfTeam.getColorPrefix().equals(targetTeam.getColorPrefix());
    }

    public static boolean hasTeamColor(EntityLivingBase entity) {
        if (entity == TeamUtil.mc.thePlayer) {
            return true;
        }
        NetworkPlayerInfo selfInfo = mc.getNetHandler().getPlayerInfo(TeamUtil.mc.thePlayer.getUniqueID());
        if (selfInfo == null) {
            return false;
        }
        ScorePlayerTeam selfTeam = selfInfo.getPlayerTeam();
        if (selfTeam == null) {
            return false;
        }
        if (selfTeam.getColorPrefix().length() < 2) {
            return false;
        }
        EntityLivingBase nearestArmorStand = TeamUtil.mc.theWorld.findNearestEntityWithinAABB(EntityArmorStand.class, entity.getEntityBoundingBox(), entity);
        if (nearestArmorStand != null) {
            return nearestArmorStand.getName().contains(selfTeam.getColorPrefix().substring(0, 2));
        }
        return false;
    }

    public static boolean isShop(EntityLivingBase entity) {
        if (entity == TeamUtil.mc.thePlayer) {
            return false;
        }
        EntityLivingBase armorStand = TeamUtil.mc.theWorld.findNearestEntityWithinAABB(EntityArmorStand.class, entity.getEntityBoundingBox(), entity);
        if (armorStand == null) return false;
        String displayName = armorStand.getName();
        if (displayName.contains("RIGHT CLICK")) return true;
        if (displayName.contains("ITEM SHOP")) return true;
        if (displayName.contains("UPGRADES")) return true;
        if (displayName.contains("BANKER")) return true;
        return displayName.contains("STREAK POWERS");
    }

    public static boolean isFriend(EntityPlayer player) {
        Flags entry = flags(player);
        if (entry == null) return Myau.friendManager.isFriend(player.getName());
        if ((entry.known & FRIEND) == 0) {
            entry.friend = Myau.friendManager.isFriend(player.getName());
            entry.known |= FRIEND;
        }
        return entry.friend;
    }

    public static boolean isTarget(EntityPlayer player) {
        Flags entry = flags(player);
        if (entry == null) return Myau.targetManager.isFriend(player.getName());
        if ((entry.known & TARGET) == 0) {
            entry.target = Myau.targetManager.isFriend(player.getName());
            entry.known |= TARGET;
        }
        return entry.target;
    }
}
