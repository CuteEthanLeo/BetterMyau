package myau.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One finished (or running) game as {@link MatchRecorder} wrote it down, for the review prompt.
 * Same JSON shape as tools/ai/synth_matches.py: stats keys kills, final_kills, deaths, beds_broken,
 * dmg_dealt, dmg_taken, gapples, pearls, void_falls, hits, swings; events {t: "mm:ss", e: text}.
 */
public final class MatchRecord {
    public String kind = "review";
    public String mode;
    public String scale;
    @com.google.gson.annotations.SerializedName("my_team")
    public String myTeam;
    /** 胜利 / 失败 / 未知 */
    public String result = "未知";
    public String duration;
    public Map<String, Integer> stats = new LinkedHashMap<>();
    public List<Event> events = new ArrayList<>();
    public String question;

    public static final class Event {
        public String t;
        public String e;

        public Event() {
        }

        public Event(String t, String e) {
            this.t = t;
            this.e = e;
        }
    }
}
