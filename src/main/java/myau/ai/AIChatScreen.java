package myau.ai;

import myau.Myau;
import myau.module.modules.AIAdvisor;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.Draw;
import myau.ui.glass.SmoothFont;
import myau.ui.glass.TextEdit;
import myau.ui.glass.UiSound;
import myau.util.ime.TextInputScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 军师 chat: ask follow-up questions, ask "who first" or for a review with one click, read the
 * history. Questions carry the current situation unless "附带局势" is switched off. The history
 * lives in {@link AIAdvisor} and survives closing the screen. Laid out in design units like
 * {@link myau.guide.GuideScreen}.
 */
public final class AIChatScreen extends GuiScreen implements TextInputScreen {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float MIN_W = 480, MIN_H = 300, HEADER = 38, BAR = 40, PAD = 14;

    private final AIAdvisor advisor;
    private final TextEdit input = new TextEdit(300);
    private boolean withSituation = true;

    private float ui = 2, zoom = 1, sw = MIN_W, sh = MIN_H, mx, my, dt, inputScroll;
    private float px, py, pw, ph;
    private float scroll, scrollTarget, contentH, viewH;
    private boolean stickBottom = true;
    private int lastCount = -1;
    private final Map<Conversation.Message, Wrapped> wraps = new IdentityHashMap<>();

    private interface Action {
        void run();
    }

    private static final class Hit {
        final float x0, y0, x1, y1;
        final Action action;

        Hit(float x, float y, float w, float h, Action action) {
            this.x0 = x;
            this.y0 = y;
            this.x1 = x + w;
            this.y1 = y + h;
            this.action = action;
        }
    }

    private static final class Wrapped {
        String text;
        float width;
        List<String> lines;
    }

    private final List<Hit> hits = new ArrayList<>();

    public AIChatScreen(AIAdvisor advisor) {
        this.advisor = advisor;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public boolean wantsTextInput() {
        return true;
    }

    // ================================================================ frame

    private void measure() {
        float gui = new ScaledResolution(mc).getScaleFactor();
        float scale = gui;
        if (mc.displayWidth / scale < MIN_W || mc.displayHeight / scale < MIN_H) {
            scale = Math.min(mc.displayWidth / MIN_W, mc.displayHeight / MIN_H);
            scale = Math.max(0.5f, (float) Math.floor(scale * 4) / 4f);
        }
        this.ui = scale;
        this.zoom = this.ui / gui;
        this.sw = mc.displayWidth / this.ui;
        this.sh = mc.displayHeight / this.ui;
        this.pw = Math.min(this.sw - 28, 660);
        this.ph = Math.min(this.sh - 28, 460);
        this.px = (this.sw - this.pw) / 2;
        this.py = (this.sh - this.ph) / 2;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.dt = Anim.dt();
        this.measure();
        this.mx = Mouse.getX() / this.ui;
        this.my = (mc.displayHeight - Mouse.getY() - 1) / this.ui;
        this.hits.clear();

        GlassTheme.screenBackdrop(this.width, this.height);
        Draw.pushTransform(0, 0, this.zoom, 0, 0);
        Draw.beginBatch();
        try {
            GlassTheme.panel(this.px, this.py, this.pw, this.ph, GlassTheme.R_PANEL, 1.2f);
            this.drawHeader();
            this.drawMessages();
            this.drawBar();
        } finally {
            Draw.endBatch();
            Draw.popTransform();
        }
    }

    private void drawHeader() {
        SmoothFont title = SmoothFont.bold(12.5f), small = SmoothFont.regular(8.5f);
        float x = this.px + PAD, y = this.py + 12;
        Draw.rect(x, y + 2, 3, title.height() - 3, 1.5f, GlassTheme.accent());
        title.draw("AI 军师", x + 8, y, GlassTheme.TEXT);
        small.draw("本地模型 " + this.advisor.model.getValue() + " · 只给建议", x + 8 + title.width("AI 军师") + 10, y + 3.5f, GlassTheme.FAINT);
        float bx = this.px + this.pw - PAD;
        bx = this.button(bx, y - 2, "清空", false, () -> {
            this.advisor.conversation().clear();
            this.wraps.clear();
        });
        bx = this.button(bx - 6, y - 2, "复盘上一局", false, () -> {
            if (!this.advisor.chatReview(null)) this.advisor.conversation().add("复盘上一局", "").state = Conversation.ERROR;
        });
        this.button(bx - 6, y - 2, "现在先打谁", true, () -> this.advisor.chatAsk(null, true));
    }

    /** A pill button whose right edge is at {@code right}; returns its left edge. */
    private float button(float right, float y, String label, boolean primary, Action action) {
        SmoothFont font = SmoothFont.regular(9);
        float w = font.width(label) + 16, h = 18, x = right - w;
        boolean hover = this.mx >= x && this.mx < x + w && this.my >= y && this.my < y + h;
        int accent = GlassTheme.accent();
        Draw.rect(x, y, w, h, h / 2, primary ? Draw.alpha(accent, hover ? 0.55f : 0.38f) : Draw.alpha(0xffffffff, hover ? 0.14f : 0.07f));
        font.draw(label, x + 8, font.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        this.hits.add(new Hit(x, y, w, h, action));
        return x;
    }

    private void drawMessages() {
        SmoothFont font = SmoothFont.regular(9.5f), small = SmoothFont.regular(8.5f);
        float areaX = this.px + PAD, areaY = this.py + HEADER, areaW = this.pw - PAD * 2;
        float areaH = this.ph - HEADER - BAR - 6;
        this.viewH = areaH;
        float lineH = font.height() + 3;
        float bubbleMax = areaW * 0.78f;
        List<Conversation.Message> list = this.advisor.conversation().messages();

        // measure
        float total = 6;
        List<List<String>> lineSets = new ArrayList<>();
        for (Conversation.Message m : list) {
            String text = this.textOf(m);
            List<String> lines = this.wrap(m, font, text, bubbleMax - 20);
            lineSets.add(lines);
            total += lines.size() * lineH + 14 + (m.target != null ? lineH : 0) + 8;
        }
        this.contentH = total;
        float max = Math.max(0, this.contentH - areaH);
        if (list.size() != this.lastCount) {
            this.lastCount = list.size();
            this.stickBottom = true;
        }
        if (this.stickBottom) this.scrollTarget = max;
        this.scrollTarget = Math.max(0, Math.min(max, this.scrollTarget));
        this.scroll += (this.scrollTarget - this.scroll) * (1 - (float) Math.exp(-16 * this.dt));

        Draw.pushClip(areaX - 2, areaY, areaW + 4, areaH);
        if (list.isEmpty()) {
            String hint = "问点什么，比如“对面钻甲我铁甲，该硬打吗？”。默认附带当前局势。";
            small.draw(hint, areaX + (areaW - small.width(hint)) / 2, areaY + areaH / 2 - 6, GlassTheme.FAINT);
        }
        float y = areaY + 6 - this.scroll;
        for (int i = 0; i < list.size(); i++) {
            Conversation.Message m = list.get(i);
            List<String> lines = lineSets.get(i);
            float textW = 0;
            for (String l : lines) textW = Math.max(textW, font.width(l));
            if (m.target != null) textW = Math.max(textW, font.width("→ " + m.target));
            float bw = Math.min(bubbleMax, textW + 20);
            float bh = lines.size() * lineH + 14 + (m.target != null ? lineH : 0);
            float bx = m.fromPlayer ? areaX + areaW - bw : areaX;
            if (y + bh > areaY - 4 && y < areaY + areaH + 4) {
                if (m.fromPlayer) {
                    Draw.rect(bx, y, bw, bh, 9, Draw.alpha(GlassTheme.accent(), 0.30f));
                } else {
                    GlassTheme.card(bx, y, bw, bh, 9, 0.4f);
                }
                float ty = y + 7;
                if (m.target != null) {
                    font.draw("→ " + m.target, bx + 10, ty, GlassTheme.accent());
                    ty += lineH;
                }
                int color = m.state == Conversation.ERROR ? GlassTheme.BAD : m.fromPlayer ? GlassTheme.TEXT : 0xffdfe2e8;
                for (String l : lines) {
                    font.draw(l, bx + 10, ty, color);
                    ty += lineH;
                }
            }
            y += bh + 8;
        }
        Draw.popClip();
    }

    private String textOf(Conversation.Message m) {
        if (m.fromPlayer) return m.display;
        if (m.state == Conversation.WAITING) return "思考中…";
        if (m.state == Conversation.ERROR && (m.text == null || m.text.isEmpty())) return "还没有录到对局：开着 AIAdvisor 打一局起床或空岛";
        return m.text == null ? "" : m.text.replace("**", "");
    }

    private List<String> wrap(Conversation.Message m, SmoothFont font, String text, float width) {
        Wrapped w = this.wraps.get(m);
        if (w != null && w.width == width && text.equals(w.text)) return w.lines;
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
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
            lines.add(line.toString());
        }
        if (w == null) {
            w = new Wrapped();
            this.wraps.put(m, w);
        }
        w.text = text;
        w.width = width;
        w.lines = lines;
        return lines;
    }

    private void drawBar() {
        SmoothFont font = SmoothFont.regular(9.5f), small = SmoothFont.regular(8.5f);
        float y = this.py + this.ph - BAR + 4, h = 26;
        float x = this.px + PAD;
        // situation toggle
        String toggle = (this.withSituation ? "✓ " : "  ") + "附带局势";
        float tw = small.width(toggle) + 14;
        Draw.rect(x, y + 4, tw, h - 8, (h - 8) / 2, this.withSituation ? Draw.alpha(GlassTheme.accent(), 0.35f) : Draw.alpha(0xffffffff, 0.07f));
        small.draw(toggle, x + 7, small.centerY(y + 4, h - 8) - 0.5f, GlassTheme.TEXT);
        this.hits.add(new Hit(x, y + 4, tw, h - 8, () -> this.withSituation = !this.withSituation));
        // send button
        float sendW = font.width("发送") + 22, sendX = this.px + this.pw - PAD - sendW;
        boolean hover = this.mx >= sendX && this.mx < sendX + sendW && this.my >= y && this.my < y + h;
        Draw.rect(sendX, y, sendW, h, 7, Draw.alpha(GlassTheme.accent(), hover ? 0.7f : 0.5f));
        font.draw("发送", sendX + 11, font.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        this.hits.add(new Hit(sendX, y, sendW, h, this::send));
        // field
        float fx = x + tw + 8, fw = sendX - 8 - fx;
        GlassTheme.field(fx, y, fw, h, 7, 1);
        if (this.input.isEmpty()) {
            font.draw(this.withSituation ? "问当前局势，回车发送" : "问任何 PvP 问题，回车发送", fx + 9, font.centerY(y, h) - 0.5f, GlassTheme.FAINT);
        }
        this.drawEdit(font, fx + 9, y, fw - 18, h);
    }

    /** Text field contents with caret and selection; keeps the caret in view (as MusicScreen does). */
    private void drawEdit(SmoothFont font, float x, float y, float w, float h) {
        String text = this.input.toString();
        float full = font.width(text), caretAt = font.width(text.substring(0, Math.min(this.input.caret(), text.length())));
        float room = Math.max(1, w - 2), off = 0;
        if (full > room) {
            off = this.inputScroll;
            if (caretAt - off > room) off = caretAt - room;
            if (caretAt - off < 0) off = caretAt;
            off = Math.max(0, Math.min(off, full - room));
        }
        this.inputScroll = off;
        float start = x - off;
        int accent = GlassTheme.accent();
        Draw.pushClip(x - 1, y, w + 2, h);
        if (this.input.hasSelection()) {
            float from = font.width(text.substring(0, this.input.selectionStart())), to = font.width(text.substring(0, this.input.selectionEnd()));
            Draw.rect(start + from, y + 5, to - from, h - 10, 2, Draw.alpha(accent, 0.38f));
        }
        if (!text.isEmpty()) font.draw(text, start, font.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        Draw.popClip();
        float cx = Math.max(x, Math.min(x + w, start + caretAt));
        if (System.currentTimeMillis() % 1000 < 550) Draw.rect(cx, y + 6.5f, 0.8f, h - 13, 0, accent);
        Draw.reportCaret(cx, y + 5, h - 10);
    }

    // ================================================================ input

    private void send() {
        String question = this.input.toString().trim();
        if (question.isEmpty()) return;
        this.input.clear();
        UiSound.CLICK.play();
        this.advisor.chatAsk(question, this.withSituation);
    }

    @Override
    public void handleMouseInput() {
        float x = Mouse.getEventX() / this.ui, y = (mc.displayHeight - Mouse.getEventY() - 1) / this.ui;
        int button = Mouse.getEventButton();
        if (Mouse.getEventButtonState() && button == 0) {
            for (int i = this.hits.size() - 1; i >= 0; i--) {
                Hit h = this.hits.get(i);
                if (x >= h.x0 && x < h.x1 && y >= h.y0 && y < h.y1) {
                    UiSound.CLICK.play();
                    h.action.run();
                    break;
                }
            }
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            this.scrollTarget -= wheel > 0 ? 40 : -40;
            float max = Math.max(0, this.contentH - this.viewH);
            this.scrollTarget = Math.max(0, Math.min(max, this.scrollTarget));
            this.stickBottom = this.scrollTarget >= max - 2;
        }
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            UiSound.CLOSE.play();
            mc.displayGuiScreen(null);
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            this.send();
            return;
        }
        this.input.key(typed, key);
    }

    /** Opens the chat (on the next frame when called from a chat command). */
    public static void open() {
        AIAdvisor advisor = (AIAdvisor) Myau.moduleManager.modules.get(AIAdvisor.class);
        if (advisor != null) mc.displayGuiScreen(new AIChatScreen(advisor));
    }
}
