package myau.ai;

import myau.util.ServerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What kind of game we are in, read from the sidebar and the tab list: Bed Wars or SkyWars, the
 * team colours, each team's bed state, how long the game has run. Nothing here is cached across
 * worlds; {@link #reset()} runs on every world change.
 */
public final class GameContext {
    public static final String BEDWARS = "起床";
    public static final String SKYWARS = "空岛";
    public static final String UNKNOWN = "未知";

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Team colour names: English sidebar word / colour code -> the Chinese name the model reads. */
    private static final Map<String, String> COLOR_WORDS = new HashMap<>();
    private static final Map<Character, String> COLOR_CODES = new HashMap<>();

    static {
        String[][] words = {
                {"red", "红"}, {"blue", "蓝"}, {"green", "绿"}, {"yellow", "黄"}, {"aqua", "青"}, {"cyan", "青"},
                {"white", "白"}, {"pink", "粉"}, {"gray", "灰"}, {"grey", "灰"},
                {"红", "红"}, {"蓝", "蓝"}, {"绿", "绿"}, {"黄", "黄"}, {"青", "青"}, {"白", "白"}, {"粉", "粉"}, {"灰", "灰"},
        };
        for (String[] w : words) COLOR_WORDS.put(w[0], w[1]);
        COLOR_CODES.put('c', "红");
        COLOR_CODES.put('4', "红");
        COLOR_CODES.put('9', "蓝");
        COLOR_CODES.put('1', "蓝");
        COLOR_CODES.put('a', "绿");
        COLOR_CODES.put('2', "绿");
        COLOR_CODES.put('e', "黄");
        COLOR_CODES.put('6', "黄");
        COLOR_CODES.put('b', "青");
        COLOR_CODES.put('3', "青");
        COLOR_CODES.put('f', "白");
        COLOR_CODES.put('d', "粉");
        COLOR_CODES.put('5', "粉");
        COLOR_CODES.put('7', "灰");
        COLOR_CODES.put('8', "灰");
    }

    /**
     * A sidebar team line: optional one-letter badge, the colour word, a colon, then the state.
     * "R Red: ✔", "B Blue: 2", "G Green: ✘ YOU", "红队: ✔"
     */
    private static final Pattern TEAM_LINE = Pattern.compile(
            "^(?:[A-Z]\\s+)?([A-Za-z]+|[红蓝绿黄青白粉灰])队?\\s*[:：]\\s*([✔✓✘✗✖]|\\d+)(.*)$");
    private static final Pattern PLAYERS_LEFT = Pattern.compile("(?i)(?:players left|players|剩余玩家|存活玩家|剩余)\\s*[:：]\\s*(\\d+)");

    /** Words in the sidebar title / lines that name the mode; editable from AIAdvisor's settings. */
    private String[] bedwarsWords = {"BED WARS", "BEDWARS", "起床"};
    private String[] skywarsWords = {"SKYWARS", "SKY WARS", "空岛"};

    private long startMillis = 0L;

    public void reset() {
        this.startMillis = 0L;
    }

    public void setKeywords(String bedwars, String skywars) {
        this.bedwarsWords = split(bedwars);
        this.skywarsWords = split(skywars);
    }

    /** Call every tick while in a world; the first in-game tick starts the game clock. */
    public void tick(boolean inGame) {
        if (inGame && this.startMillis == 0L) this.startMillis = System.currentTimeMillis();
    }

    /** Game time as mm:ss, null before the game started. */
    public String elapsed() {
        if (this.startMillis == 0L) return null;
        long seconds = (System.currentTimeMillis() - this.startMillis) / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    public String mode() {
        String title = stripped(sidebarTitle()).toUpperCase(Locale.ROOT);
        if (containsAny(title, this.bedwarsWords)) return BEDWARS;
        if (containsAny(title, this.skywarsWords)) return SKYWARS;
        List<String> lines = sidebarLines();
        for (String line : lines) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (containsAny(upper, this.bedwarsWords)) return BEDWARS;
            if (containsAny(upper, this.skywarsWords)) return SKYWARS;
        }
        // several "Red: ✔" style lines only exist in Bed Wars
        return teamLines().size() >= 2 ? BEDWARS : UNKNOWN;
    }

    /** Every team line of the sidebar, colour name -> state, own team included (marked by {@link #ownTeamFromSidebar()}). */
    public Map<String, String> teamLines() {
        Map<String, String> teams = new LinkedHashMap<>();
        for (String line : sidebarLines()) {
            Matcher m = TEAM_LINE.matcher(line);
            if (!m.find()) continue;
            String color = COLOR_WORDS.get(m.group(1).toLowerCase(Locale.ROOT));
            if (color == null) continue;
            teams.put(color, normalizeState(m.group(2)));
        }
        return teams;
    }

    /** The team the sidebar marks as "YOU" / "你", if any. */
    public String ownTeamFromSidebar() {
        for (String line : sidebarLines()) {
            Matcher m = TEAM_LINE.matcher(line);
            if (!m.find()) continue;
            String rest = m.group(3).toUpperCase(Locale.ROOT);
            if (rest.contains("YOU") || rest.contains("你")) {
                return COLOR_WORDS.get(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return null;
    }

    /** Own team colour: the sidebar's YOU marker, else the colour of our tab-list team prefix. */
    public String ownTeam() {
        String fromSidebar = ownTeamFromSidebar();
        return fromSidebar != null ? fromSidebar : (mc.thePlayer == null ? null : teamColor(mc.thePlayer));
    }

    /** Chinese colour name of a player's scoreboard team, null when it has none. */
    public static String teamColor(EntityPlayer player) {
        if (mc.getNetHandler() == null) return null;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
        ScorePlayerTeam team = info == null ? null : info.getPlayerTeam();
        if (team == null) return null;
        String format = FontRenderer.getFormatFromString(team.getColorPrefix());
        for (int i = format.length() - 1; i >= 1; i--) {
            if (format.charAt(i - 1) == '§') {
                String name = COLOR_CODES.get(Character.toLowerCase(format.charAt(i)));
                if (name != null) return name;
            }
        }
        return null;
    }

    /** Players per colour in the tab list (players with no team colour are left out). */
    public static Map<String, Integer> tabTeamSizes() {
        Map<String, Integer> sizes = new HashMap<>();
        if (mc.getNetHandler() == null) return sizes;
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            ScorePlayerTeam team = info.getPlayerTeam();
            if (team == null) continue;
            String format = FontRenderer.getFormatFromString(team.getColorPrefix());
            if (format.length() < 2) continue;
            String color = COLOR_CODES.get(Character.toLowerCase(format.charAt(format.length() - 1)));
            if (color != null) sizes.merge(color, 1, Integer::sum);
        }
        return sizes;
    }

    /** "单人", "双人", "3v3", "4v4" from the number of teams and the largest team; null when unclear. */
    public static String bedwarsScale(int teams, int largestTeam) {
        if (teams >= 6) return largestTeam <= 1 ? "单人" : largestTeam == 2 ? "双人" : null;
        if (teams >= 3 && teams <= 4) return largestTeam >= 4 ? "4v4" : largestTeam == 3 ? "3v3" : null;
        return null;
    }

    /** Scale of the current game ("4v4", "单人"...), null when unclear or not Bed Wars. */
    public String scale(String mode) {
        if (!BEDWARS.equals(mode)) return null;
        Map<String, Integer> sizes = tabTeamSizes();
        int largest = 0;
        for (int n : sizes.values()) largest = Math.max(largest, n);
        return bedwarsScale(Math.max(teamLines().size(), sizes.size()), largest);
    }

    /** SkyWars "Players left: 7" style line. */
    public Integer playersLeft() {
        for (String line : sidebarLines()) {
            Matcher m = PLAYERS_LEFT.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return null;
    }

    // ---------------------------------------------------------------- sidebar

    private static String sidebarTitle() {
        if (mc.theWorld == null) return "";
        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        ScoreObjective objective = scoreboard == null ? null : scoreboard.getObjectiveInDisplaySlot(1);
        return objective == null || objective.getDisplayName() == null ? "" : objective.getDisplayName();
    }

    private static List<String> sidebarLines() {
        List<String> lines = new ArrayList<>();
        for (String line : ServerUtil.getScoreboardLines()) {
            // Hypixel pads lines with emoji/colour tricks; strip codes and odd invisible characters
            String clean = stripped(line).replaceAll("[\\p{Cf}\\p{So}&&[^✔✓✘✗✖]]", "").trim();
            if (!clean.isEmpty()) lines.add(clean);
        }
        return lines;
    }

    private static String stripped(String text) {
        String clean = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return clean == null ? "" : clean;
    }

    private static String normalizeState(String raw) {
        switch (raw) {
            case "✔":
            case "✓":
                return "✔";
            case "✘":
            case "✗":
            case "✖":
                return "✘";
            default:
                return raw;
        }
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (!w.isEmpty() && text.contains(w)) return true;
        }
        return false;
    }

    private static String[] split(String csv) {
        List<String> words = new ArrayList<>();
        for (String w : csv.split(",")) {
            String t = w.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) words.add(t);
        }
        return words.toArray(new String[0]);
    }
}
