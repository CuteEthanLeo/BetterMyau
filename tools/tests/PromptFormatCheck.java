import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.ai.MatchRecord;
import myau.ai.PromptFormat;
import myau.ai.Situation;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * PromptFormat must render exactly like tools/ai/render.py. Needs Situation.java, PromptFormat.java,
 * the Gson jar and a JDK:
 *   javac -encoding UTF-8 -cp gson.jar -d out src/main/java/myau/ai/Situation.java src/main/java/myau/ai/MatchRecord.java src/main/java/myau/ai/PromptFormat.java tools/tests/PromptFormatCheck.java
 *   java -cp out;gson.jar PromptFormatCheck tools/ai/golden/situations.jsonl
 */
public final class PromptFormatCheck {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "tools/ai/golden/situations.jsonl";
        Gson gson = new Gson();
        JsonParser parser = new JsonParser();
        int rows = 0, bad = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                rows++;
                JsonObject row = parser.parse(line).getAsJsonObject();
                JsonObject situation = row.getAsJsonObject("situation");
                String expected = row.get("text").getAsString();
                String got = situation.has("kind") && "review".equals(situation.get("kind").getAsString())
                        ? PromptFormat.renderReview(gson.fromJson(situation, MatchRecord.class))
                        : PromptFormat.render(gson.fromJson(situation, Situation.class));
                if (!expected.equals(got)) {
                    bad++;
                    System.out.println("mismatch on row " + rows + ":\n--- expected\n" + expected + "\n--- got\n" + got);
                }
            }
        }

        check(PromptFormat.parseTarget("先打：E3\n理由：……"), "E3");
        check(PromptFormat.parseTarget("先打: 无\n先回血"), "无");
        check(PromptFormat.parseTarget("  先打 ：E10"), "E10");
        check(PromptFormat.parseTarget("我觉得先打 E3"), null);
        check(String.valueOf(PromptFormat.enemyIds("#v1 模式=起床\n我 血20/20\nE1 距3.0\nE2 距4.0\n问：x")), "[E1, E2]");
        check(String.valueOf(PromptFormat.roundDist(3.25)), "3.3");

        System.out.println("golden rows: " + rows + ", mismatches: " + bad + (bad == 0 ? "  OK" : "  FAIL"));
        if (bad > 0) System.exit(1);
    }

    private static void check(String got, String expected) {
        boolean ok = expected == null ? got == null : expected.equals(got);
        if (!ok) {
            System.out.println("check failed: expected " + expected + ", got " + got);
            System.exit(1);
        }
    }
}
