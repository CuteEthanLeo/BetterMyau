package myau.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The #v1 prompt format: a {@link Situation} rendered as the compact Chinese text block the local
 * model was trained on. It must stay byte-for-byte identical to tools/ai/render.py, which renders the
 * teacher's training prompts; tools/ai/golden/situations.jsonl pins both sides
 * (tools/tests/PromptFormatCheck.java and {@code python render.py --check}).
 * Flags are written only when true to keep prompts short. Change the format only together with
 * render.py, and bump {@link #VERSION} when old models can no longer read it.
 */
public final class PromptFormat {
    public static final String VERSION = "#v1";
    public static final String TARGET_QUESTION = "现在先打谁？";
    /** "先打：E3" / "先打: 无" on the first line of an answer. */
    private static final Pattern TARGET_LINE = Pattern.compile("^\\s*先打\\s*[：:]\\s*(E\\d+|无)");

    private PromptFormat() {
    }

    public static String render(Situation s) {
        if ("qa".equals(s.kind)) {
            return VERSION + " 问答\n问：" + s.question;
        }
        StringBuilder out = new StringBuilder(header(s));
        if (s.teams != null && !s.teams.isEmpty()) {
            out.append("\n各队");
            for (Situation.TeamState t : s.teams) {
                out.append(' ').append(t.team).append(t.state);
            }
        }
        out.append('\n').append(meLine(s.me));
        if (s.mates != null) {
            for (int i = 0; i < s.mates.size(); i++) {
                out.append('\n').append(mateLine(i + 1, s.mates.get(i)));
            }
        }
        if (s.enemies == null || s.enemies.isEmpty()) {
            out.append("\n附近没有敌人");
        } else {
            for (int i = 0; i < s.enemies.size(); i++) {
                out.append('\n').append(enemyLine(i + 1, s.enemies.get(i)));
            }
        }
        out.append("\n问：").append("target".equals(s.kind) ? TARGET_QUESTION : s.question);
        return out.toString();
    }

    public static final String REVIEW_QUESTION = "复盘这局。";
    /** Stat keys and labels in display order (== STATS in render.py); hits/swings go last as 命中. */
    private static final String[][] STATS = {
            {"kills", "击杀"}, {"final_kills", "最终击杀"}, {"deaths", "死亡"}, {"beds_broken", "拆床"},
            {"dmg_dealt", "造成伤害"}, {"dmg_taken", "受到伤害"}, {"gapples", "金苹果"}, {"pearls", "珍珠"},
            {"void_falls", "掉虚空"},
    };

    /** A match record as the review prompt. */
    public static String renderReview(MatchRecord r) {
        StringBuilder b = new StringBuilder(VERSION).append(" 复盘 模式=").append(r.mode == null ? "未知" : r.mode);
        if (notEmpty(r.scale)) b.append(' ').append(r.scale);
        if (notEmpty(r.myTeam)) b.append(" 我方=").append(r.myTeam);
        b.append(" 结果=").append(r.result == null ? "未知" : r.result);
        if (notEmpty(r.duration)) b.append(" 时长=").append(r.duration);
        StringBuilder stats = new StringBuilder();
        for (String[] stat : STATS) {
            Integer v = r.stats.get(stat[0]);
            if (v != null) stats.append(' ').append(stat[1]).append(v);
        }
        Integer swings = r.stats.get("swings");
        if (swings != null && swings != 0) {
            Integer hits = r.stats.get("hits");
            stats.append(" 命中").append(hits == null ? 0 : hits).append('/').append(swings);
        }
        if (stats.length() > 0) b.append("\n统计").append(stats);
        b.append("\n时间线");
        for (MatchRecord.Event e : r.events) b.append('\n').append(e.t).append(' ').append(e.e);
        b.append("\n问：").append(notEmpty(r.question) ? r.question : REVIEW_QUESTION);
        return b.toString();
    }

    /**
     * The target an answer picks: "E3", "无" (fight nobody right now) or null when the first line
     * is not a 先打 line.
     */
    public static String parseTarget(String reply) {
        if (reply == null) return null;
        Matcher m = TARGET_LINE.matcher(reply);
        return m.find() ? m.group(1) : null;
    }

    /** E-numbers present in a rendered prompt. */
    public static Set<String> enemyIds(String prompt) {
        Set<String> ids = new LinkedHashSet<>();
        for (String line : prompt.split("\n")) {
            int space = line.indexOf(' ');
            String head = space < 0 ? line : line.substring(0, space);
            if (head.length() > 1 && head.charAt(0) == 'E' && isDigits(head.substring(1))) ids.add(head);
        }
        return ids;
    }

    /** Distance as stored in a snapshot: one decimal, rounded half up (render.py only formats it). */
    public static double roundDist(double blocks) {
        return Math.round(blocks * 10.0) / 10.0;
    }

    // ---------------------------------------------------------------- lines

    private static String header(Situation s) {
        StringBuilder b = new StringBuilder(VERSION).append(" 模式=").append(s.mode == null ? "未知" : s.mode);
        if (notEmpty(s.scale)) b.append(' ').append(s.scale);
        if (notEmpty(s.time)) b.append(" 时间=").append(s.time);
        if (notEmpty(s.myTeam)) b.append(" 我方=").append(s.myTeam);
        if (s.myBed != null) b.append(" 床=").append(s.myBed ? "在" : "无");
        if (s.matesTotal != null && s.matesTotal != 0) {
            b.append(" 队友存活=").append(s.matesAlive == null ? 0 : s.matesAlive).append('/').append(s.matesTotal);
        }
        if (s.playersLeft != null) b.append(" 剩余玩家=").append(s.playersLeft);
        return b.toString();
    }

    private static String meLine(Situation.Me m) {
        StringBuilder b = new StringBuilder("我");
        hp(b, m.hp, m.max, m.absorption);
        gear(b, m.armor, m.prot, "手持", m.held, m.sharp);
        b.append(" 金苹果").append(m.gapple).append(" 珍珠").append(m.pearl).append(" 方块").append(m.blocks);
        List<String> effects = m.effects;
        if (effects != null && !effects.isEmpty()) b.append(" 药水=").append(String.join(",", effects));
        if (m.voidEdge) b.append(" 虚空边");
        return b.toString();
    }

    private static String enemyLine(int index, Situation.Enemy e) {
        StringBuilder b = new StringBuilder("E").append(index);
        if (notEmpty(e.team)) b.append(' ').append(e.team);
        if (e.bed != null) b.append(" 床=").append(e.bed ? "在" : "无");
        b.append(" 距").append(dist(e.dist));
        hp(b, e.hp, null, e.absorption);
        gear(b, e.armor, e.prot, "武器", e.weapon, e.sharp);
        if (e.dy != 0) b.append(" 高差").append(String.format(Locale.ROOT, "%+d", e.dy));
        if (e.hittingMe) b.append(" 在打我");
        if (e.facingMe) b.append(" 朝向我");
        if (e.bowDrawn) b.append(" 拉弓");
        if (e.voidEdge) b.append(" 虚空边");
        if (e.nearMyBed) b.append(" 近我方床");
        if (e.invis) b.append(" 隐身");
        return b.toString();
    }

    private static String mateLine(int index, Situation.Mate t) {
        StringBuilder b = new StringBuilder("T").append(index).append(" 距").append(dist(t.dist));
        hp(b, t.hp, null, t.absorption);
        b.append(" 甲=").append(notEmpty(t.armor) ? t.armor : "?");
        return b.toString();
    }

    private static void hp(StringBuilder b, Integer hp, Integer max, Integer absorption) {
        b.append(" 血").append(hp == null ? "?" : String.valueOf(hp));
        if (max != null) b.append('/').append(max);
        if (absorption != null && absorption != 0) b.append(" 吸收").append(absorption);
    }

    private static void gear(StringBuilder b, String armor, Integer prot, String itemKey, String item, Integer sharp) {
        b.append(" 甲=").append(notEmpty(armor) ? armor : "?");
        if (prot != null && prot != 0) b.append(" 保护").append(prot);
        b.append(' ').append(itemKey).append('=').append(notEmpty(item) ? item : "?");
        if (sharp != null && sharp != 0) b.append(" 锋利").append(sharp);
    }

    private static String dist(Double d) {
        return d == null ? "?" : String.format(Locale.ROOT, "%.1f", d);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
