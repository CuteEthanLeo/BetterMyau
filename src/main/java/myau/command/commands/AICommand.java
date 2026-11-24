package myau.command.commands;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import myau.Myau;
import myau.ai.PromptFormat;
import myau.ai.SnapshotBuilder;
import myau.command.Command;
import myau.config.Config;
import myau.module.modules.AIAdvisor;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * {@code .ai}: the AI advisor. {@code .ai} alone asks who to hit first; {@code .ai ask <问题>} asks
 * about the current situation; {@code status} checks Ollama and the model; {@code show} prints the
 * situation as the model reads it; {@code export} saves it (anonymised: E1..En, no player names) as
 * training material for tools/ai.
 */
public class AICommand extends Command {
    /** Exported situations, one JSON per line, read by tools/ai. */
    public static final File SNAPSHOT_DIR = new File(Config.CONFIG_DIR, "AI/snapshots");
    private static final Gson GSON = new Gson();

    public AICommand() {
        super(new ArrayList<>(Arrays.asList("ai", "军师")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        String sub = args.size() >= 2 ? args.get(1).toLowerCase(Locale.ROOT) : "";
        AIAdvisor advisor = (AIAdvisor) Myau.moduleManager.modules.get(AIAdvisor.class);
        if (advisor == null || Minecraft.getMinecraft().thePlayer == null) return;
        switch (sub) {
            case "":
                ask(advisor, null);
                return;
            case "ask":
                ask(advisor, args.size() > 2 ? String.join(" ", args.subList(2, args.size())) : null);
                return;
            case "review":
            case "复盘":
                if (!advisor.isEnabled()) {
                    ChatUtil.sendFormatted(Myau.clientName + "&e先打开 AIAdvisor 模块&r");
                } else if (!advisor.askReview(args.size() > 2 ? String.join(" ", args.subList(2, args.size())) : null)) {
                    ChatUtil.sendFormatted(Myau.clientName + "&e还没有录到对局：开着 AIAdvisor（record-matches）打一局起床或空岛&r");
                }
                return;
            case "chat":
            case "对话":
                myau.ui.glass.Defer.nextFrame(myau.ai.AIChatScreen::open);
                return;
            case "status":
                status(advisor);
                return;
            case "model":
                if (args.size() > 2) {
                    advisor.model.setValue(args.get(2));
                    ChatUtil.sendFormatted(Myau.clientName + "&a模型改为 &f" + advisor.model.getValue() + "&r");
                } else {
                    ChatUtil.sendFormatted(Myau.clientName + "&7当前模型：&f" + advisor.model.getValue() + "&r");
                }
                return;
            case "show":
                show(advisor, args);
                return;
            case "export":
                export(advisor);
                return;
            default:
                ChatUtil.sendFormatted(String.format("%sUsage: .%s [&oask&r [问题] / &oreview&r [问题] / &ochat&r / &ostatus&r / &omodel&r [名字] / &oshow&r [问题] / &oexport&r]&r",
                        Myau.clientName, args.get(0).toLowerCase(Locale.ROOT)));
        }
    }

    private static void ask(AIAdvisor advisor, String question) {
        if (!advisor.isEnabled()) {
            ChatUtil.sendFormatted(Myau.clientName + "&e先打开 AIAdvisor 模块，回答会显示在左侧面板&r");
            return;
        }
        advisor.ask(question == null ? "target" : "ask", question);
    }

    private static void status(AIAdvisor advisor) {
        String endpoint = advisor.endpoint.getValue();
        String model = advisor.model.getValue();
        advisor.llm().worker().execute(() -> {
            String message;
            try {
                java.util.List<String> models = myau.ai.LocalLLM.listModels(endpoint);
                boolean found = false;
                for (String name : models) {
                    if (name.equals(model) || name.equals(model + ":latest")) found = true;
                }
                message = found ? "&aOllama 正常，模型 &f" + model + "&a 已就绪&r"
                        : "&eOllama 正常，但没有模型 &f" + model + "&e。现有：&f" + (models.isEmpty() ? "（空）" : String.join(", ", models)) + "&r";
            } catch (Exception e) {
                message = "&c连不上 Ollama（" + endpoint + "）：" + e.getMessage() + "&r";
            }
            String text = message;
            Minecraft.getMinecraft().addScheduledTask(() -> ChatUtil.sendFormatted(Myau.clientName + text));
        });
    }

    /** {@code .ai show} prints the "who first" prompt; {@code .ai show <问题>} the prompt for that question. */
    private static void show(AIAdvisor advisor, ArrayList<String> args) {
        String question = args.size() > 2 ? String.join(" ", args.subList(2, args.size())) : null;
        SnapshotBuilder.Snapshot snapshot = advisor.snapshot(question == null ? "target" : "ask", question);
        if (!advisor.isEnabled()) {
            ChatUtil.sendFormatted(Myau.clientName + "&eAIAdvisor 没开，\"在打我\"不会被记录&r");
        }
        for (String line : PromptFormat.render(snapshot.situation).split("\n")) {
            ChatUtil.sendRaw("§7" + line);
        }
    }

    private static void export(AIAdvisor advisor) {
        SnapshotBuilder.Snapshot snapshot = advisor.snapshot("target", null);
        File file = new File(SNAPSHOT_DIR, new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".jsonl");
        JsonObject row = new JsonObject();
        row.addProperty("id", "s" + System.currentTimeMillis());
        row.add("situation", GSON.toJsonTree(snapshot.situation));
        if (!SNAPSHOT_DIR.exists() && !SNAPSHOT_DIR.mkdirs()) {
            ChatUtil.sendFormatted(Myau.clientName + "&c无法创建 " + SNAPSHOT_DIR.getPath() + "&r");
            return;
        }
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            out.write(GSON.toJson(row));
            out.write("\n");
        } catch (Exception e) {
            ChatUtil.sendFormatted(Myau.clientName + "&c导出失败：" + e.getMessage() + "&r");
            return;
        }
        ChatUtil.sendFormatted(String.format("%s&a已导出局势（%d 个敌人）到 &f%s&r",
                Myau.clientName, snapshot.situation.enemies.size(), file.getPath()));
    }
}
