package myau.module.modules;

import myau.Myau;
import myau.ai.AIChatScreen;
import myau.ai.AIKeys;
import myau.ai.CombatTracker;
import myau.ai.Conversation;
import myau.ai.GameContext;
import myau.ai.LocalLLM;
import myau.ai.MatchRecord;
import myau.ai.MatchRecorder;
import myau.ai.PromptFormat;
import myau.ai.Situation;
import myau.ai.SnapshotBuilder;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.TextProperty;
import myau.ui.GlassTheme;
import myau.ui.glass.Draw;
import myau.ui.glass.GlState;
import myau.ui.glass.SmoothFont;
import myau.util.ChatUtil;
import myau.util.RenderUtil;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S45PacketTitle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 军师：把当前局势整理成文本，交给本地问答模型（蒸馏自 GPT-6.2 Sol，经 Ollama 运行）回答
 * "先打谁"、局势追问和赛后复盘。只给建议：显示回答、标出推荐的目标，不碰瞄准、攻击或移动。
 * 局势格式见 {@link PromptFormat}，命令见 {@code .ai}，热键在 选项 → 控制 → "BetterMyau AI 军师"。
 */
public class AIAdvisor extends Module {
    private static final int WAITING = 0, STREAMING = 1, DONE = 2, ERROR = 3;
    private static final float PANEL_W = 210.0F;
    private static final int MAX_LINES = 20;

    public final TextProperty endpoint = new TextProperty("endpoint", "http://127.0.0.1:11434");
    public final TextProperty model = new TextProperty("model", "bettermyau-pvp");
    public final BooleanProperty highlight = new BooleanProperty("highlight", true);
    public final BooleanProperty recordMatches = new BooleanProperty("record-matches", true);
    public final IntProperty panelSeconds = new IntProperty("panel-seconds", 20, 5, 120);
    public final FloatProperty range = new FloatProperty("range", 40.0F, 8.0F, 64.0F);
    public final IntProperty maxEnemies = new IntProperty("max-enemies", 10, 1, 10);
    public final BooleanProperty teams = new BooleanProperty("teams", true);
    public final TextProperty bedwarsWords = new TextProperty("bedwars-words", "BED WARS,BEDWARS,起床");
    public final TextProperty skywarsWords = new TextProperty("skywars-words", "SKYWARS,SKY WARS,空岛");

    private final GameContext context = new GameContext();
    private final CombatTracker combat = new CombatTracker();
    private final LocalLLM llm = new LocalLLM();
    private final MatchRecorder recorder = new MatchRecorder();
    private final Conversation conversation = new Conversation();
    private boolean startedThisWorld;

    // answer panel (client thread only)
    private String panelTitle;
    private String panelText = "";
    private int panelState = DONE;
    private long panelDoneAt;
    private String targetLabel;
    private EntityPlayer highlightTarget;
    private long highlightUntil;
    private String wrappedFor;
    private List<String> wrapped = Collections.emptyList();

    public AIAdvisor() {
        super("AIAdvisor", false);
        AIKeys.init();
    }

    public LocalLLM llm() {
        return this.llm;
    }

    /** Current situation; "ask" carries the player's question, "target" asks who to hit first. */
    public SnapshotBuilder.Snapshot snapshot(String kind, String question) {
        this.context.setKeywords(this.bedwarsWords.getValue(), this.skywarsWords.getValue());
        return SnapshotBuilder.build(this.context, this.combat, this.range.getValue(), this.maxEnemies.getValue(),
                this.teams.getValue(), kind, question);
    }

    /** Asks the local model about the current situation; the answer streams into the panel. */
    public void ask(String kind, String question) {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        SnapshotBuilder.Snapshot snapshot = this.snapshot(kind, question);
        this.send("target".equals(kind) ? PromptFormat.TARGET_QUESTION : question,
                PromptFormat.render(snapshot.situation), snapshot, false);
    }

    /** Reviews the game being recorded, or the last one; false when nothing was recorded yet. */
    public boolean askReview(String question) {
        MatchRecord match = this.recorder.latest();
        if (match == null) return false;
        String prompt = PromptFormat.renderReview(MatchRecorder.forPrompt(match, question));
        this.send(question == null ? "复盘" : question, prompt, null, true);
        return true;
    }

    public MatchRecord lastMatch() {
        return this.recorder.latest();
    }

    public Conversation conversation() {
        return this.conversation;
    }

    /** Chat screen: a question (null = who first), with or without the current situation. */
    public void chatAsk(String question, boolean withSituation) {
        if (mc.thePlayer == null) return;
        SnapshotBuilder.Snapshot snapshot = null;
        String content;
        if (withSituation && mc.theWorld != null) {
            snapshot = this.snapshot(question == null ? "target" : "ask", question);
            content = PromptFormat.render(snapshot.situation);
        } else {
            if (question == null) return;
            Situation qa = new Situation();
            qa.kind = "qa";
            qa.question = question;
            content = PromptFormat.render(qa);
        }
        this.streamInto(this.conversation.add(question == null ? PromptFormat.TARGET_QUESTION : question, content), snapshot);
    }

    /** Chat screen: review the current or last game; false when nothing was recorded. */
    public boolean chatReview(String question) {
        MatchRecord match = this.recorder.latest();
        if (match == null) return false;
        String content = PromptFormat.renderReview(MatchRecorder.forPrompt(match, question));
        this.streamInto(this.conversation.add(question == null ? "复盘上一局" : question, content), null);
        return true;
    }

    private void streamInto(Conversation.Message answer, SnapshotBuilder.Snapshot snapshot) {
        this.llm.chat(this.endpoint.getValue(), this.model.getValue(), this.conversation.context(), new LocalLLM.Listener() {
            @Override
            public void onText(String soFar) {
                answer.text = soFar;
                answer.state = Conversation.STREAMING;
            }

            @Override
            public void onDone(String text) {
                answer.text = text;
                answer.state = Conversation.DONE;
                if (snapshot != null) answer.target = AIAdvisor.this.highlightFor(snapshot, text);
            }

            @Override
            public void onError(String message) {
                answer.text = message;
                answer.state = Conversation.ERROR;
            }
        });
    }

    private void send(String title, String prompt, SnapshotBuilder.Snapshot snapshot, boolean echoToChat) {
        this.panelTitle = title;
        this.panelText = "";
        this.panelState = WAITING;
        this.targetLabel = null;
        this.highlightTarget = null;
        List<String[]> messages = new ArrayList<>();
        messages.add(new String[]{"user", prompt});
        this.llm.chat(this.endpoint.getValue(), this.model.getValue(), messages, new LocalLLM.Listener() {
            @Override
            public void onText(String soFar) {
                AIAdvisor.this.panelText = soFar;
                AIAdvisor.this.panelState = STREAMING;
                // the first line names the target: mark it as soon as it is complete
                if (snapshot != null && AIAdvisor.this.targetLabel == null && soFar.indexOf('\n') > 0) AIAdvisor.this.applyTarget(snapshot, soFar);
            }

            @Override
            public void onDone(String answer) {
                AIAdvisor.this.panelText = answer;
                AIAdvisor.this.panelState = DONE;
                AIAdvisor.this.panelDoneAt = System.currentTimeMillis();
                if (snapshot != null && AIAdvisor.this.targetLabel == null) AIAdvisor.this.applyTarget(snapshot, answer);
                if (echoToChat) {
                    ChatUtil.sendFormatted(Myau.clientName + "&bAI 军师 · " + title + "&r");
                    for (String line : answer.replace("**", "").split("\n")) ChatUtil.sendRaw("\u00a77" + line);
                }
            }

            @Override
            public void onError(String message) {
                AIAdvisor.this.panelText = message;
                AIAdvisor.this.panelState = ERROR;
                AIAdvisor.this.panelDoneAt = System.currentTimeMillis();
            }
        });
    }

    private void applyTarget(SnapshotBuilder.Snapshot snapshot, String answer) {
        String label = this.highlightFor(snapshot, answer);
        if (label != null) this.targetLabel = label;
    }

    /** Marks the player a "先打：E3" answer picked (5 s); returns the label to show, or null. */
    private String highlightFor(SnapshotBuilder.Snapshot snapshot, String answer) {
        String id = PromptFormat.parseTarget(answer);
        if (id == null) return null;
        if ("无".equals(id)) return "先别打，先处理自己";
        EntityPlayer player = snapshot.resolve(id);
        if (player == null) return null;
        this.highlightTarget = player;
        this.highlightUntil = System.currentTimeMillis() + 5000L;
        return id + " = " + player.getName();
    }

    @Override
    public void onDisabled() {
        this.llm.cancel();
        this.panelTitle = null;
        this.highlightTarget = null;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) return;
        this.combat.tick();
        GameDetector detector = (GameDetector) Myau.moduleManager.modules.get(GameDetector.class);
        boolean inGame = detector == null || detector.isInGame();
        this.context.tick(inGame);
        if (this.recordMatches.getValue()) {
            if (!this.recorder.recording() && !this.startedThisWorld && inGame) {
                String mode = this.context.mode();
                if (!GameContext.UNKNOWN.equals(mode)) {
                    this.startedThisWorld = true;
                    this.recorder.start(mode, this.context.scale(mode), this.context.ownTeam());
                }
            }
            this.recorder.tick(this.combat);
            this.recorder.endIfFinalDeath();
        }
        if (mc.currentScreen == null) {
            if (AIKeys.quickAskPressed()) this.ask("target", null);
            if (AIKeys.openChatPressed()) mc.displayGuiScreen(new AIChatScreen(this));
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (this.isEnabled() && event.getTarget() != null) this.recorder.onAttack(event.getTarget());
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || !this.recorder.recording()) return;
        // network thread: hand everything to the client thread
        if (event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) event.getPacket();
            if (status.getOpCode() != 2 || mc.theWorld == null) return;
            net.minecraft.entity.Entity entity = status.getEntity(mc.theWorld);
            if (entity != null) {
                int id = entity.getEntityId();
                mc.addScheduledTask(() -> this.recorder.onHurt(id));
            }
        } else if (event.getPacket() instanceof S02PacketChat) {
            S02PacketChat chat = (S02PacketChat) event.getPacket();
            if (chat.getType() == 2 || chat.getChatComponent() == null) return; // action bar
            String text = chat.getChatComponent().getUnformattedText();
            mc.addScheduledTask(() -> this.serverText(text, false));
        } else if (event.getPacket() instanceof S45PacketTitle) {
            S45PacketTitle title = (S45PacketTitle) event.getPacket();
            if (title.getMessage() == null) return;
            String text = title.getMessage().getUnformattedText();
            mc.addScheduledTask(() -> this.serverText(text, true));
        }
    }

    private void serverText(String text, boolean title) {
        if (mc.thePlayer == null) return;
        this.recorder.onServerText(text, title, mc.thePlayer.getName(), this.context.ownTeam());
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.recorder.finish(null);
        this.startedThisWorld = false;
        this.context.reset();
        this.combat.reset();
        this.highlightTarget = null;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || !this.highlight.getValue()) return;
        EntityPlayer target = this.highlightTarget;
        if (target == null || target.isDead || System.currentTimeMillis() > this.highlightUntil) return;
        int accent = GlassTheme.accent();
        RenderUtil.enableRenderState();
        RenderUtil.drawEntityBoundingBox(target, accent >> 16 & 0xFF, accent >> 8 & 0xFF, accent & 0xFF, 220, 2.0F, 0.15);
        RenderUtil.disableRenderState();
    }

    @EventTarget(Priority.LOWEST)
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || this.panelTitle == null) return;
        long now = System.currentTimeMillis();
        long shownFor = this.panelState >= DONE ? now - this.panelDoneAt : 0L;
        long limit = this.panelSeconds.getValue() * 1000L;
        if (shownFor > limit) return;
        float alpha = shownFor > limit - 400L ? (limit - shownFor) / 400.0F : 1.0F;

        GlState.hudDefaults();
        Draw.useGuiScale();
        ScaledResolution sr = new ScaledResolution(mc);
        SmoothFont titleFont = SmoothFont.bold(9.0F);
        SmoothFont bodyFont = SmoothFont.regular(8.5F);
        float pad = 8.0F;
        float textW = PANEL_W - pad * 2;
        List<String> lines = this.wrap(bodyFont, textW);
        float lineH = bodyFont.height() + 2.0F;
        String status = this.panelState == WAITING ? "  思考中…" : this.panelState == STREAMING ? "  …" : "";
        float h = pad + titleFont.height() + 3.0F + lineH * Math.max(1, lines.size())
                + (this.targetLabel != null ? lineH + 2.0F : 0.0F) + pad;
        float x = 8.0F;
        float y = Math.max(30.0F, sr.getScaledHeight() * 0.28F);
        int accent = this.panelState == ERROR ? GlassTheme.BAD : GlassTheme.accent();

        Draw.pushAlpha(alpha);
        GlassTheme.panel(x, y, PANEL_W, h, 9, 0.8F);
        Draw.rect(x + pad, y + pad + 1.0F, 3, titleFont.height() - 2.0F, 1.5F, accent);
        titleFont.draw(titleFont.trim("AI 军师 · " + this.panelTitle + status, textW - 8), x + pad + 7.0F, y + pad, GlassTheme.TEXT);
        float ty = y + pad + titleFont.height() + 3.0F;
        if (this.targetLabel != null) {
            bodyFont.draw(bodyFont.trim("→ " + this.targetLabel, textW), x + pad, ty, accent);
            ty += lineH + 2.0F;
        }
        for (String line : lines) {
            bodyFont.draw(line, x + pad, ty, this.panelState == ERROR ? GlassTheme.BAD : GlassTheme.MUTED);
            ty += lineH;
        }
        Draw.popAlpha();
    }

    /** The answer broken into lines that fit the panel; recomputed only when the text changed. */
    private List<String> wrap(SmoothFont font, float width) {
        String text = this.panelText;
        if (text.equals(this.wrappedFor)) return this.wrapped;
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.replace("**", "").split("\n")) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                line.append(paragraph.charAt(i));
                if (font.width(line.toString()) > width) {
                    line.setLength(line.length() - 1);
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(paragraph.charAt(i));
                }
            }
            if (line.length() > 0) lines.add(line.toString());
        }
        if (lines.size() > MAX_LINES) {
            List<String> cut = new ArrayList<>(lines.subList(0, MAX_LINES));
            cut.set(MAX_LINES - 1, cut.get(MAX_LINES - 1) + "…");
            lines = cut;
        }
        this.wrappedFor = text;
        this.wrapped = lines;
        return lines;
    }
}
