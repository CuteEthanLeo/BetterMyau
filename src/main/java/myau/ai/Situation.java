package myau.ai;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * One snapshot of the fight as the AI advisor sees it. Field names follow the JSON written by
 * {@code .ai export} and read by tools/ai (render.py, synth_situations.py), so the two sides share
 * one schema. Numbers are stored already rounded (whole hit points, distance to 0.1 block): rounding
 * happens once, at capture, and both renderers only format.
 * No Minecraft types here, so {@link PromptFormat} can be checked without starting the game.
 */
public final class Situation {
    /** "target" (who to hit first), "ask" (situation + free question) or "qa" (question only). */
    public String kind = "target";
    /** 起床 / 空岛 / 未知 */
    public String mode;
    /** 单人, 双人, 3v3, 4v4 */
    public String scale;
    /** Time since the game started, mm:ss. */
    public String time;
    @SerializedName("my_team")
    public String myTeam;
    @SerializedName("my_bed")
    public Boolean myBed;
    @SerializedName("mates_alive")
    public Integer matesAlive;
    @SerializedName("mates_total")
    public Integer matesTotal;
    @SerializedName("players_left")
    public Integer playersLeft;
    public List<TeamState> teams = new ArrayList<>();
    public Me me = new Me();
    public List<Mate> mates = new ArrayList<>();
    public List<Enemy> enemies = new ArrayList<>();
    public String question;

    public static final class TeamState {
        public String team;
        /** ✔ bed alive, a digit = bed gone with that many players left, ✘ eliminated. */
        public String state;

        public TeamState() {
        }

        public TeamState(String team, String state) {
            this.team = team;
            this.state = state;
        }
    }

    public static final class Me {
        public Integer hp;
        public Integer max = 20;
        @SerializedName("abs")
        public Integer absorption;
        public String armor;
        public Integer prot;
        public String held;
        public Integer sharp;
        public int gapple;
        public int pearl;
        public int blocks;
        public List<String> effects = new ArrayList<>();
        @SerializedName("void_edge")
        public boolean voidEdge;
    }

    public static final class Mate {
        public Double dist;
        public Integer hp;
        @SerializedName("abs")
        public Integer absorption;
        public String armor;
    }

    public static final class Enemy {
        public String team;
        public Boolean bed;
        public Double dist;
        public Integer hp;
        @SerializedName("abs")
        public Integer absorption;
        public String armor;
        public Integer prot;
        public String weapon;
        public Integer sharp;
        public int dy;
        @SerializedName("hitting_me")
        public boolean hittingMe;
        @SerializedName("facing_me")
        public boolean facingMe;
        @SerializedName("bow_drawn")
        public boolean bowDrawn;
        @SerializedName("void_edge")
        public boolean voidEdge;
        @SerializedName("near_my_bed")
        public boolean nearMyBed;
        public boolean invis;
    }
}
