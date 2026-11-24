package myau.guide;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.GuiModule;
import myau.module.modules.HUD;
import myau.property.Property;
import myau.property.properties.ModeProperty;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.Draw;
import myau.ui.glass.Interaction;
import myau.ui.glass.ScreenTransition;
import myau.ui.glass.SmoothFont;
import myau.ui.glass.TextEdit;
import myau.ui.glass.UiSound;
import myau.util.KeyBindUtil;
import myau.util.ime.TextInputScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The in-client guide: topics grouped on the left (with search), the article on the right.
 * Articles come from {@link GuideContent}; text is set with {@link TextWrap}.
 *
 * <p>Laid out in design units like the music screen ({@link #ui} pixels each, at least
 * {@link #MIN_W} x {@link #MIN_H}). Every clickable thing registers a hit rectangle while it is
 * drawn; a click goes to the topmost one under the pointer. Closing goes back to the screen it was
 * opened from (main menu, click GUI) or to the game.
 */
public final class GuideScreen extends GuiScreen implements TextInputScreen {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float MIN_W = 640, MIN_H = 380, MARGIN = 14, SIDE_W = 172, GAP = 10;
    private static final float ITEM_H = 21, GROUP_H = 18, TEXT_MAX = 600, PAD_X = 28;
    private static final float LH = 13.5f, LH_HEADING = 16;

    private final GuiScreen parent;

    // ---- frame
    private float ui = 2, zoom = 1, sw = MIN_W, sh = MIN_H, mx, my, dt, openTime;
    private boolean mouseDown;
    private int wheel;

    // ---- layout (design units)
    private float px, py, pw, ph, sideX, sideY, sideW, sideH, mainX, mainY, mainW, mainH;

    // ---- hits
    private interface Action {
        void run(int button);
    }

    private static final class Hit {
        float x0, y0, x1, y1;
        Action action;
    }

    private final List<Hit> hits = new ArrayList<>();
    private int hitCount;
    private final float[] clipped = new float[4];
    private final Map<String, Interaction> feels = new HashMap<>();

    // ---- content and navigation
    private final List<GuideContent.Topic> topics = GuideContent.topics();
    private GuideContent.Topic topic;
    private final TextEdit search = new TextEdit(40);
    private boolean searchFocused;
    private float searchScroll;
    private final Anim.Smooth pillY = new Anim.Smooth(0, 16), reveal = new Anim.Smooth(0, 9);
    private boolean pillPlaced;
    private float sideScroll, sideTarget, sideContent;
    private final Scroller scroll = new Scroller();
    private String toast = "";
    private long toastAt;

    // ---- the laid-out article
    private final List<Item> items = new ArrayList<>();
    private float contentH;
    private String layoutKey = "";

    public GuideScreen(GuiScreen parent) {
        this(parent, null);
    }

    /** @param start topic to open (id, title or a word in it); null for the first */
    public GuideScreen(GuiScreen parent, String start) {
        this.parent = parent;
        GuideContent.Topic t = start == null ? null : GuideContent.find(start);
        this.topic = t != null ? t : this.topics.get(0);
        GuideState.markSeen();
    }

    // ================================================================ lifecycle

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

    /** Typing anywhere searches, so the input method may stay on while the guide is open. */
    @Override
    public boolean wantsTextInput() {
        return true;
    }

    private void close() {
        UiSound.CLOSE.play();
        mc.displayGuiScreen(this.parent);
    }

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
    }

    private void layout() {
        float w = Math.min(this.sw - MARGIN * 2, 1040), h = Math.min(this.sh - MARGIN * 2, 640);
        this.px = (this.sw - w) / 2;
        this.py = (this.sh - h) / 2;
        this.pw = w;
        this.ph = h;
        this.sideX = this.px + GAP;
        this.sideY = this.py + GAP;
        this.sideW = SIDE_W;
        this.sideH = this.ph - GAP * 2;
        this.mainX = this.sideX + this.sideW + GAP;
        this.mainY = this.py + GAP;
        this.mainW = this.px + this.pw - GAP - this.mainX;
        this.mainH = this.ph - GAP * 2;
    }

    // ================================================================ frame

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.dt = Anim.dt();
        this.openTime += this.dt;
        this.measure();
        this.layout();
        this.mx = Mouse.getX() / this.ui;
        this.my = (mc.displayHeight - Mouse.getY() - 1) / this.ui;
        this.mouseDown = Mouse.isButtonDown(0);

        GlassTheme.screenBackdrop(this.width, this.height);
        Draw.pushTransform(0, 0, this.zoom, 0, 0);
        Draw.beginBatch();
        try {
            this.hitCount = 0;
            GlassTheme.panel(this.px, this.py, this.pw, this.ph, GlassTheme.R_PANEL, 1.2f);
            ScreenTransition.focus(this.px * this.zoom, this.py * this.zoom, this.pw * this.zoom, this.ph * this.zoom, GlassTheme.R_PANEL * this.zoom);
            this.drawSidebar();
            this.drawArticle();
            this.drawToast();
        } finally {
            Draw.endBatch();
            Draw.popTransform();
        }
        this.wheel = 0;
    }

    // ================================================================ sidebar

    private String query() {
        return this.search.toString().trim().toLowerCase(Locale.ROOT);
    }

    private List<GuideContent.Topic> visibleTopics() {
        String q = this.query();
        if (q.isEmpty()) return this.topics;
        List<GuideContent.Topic> out = new ArrayList<>();
        for (GuideContent.Topic t : this.topics) if (t.matches(q) > 0) out.add(t);
        return out;
    }

    private void drawSidebar() {
        float x = this.sideX, y = this.sideY, w = this.sideW;
        GlassTheme.card(x, y, w, this.sideH, GlassTheme.R_CARD, 0.4f);

        // Brand
        int a = GlassTheme.accent(0), b = GlassTheme.accent(6);
        float bx = x + 12, by = y + 12;
        Draw.glow(bx, by, 20, 20, 6, 8, Draw.alpha(a, 0.35f));
        Draw.gradientH(bx, by, 20, 20, 6, a, b);
        Draw.gradientV(bx, by, 20, 20, 6, 0x38ffffff, 0x00ffffff);
        this.bookGlyph(bx + 10, by + 10, 0xf0ffffff);
        SmoothFont title = SmoothFont.bold(10), tiny = SmoothFont.regular(6.5f);
        title.draw("使用指南", bx + 27, by + 1, GlassTheme.TEXT);
        tiny.draw("BetterMyau v" + Myau.version, bx + 27, by + 1 + title.height(), GlassTheme.FAINT);

        // Search
        float fy = y + 44, fh = 22, fx = x + 8, fw = w - 16;
        GlassTheme.field(fx, fy, fw, fh, GlassTheme.R_CONTROL + 2, this.searchFocused ? 1 : 0);
        this.searchGlyph(fx + 11, fy + fh / 2);
        SmoothFont f = SmoothFont.regular(8);
        if (this.search.isEmpty() && !this.searchFocused) {
            f.draw("搜索（直接打字）", fx + 21, f.centerY(fy, fh), GlassTheme.FAINT);
        } else {
            this.drawEdit(f, fx + 21, fy, fw - 28, fh);
        }
        this.hit(fx, fy, fw, fh, button -> {
            this.searchFocused = true;
            this.search.caretToEnd();
        });

        // Topics, grouped; the list scrolls when the window is short.
        float listY = fy + fh + 8, listH = y + this.sideH - 8 - listY;
        List<GuideContent.Topic> shown = this.visibleTopics();
        String q = this.query();
        int d = this.takeWheel(x, listY, w, listH);
        if (d != 0) this.sideTarget -= d / 120f * 40;
        this.sideTarget = Math.max(0, Math.min(Math.max(0, this.sideContent - listH), this.sideTarget));
        this.sideScroll += (this.sideTarget - this.sideScroll) * (1 - (float) Math.exp(-18 * this.dt));

        Draw.pushClip(x, listY, w, listH);
        float iy = listY - this.sideScroll;
        String group = null;
        float selectedY = Float.NaN;
        // Positions first, so the selection pill can be drawn under the rows.
        Map<GuideContent.Topic, Float> rows = new LinkedHashMap<>();
        List<Object[]> groups = new ArrayList<>();
        for (GuideContent.Topic t : shown) {
            if (!t.group.equals(group)) {
                group = t.group;
                if (!rows.isEmpty()) iy += 4;
                groups.add(new Object[]{group, iy});
                iy += GROUP_H;
            }
            rows.put(t, iy);
            if (t == this.topic) selectedY = iy;
            iy += ITEM_H + 1;
        }
        this.sideContent = iy + this.sideScroll - listY + 4;

        SmoothFont groupFont = SmoothFont.regular(6.5f);
        for (Object[] g : groups) {
            groupFont.draw((String) g[0], x + 14, (Float) g[1] + 6, GlassTheme.FAINT);
        }
        if (!Float.isNaN(selectedY)) {
            if (!this.pillPlaced) {
                this.pillY.snap(selectedY);
                this.pillPlaced = true;
            }
            float pyv = this.pillY.update(selectedY, this.dt);
            int accent = GlassTheme.accent();
            Draw.rect(x + 6, pyv, w - 12, ITEM_H, 6, 0x1cffffff);
            Draw.glow(x + 8, pyv + 5, 2, ITEM_H - 10, 1, 5, Draw.alpha(accent, 0.5f));
            Draw.rect(x + 8, pyv + 5, 2, ITEM_H - 10, 1, accent);
        }
        for (Map.Entry<GuideContent.Topic, Float> e : rows.entrySet()) {
            GuideContent.Topic t = e.getKey();
            float ry = e.getValue();
            if (ry + ITEM_H < listY || ry > listY + listH) continue;
            boolean current = t == this.topic;
            Interaction feel = this.feel("topic:" + t.id);
            boolean hov = this.over(x + 6, ry, w - 12, ITEM_H);
            feel.update(hov, hov && this.mouseDown, this.dt);
            if (!current && feel.hot > 0.01f) Draw.rect(x + 6, ry, w - 12, ITEM_H, 6, Draw.alpha(0xffffff, 0.06f * feel.hot));
            SmoothFont rf = current ? SmoothFont.bold(8) : SmoothFont.regular(8);
            int color = current ? GlassTheme.TEXT : Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, feel.hot);
            rf.draw(rf.trim(t.title, w - 50), x + 16, rf.centerY(ry, ITEM_H) - 0.5f, color);
            if (!q.isEmpty()) {
                SmoothFont small = SmoothFont.regular(6.5f);
                small.drawRight(String.valueOf(t.matches(q)), x + w - 12, small.centerY(ry, ITEM_H) - 0.5f, GlassTheme.accent());
            }
            this.hit(x + 6, ry, w - 12, ITEM_H, button -> {
                feel.pulse();
                this.open(t);
            });
        }
        if (shown.isEmpty()) {
            SmoothFont.regular(8).drawCentered("没有找到相关内容", x + w / 2, listY + 16, GlassTheme.FAINT);
        }
        Draw.popClip();
    }

    private void open(GuideContent.Topic t) {
        if (t == this.topic) return;
        UiSound.SELECT.play();
        this.topic = t;
        this.reveal.snap(0);
        this.layoutKey = "";
        String q = this.query();
        this.scroll.jump(0);
        this.pendingFind = q;
    }

    /** After a topic is opened from a search, scroll to the first block that matches (once laid out). */
    private String pendingFind = "";

    private void bookGlyph(float cx, float cy, int color) {
        // An open book: two pages meeting at the spine.
        Draw.polyline(1.1f, color, cx, cy - 3.6f, cx - 2.8f, cy - 4.8f, cx - 5.4f, cy - 4.4f, cx - 5.4f, cy + 3.8f,
                cx - 2.8f, cy + 3.4f, cx, cy + 4.6f, cx, cy - 3.6f);
        Draw.polyline(1.1f, color, cx, cy - 3.6f, cx + 2.8f, cy - 4.8f, cx + 5.4f, cy - 4.4f, cx + 5.4f, cy + 3.8f,
                cx + 2.8f, cy + 3.4f, cx, cy + 4.6f);
    }

    private void searchGlyph(float cx, float cy) {
        int n = 18;
        float[] pts = new float[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            double ang = Math.PI * 2 * i / n;
            pts[i * 2] = cx - 1 + (float) Math.cos(ang) * 3.2f;
            pts[i * 2 + 1] = cy - 1 + (float) Math.sin(ang) * 3.2f;
        }
        Draw.polyline(1.1f, 0xaaffffff, pts);
        Draw.polyline(1.3f, 0xaaffffff, cx + 1.4f, cy + 1.4f, cx + 3.8f, cy + 3.8f);
    }

    private void drawEdit(SmoothFont font, float x, float y, float w, float h) {
        String text = this.search.toString();
        float full = font.width(text), caretAt = font.width(text.substring(0, Math.min(this.search.caret(), text.length())));
        float room = Math.max(1, w - 2), off = 0;
        if (full > room) {
            off = this.searchScroll;
            if (caretAt - off > room) off = caretAt - room;
            if (caretAt - off < 0) off = caretAt;
            off = Math.max(0, Math.min(off, full - room));
        }
        this.searchScroll = off;
        float start = x - off;
        int accent = GlassTheme.accent();
        Draw.pushClip(x - 1, y, w + 2, h);
        if (this.searchFocused && this.search.hasSelection()) {
            float from = font.width(text.substring(0, this.search.selectionStart())), to = font.width(text.substring(0, this.search.selectionEnd()));
            Draw.rect(start + from, y + 5, to - from, h - 10, 2, Draw.alpha(accent, 0.38f));
        }
        if (!text.isEmpty()) font.draw(text, start, font.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        Draw.popClip();
        if (this.searchFocused) {
            float cx = Math.max(x, Math.min(x + w, start + caretAt));
            if (System.currentTimeMillis() % 1000 < 550) Draw.rect(cx, y + 5.5f, 0.8f, h - 11, 0, accent);
            Draw.reportCaret(cx, y + 4, h - 8);
        }
    }

    // ================================================================ article

    private static final int TITLE = 0, SUMMARY = 1, RULE = 2, HEADING = 3, TEXT = 4, BULLET = 5, STEP = 6,
            CALLOUT = 7, ROW = 8, COMMAND = 9, BUTTONS = 10, NEXT = 11;

    /** One laid-out piece of the article, at {@code y} below the content top. */
    private static final class Item {
        int kind;
        float y, h, indent;
        List<TextWrap.Line> lines, lines2;
        TextWrap.Fonts fonts, fonts2;
        GuideContent.Block block;
        List<GuideContent.Block> buttons;
        float split;
        boolean match;
        int blockIndex = -1, stripe;
    }

    private TextWrap.Fonts body() {
        return new TextWrap.Fonts(SmoothFont.regular(8.5f), SmoothFont.bold(8.5f), SmoothFont.regular(7.5f), SmoothFont.bold(7), 3.5f, 1.5f);
    }

    private TextWrap.Fonts heading() {
        SmoothFont h = SmoothFont.bold(11);
        return new TextWrap.Fonts(h, h, SmoothFont.regular(9), SmoothFont.bold(8.5f), 3.5f, 1.5f);
    }

    private TextWrap.Fonts small() {
        return new TextWrap.Fonts(SmoothFont.regular(7.5f), SmoothFont.bold(7.5f), SmoothFont.regular(7), SmoothFont.bold(6.5f), 3, 1.2f);
    }

    /** Placeholders: {@code {version}} and {@code {bind:Module}} (its key, live). */
    private static String resolve(String name) {
        if (name.equals("version")) return Myau.version;
        if (name.startsWith("bind:")) {
            String module = name.substring(5);
            if (Myau.moduleManager == null) return null;
            for (Module m : Myau.moduleManager.modules.values()) {
                if (!m.getName().equalsIgnoreCase(module)) continue;
                int key = m.getKey();
                return key == 0 ? "未绑定" : KeyBindUtil.getKeyName(key);
            }
            return null;
        }
        return null;
    }

    private List<TextWrap.Line> wrap(String text, TextWrap.Fonts fonts, float width) {
        return TextWrap.wrap(TextWrap.parse(text, GuideScreen::resolve), fonts, Math.max(40, width));
    }

    /** Lays the article out for a text column {@code w} wide (cached until something changes). */
    private void build(float w) {
        String q = this.query();
        StringBuilder binds = new StringBuilder();
        GuiModule gui = guiModule();
        if (gui != null) binds.append(gui.getKey());
        String key = this.topic.id + "|" + (int) w + "|" + q + "|" + binds + "|" + Draw.scale();
        if (key.equals(this.layoutKey)) return;
        this.layoutKey = key;
        this.items.clear();
        TextWrap.Fonts body = this.body(), small = this.small();
        float y = 0;

        Item title = new Item();
        title.kind = TITLE;
        title.fonts = new TextWrap.Fonts(SmoothFont.bold(17), SmoothFont.bold(17), SmoothFont.regular(12), SmoothFont.bold(11), 4, 2);
        title.lines = this.wrap(this.topic.title, title.fonts, w);
        title.y = y;
        title.h = title.lines.size() * 24;
        this.items.add(title);
        y += title.h + 2;
        if (!this.topic.summary.isEmpty()) {
            Item sum = new Item();
            sum.kind = SUMMARY;
            sum.fonts = new TextWrap.Fonts(SmoothFont.regular(9), SmoothFont.bold(9), SmoothFont.regular(8), SmoothFont.bold(7.5f), 3.5f, 1.5f);
            sum.lines = this.wrap(this.topic.summary, sum.fonts, w);
            sum.y = y;
            sum.h = sum.lines.size() * 14;
            this.items.add(sum);
            y += sum.h;
        }
        y += 10;
        Item rule = new Item();
        rule.kind = RULE;
        rule.y = y;
        rule.h = 1;
        this.items.add(rule);
        y += 16;

        List<GuideContent.Block> blocks = this.topic.blocks;
        int stripe = 0;
        for (int i = 0; i < blocks.size(); i++) {
            GuideContent.Block b = blocks.get(i);
            GuideContent.Block next = i + 1 < blocks.size() ? blocks.get(i + 1) : null;
            boolean match = !q.isEmpty() && GuideContent.plain(b.text + " " + b.extra).toLowerCase(Locale.ROOT).contains(q);
            Item it = new Item();
            it.block = b;
            it.blockIndex = i;
            it.match = match;
            it.y = y;
            switch (b.kind) {
                case HEADING: {
                    if (i > 0) {
                        y += 10;
                        it.y = y;
                    }
                    it.kind = HEADING;
                    it.fonts = this.heading();
                    it.lines = this.wrap(b.text, it.fonts, w);
                    it.h = it.lines.size() * LH_HEADING;
                    y += it.h + 6;
                    break;
                }
                case PARAGRAPH: {
                    it.kind = TEXT;
                    it.fonts = body;
                    it.lines = this.wrap(b.text, body, w);
                    it.h = it.lines.size() * LH;
                    y += it.h + 8;
                    break;
                }
                case BULLET:
                case STEP: {
                    boolean step = b.kind == GuideContent.Kind.STEP;
                    it.kind = step ? STEP : BULLET;
                    it.indent = step ? 20 : 12;
                    it.fonts = body;
                    it.lines = this.wrap(b.text, body, w - it.indent);
                    it.h = it.lines.size() * LH;
                    y += it.h + (next != null && next.kind == b.kind ? 4 : 10);
                    break;
                }
                case TIP:
                case WARN: {
                    it.kind = CALLOUT;
                    it.fonts = body;
                    it.lines = this.wrap(b.text, body, w - 30);
                    it.h = it.lines.size() * LH + 16;
                    y += it.h + 10;
                    break;
                }
                case ROW: {
                    // A run of rows is one table: the left column fits its widest entry (up to 40%).
                    int end = i;
                    float left = 0;
                    while (end < blocks.size() && blocks.get(end).kind == GuideContent.Kind.ROW) {
                        for (TextWrap.Line l : this.wrap(blocks.get(end).text, body, w * 0.4f)) left = Math.max(left, l.width);
                        end++;
                    }
                    float split = Math.min(w * 0.4f, left + 18);
                    stripe = 0;
                    for (int k = i; k < end; k++) {
                        GuideContent.Block rb = blocks.get(k);
                        Item row = k == i ? it : new Item();
                        row.block = rb;
                        row.blockIndex = k;
                        row.match = !q.isEmpty() && GuideContent.plain(rb.text + " " + rb.extra).toLowerCase(Locale.ROOT).contains(q);
                        row.kind = ROW;
                        row.split = split;
                        row.stripe = stripe++;
                        row.fonts = body;
                        row.lines = this.wrap(rb.text, body, split - 14);
                        row.lines2 = this.wrap(rb.extra, body, w - split - 12);
                        row.y = y;
                        row.h = Math.max(row.lines.size(), row.lines2.size()) * LH + 10;
                        y += row.h;
                        if (k != i) this.items.add(row);
                    }
                    // The first row is added below with the others' order kept: insert it first.
                    this.items.add(this.items.size() - (end - i - 1), it);
                    y += 12;
                    i = end - 1;
                    continue;
                }
                case COMMAND: {
                    it.kind = COMMAND;
                    it.fonts = body;
                    it.fonts2 = small;
                    it.lines2 = b.extra.isEmpty() ? new ArrayList<>() : this.wrap(b.extra, small, w - 24);
                    it.h = 24 + (it.lines2.isEmpty() ? 0 : it.lines2.size() * 11 + 2);
                    y += it.h + 6;
                    if (next == null || next.kind != GuideContent.Kind.COMMAND) y += 6;
                    break;
                }
                case BUTTON: {
                    // A run of buttons is one row of pills.
                    it.kind = BUTTONS;
                    it.buttons = new ArrayList<>();
                    int end = i;
                    while (end < blocks.size() && blocks.get(end).kind == GuideContent.Kind.BUTTON) {
                        if (buttonLabel(blocks.get(end)) != null) it.buttons.add(blocks.get(end));
                        end++;
                    }
                    i = end - 1;
                    if (it.buttons.isEmpty()) continue;
                    it.h = this.buttonRows(it.buttons, w) * 28 - 6;
                    y += it.h + 14;
                    break;
                }
                default:
                    continue;
            }
            this.items.add(it);
        }

        int index = this.topics.indexOf(this.topic);
        if (index >= 0 && index + 1 < this.topics.size()) {
            y += 8;
            Item nextItem = new Item();
            nextItem.kind = NEXT;
            nextItem.y = y;
            nextItem.h = 42;
            this.items.add(nextItem);
            y += 42;
        }
        this.contentH = y + 24;

        if (!this.pendingFind.isEmpty()) {
            for (Item it : this.items) {
                if (it.match) {
                    this.scroll.limit(this.contentH - this.viewH());
                    this.scroll.jump(it.y - 24);
                    break;
                }
            }
            this.pendingFind = "";
        }
    }

    private float viewH() {
        return this.mainH - 44;
    }

    private int buttonRows(List<GuideContent.Block> buttons, float w) {
        SmoothFont f = SmoothFont.bold(8);
        float x = 0;
        int rows = 1;
        for (GuideContent.Block b : buttons) {
            float bw = f.width(buttonLabel(b)) + 26;
            if (x > 0 && x + bw > w) {
                rows++;
                x = 0;
            }
            x += bw + 8;
        }
        return rows;
    }

    private void drawArticle() {
        float x = this.mainX, y = this.mainY, w = this.mainW, h = this.mainH;
        // Top bar: where we are, and the way out.
        SmoothFont crumbs = SmoothFont.regular(7.5f);
        crumbs.draw(this.topic.group + "  /  " + this.topic.title, x + PAD_X - 6, y + 12, GlassTheme.FAINT);
        this.closeButton(x + w - 26, y + 6);

        float viewY = y + 34, viewH = this.viewH();
        float textW = Math.min(TEXT_MAX, w - PAD_X * 2);
        float tx = x + PAD_X - 6;
        this.build(textW);

        this.scroll.limit(this.contentH - viewH);
        int d = this.takeWheel(x, viewY, w, viewH);
        if (d != 0) this.scroll.nudge(-d / 120f * 46);
        this.scroll.update(this.dt);
        float r = this.reveal.update(1, this.dt);

        Draw.pushClip(x, viewY, w, viewH);
        Draw.pushAlpha(Anim.outCubic(r));
        float top = viewY + 6 - this.scroll.value + (1 - Anim.outCubic(r)) * 10;
        for (Item it : this.items) {
            float iy = top + it.y;
            if (iy + it.h < viewY - 20 || iy > viewY + viewH + 20) continue;
            this.drawItem(it, tx, iy, textW);
        }
        Draw.popAlpha();
        Draw.popClip();

        // Soft edges where the text scrolls under the bar, and a thin thumb.
        if (this.scroll.value > 1) Draw.gradientV(x + 6, viewY, w - 12, 14, 0, 0x22000000, 0x00000000);
        if (this.scroll.max > 1) {
            float th = Math.max(24, viewH * viewH / (viewH + this.scroll.max));
            float ty = viewY + (viewH - th) * (this.scroll.value / this.scroll.max);
            Draw.rect(x + w - 6, ty, 2.5f, th, 1.25f, 0x40ffffff);
        }
    }

    private void closeButton(float x, float y) {
        float s = 20;
        Interaction feel = this.feel("close");
        boolean hov = this.over(x, y, s, s);
        feel.update(hov, hov && this.mouseDown, this.dt);
        if (feel.hot > 0.01f) Draw.circle(x + s / 2, y + s / 2, s / 2, Draw.alpha(0xffffff, 0.1f * feel.hot));
        int c = Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, feel.hot);
        float cx = x + s / 2, cy = y + s / 2, k = 3.6f;
        Draw.polyline(1.2f, c, cx - k, cy - k, cx + k, cy + k);
        Draw.polyline(1.2f, c, cx - k, cy + k, cx + k, cy - k);
        this.hit(x, y, s, s, button -> this.close());
    }

    private void drawItem(Item it, float x, float y, float w) {
        int accent = GlassTheme.accent();
        if (it.match && it.kind != ROW) {
            Draw.rect(x - 8, y - 3, w + 16, it.h + 6, 6, Draw.alpha(accent, 0.10f));
        }
        switch (it.kind) {
            case TITLE:
                this.drawLines(it.lines, it.fonts, x, y, 24, GlassTheme.TEXT, true);
                return;
            case SUMMARY:
                this.drawLines(it.lines, it.fonts, x, y, 14, GlassTheme.MUTED, false);
                return;
            case RULE:
                Draw.gradientH(x, y, w, 0.6f, 0, Draw.alpha(accent, 0.55f), 0x00ffffff);
                return;
            case HEADING:
                Draw.rect(x - 9, y + 3, 2.5f, LH_HEADING - 6, 1.25f, accent);
                this.drawLines(it.lines, it.fonts, x, y, LH_HEADING, GlassTheme.TEXT, false);
                return;
            case TEXT:
                this.drawLines(it.lines, it.fonts, x, y, LH, 0xffdfe2e8, false);
                return;
            case BULLET:
                Draw.circle(x + 3.5f, y + LH / 2, 1.6f, Draw.alpha(accent, 0.9f));
                this.drawLines(it.lines, it.fonts, x + it.indent, y, LH, 0xffdfe2e8, false);
                return;
            case STEP: {
                float cx = x + 6.5f, cy = y + LH / 2;
                Draw.circle(cx, cy, 6, Draw.alpha(accent, 0.22f));
                SmoothFont n = SmoothFont.bold(6.5f);
                n.drawCentered(String.valueOf(it.block.number), cx, n.centerY(cy - 6, 12) - 0.3f, GlassTheme.TEXT);
                this.drawLines(it.lines, it.fonts, x + it.indent, y, LH, 0xffdfe2e8, false);
                return;
            }
            case CALLOUT: {
                boolean warn = it.block.kind == GuideContent.Kind.WARN;
                int tone = warn ? GlassTheme.WARN : accent;
                Draw.rect(x - 4, y, w + 8, it.h, 7, Draw.alpha(tone, 0.09f));
                Draw.outline(x - 4, y, w + 8, it.h, 7, 0.6f, Draw.alpha(tone, 0.28f), Draw.alpha(tone, 0.12f));
                Draw.rect(x + 3, y + 7, 2.5f, it.h - 14, 1.25f, tone);
                this.drawLines(it.lines, it.fonts, x + 16, y + 8, LH, 0xffe9ebef, false);
                return;
            }
            case ROW: {
                if (it.stripe % 2 == 0) Draw.rect(x - 4, y, w + 8, it.h, 5, it.match ? Draw.alpha(accent, 0.12f) : 0x0cffffff);
                else if (it.match) Draw.rect(x - 4, y, w + 8, it.h, 5, Draw.alpha(accent, 0.12f));
                this.drawLines(it.lines, it.fonts, x + 6, y + 5, LH, GlassTheme.TEXT, false);
                this.drawLines(it.lines2, it.fonts, x + it.split, y + 5, LH, 0xffc9cdd5, false);
                return;
            }
            case COMMAND: {
                this.drawCommand(it, x, y, w);
                return;
            }
            case BUTTONS: {
                this.drawButtons(it, x, y, w);
                return;
            }
            case NEXT: {
                int index = this.topics.indexOf(this.topic);
                GuideContent.Topic next = this.topics.get(index + 1);
                Interaction feel = this.feel("next");
                boolean hov = this.over(x - 4, y, w + 8, it.h);
                feel.update(hov, hov && this.mouseDown, this.dt);
                GlassTheme.card(x - 4, y, w + 8, it.h, GlassTheme.R_CARD, 0.3f + 0.4f * feel.hot);
                SmoothFont s = SmoothFont.regular(7), b = SmoothFont.bold(9.5f);
                s.draw("下一篇", x + 8, y + 9, GlassTheme.FAINT);
                b.draw(next.title, x + 8, y + 20, GlassTheme.TEXT);
                Draw.chevron(x + w - 10 + 2 * feel.hot, y + it.h / 2, 4, 0, 1.3f, Draw.mix(GlassTheme.MUTED, accent, feel.hot));
                this.hit(x - 4, y, w + 8, it.h, button -> {
                    feel.pulse();
                    this.open(next);
                });
                return;
            }
            default:
        }
    }

    private void drawLines(List<TextWrap.Line> lines, TextWrap.Fonts fonts, float x, float y, float lh, int color, boolean shadow) {
        int accent = GlassTheme.accent();
        for (int i = 0; i < lines.size(); i++) {
            float ly = y + i * lh;
            for (TextWrap.Piece p : lines.get(i).pieces) {
                SmoothFont f = fonts.of(p.style);
                switch (p.style) {
                    case CODE: {
                        float cx = x + p.x + fonts.margin, cw = p.w - fonts.margin * 2, ch = f.height() + 2;
                        float cy = ly + (lh - ch) / 2;
                        Draw.rect(cx, cy, cw, ch, 3, Draw.alpha(accent, 0.16f));
                        f.draw(p.text, cx + fonts.pad, f.centerY(cy, ch) - 0.3f, Draw.mix(accent, 0xffffffff, 0.55f));
                        break;
                    }
                    case KEY: {
                        float cx = x + p.x + fonts.margin, cw = p.w - fonts.margin * 2, ch = f.height() + 3;
                        float cy = ly + (lh - ch) / 2;
                        Draw.rect(cx, cy + 0.8f, cw, ch, 3, 0x40000000);
                        Draw.rect(cx, cy, cw, ch, 3, 0x26ffffff);
                        Draw.outline(cx, cy, cw, ch, 3, 0.6f, 0x50ffffff, 0x18ffffff);
                        f.draw(p.text, cx + fonts.pad, f.centerY(cy, ch) - 0.3f, GlassTheme.TEXT);
                        break;
                    }
                    case BOLD:
                        f.draw(p.text, x + p.x, f.centerY(ly, lh) - 0.5f, GlassTheme.TEXT);
                        break;
                    default:
                        if (shadow) f.drawShadowed(p.text, x + p.x, f.centerY(ly, lh) - 0.5f, color);
                        else f.draw(p.text, x + p.x, f.centerY(ly, lh) - 0.5f, color);
                }
            }
        }
    }

    private void drawCommand(Item it, float x, float y, float w) {
        GuideContent.Block b = it.block;
        String cmd = b.text;
        Interaction feel = this.feel("cmd:" + it.blockIndex + ":" + this.topic.id);
        boolean hov = this.over(x - 4, y, w + 8, it.h);
        feel.update(hov, hov && this.mouseDown, this.dt);
        int accent = GlassTheme.accent();
        Draw.rect(x - 4, y, w + 8, it.h, 6, Draw.alpha(0x000000, 0.28f + 0.06f * feel.hot));
        Draw.outline(x - 4, y, w + 8, it.h, 6, 0.6f, Draw.alpha(0xffffff, 0.10f + 0.12f * feel.hot), Draw.alpha(0xffffff, 0.04f));
        SmoothFont prompt = SmoothFont.bold(8), code = SmoothFont.regular(8.5f);
        prompt.draw("›", x + 4, prompt.centerY(y, 24) - 0.5f, Draw.alpha(accent, 0.9f));
        String shown = code.trim(cmd, w - 70);
        code.draw(shown, x + 13, code.centerY(y, 24) - 0.5f, Draw.mix(accent, 0xffffffff, 0.6f));
        SmoothFont tiny = SmoothFont.regular(6.5f);
        tiny.drawRight(hov ? "点击复制" : "", x + w - 2, tiny.centerY(y, 24) - 0.5f, GlassTheme.FAINT);
        if (!it.lines2.isEmpty()) this.drawLines(it.lines2, it.fonts2, x + 13, y + 21, 11, GlassTheme.MUTED, false);
        this.hit(x - 4, y, w + 8, it.h, button -> {
            feel.pulse();
            GuiScreen.setClipboardString(cmd);
            UiSound.SUCCESS.play();
            this.toast("已复制：" + cmd);
        });
    }

    private void drawButtons(Item it, float x, float y, float w) {
        SmoothFont f = SmoothFont.bold(8);
        float bx = x, by = y;
        for (GuideContent.Block b : it.buttons) {
            String label = buttonLabel(b);
            if (label == null) continue;
            float bw = f.width(label) + 26, bh = 22;
            if (bx > x && bx + bw > x + w) {
                bx = x;
                by += 28;
            }
            Interaction feel = this.feel("btn:" + b.extra);
            boolean hov = this.over(bx, by, bw, bh);
            feel.update(hov, hov && this.mouseDown, this.dt);
            float s = feel.scale(0.05f);
            int accent = GlassTheme.accent();
            Draw.pushTransform(bx + bw / 2, by + bh / 2, s, 0, 0);
            Draw.glow(bx, by, bw, bh, bh / 2, 8, Draw.alpha(accent, 0.12f + 0.18f * feel.hot));
            Draw.rect(bx, by, bw, bh, bh / 2, Draw.alpha(accent, 0.30f + 0.16f * feel.hot));
            Draw.outline(bx, by, bw, bh, bh / 2, 0.7f, Draw.alpha(0xffffff, 0.35f), Draw.alpha(0xffffff, 0.08f));
            f.drawCentered(label, bx + bw / 2, f.centerY(by, bh) - 0.5f, GlassTheme.TEXT);
            Draw.popTransform();
            String action = b.extra;
            this.hit(bx, by, bw, bh, button -> {
                feel.pulse();
                UiSound.CLICK.play();
                this.act(action);
            });
            bx += bw + 8;
        }
    }

    // ================================================================ actions

    private static GuiModule guiModule() {
        return Myau.moduleManager == null ? null : (GuiModule) Myau.moduleManager.modules.get(GuiModule.class);
    }

    /** HUD's main menu background setting, looked up by name (null when this build has none). */
    private static ModeProperty backgroundSetting() {
        if (Myau.propertyManager == null) return null;
        List<Property<?>> list = Myau.propertyManager.properties.get(HUD.class);
        if (list == null) return null;
        for (Property<?> p : list) if (p instanceof ModeProperty && p.getName().equals("menu-background")) return (ModeProperty) p;
        return null;
    }

    private static String backgroundName(String mode) {
        switch (mode) {
            case "AURORA":
                return "流光渐变";
            case "NIGHT":
                return "方块夜景";
            case "PANORAMA":
                return "模糊原版全景";
            case "MINIMAL":
                return "极简暗色";
            case "PLASMA":
                return "等离子流光";
            default:
                return mode;
        }
    }

    /** The label a button shows now, or null when its action is not available here. */
    private static String buttonLabel(GuideContent.Block b) {
        if (b.extra.equals("background")) {
            ModeProperty bg = backgroundSetting();
            return bg == null ? null : b.text + "（当前：" + backgroundName(bg.getModeString()) + "）";
        }
        if (b.extra.equals("clickgui") && guiModule() == null) return null;
        return b.text;
    }

    private void act(String action) {
        switch (action) {
            case "clickgui": {
                GuiModule gui = guiModule();
                if (gui != null) mc.displayGuiScreen(gui.screen());
                return;
            }
            case "music":
                mc.displayGuiScreen(new myau.music.ui.MusicScreen());
                return;
            case "configs": {
                File dir = new File("./config/Myau/");
                // Desktop.open can take a moment: keep it off the render thread.
                Thread t = new Thread(() -> {
                    try {
                        Desktop.getDesktop().open(dir);
                    } catch (Exception e) {
                        Myau.LOGGER.warn("[Guide] cannot open {}", dir, e);
                    }
                }, "BetterMyau-OpenFolder");
                t.setDaemon(true);
                t.start();
                this.toast("已打开配置文件夹");
                return;
            }
            case "background": {
                ModeProperty bg = backgroundSetting();
                if (bg == null) return;
                bg.nextMode();
                this.layoutKey = "";
                this.toast("主菜单背景：" + backgroundName(bg.getModeString()));
                return;
            }
            default:
        }
    }

    // ================================================================ toast

    private void toast(String text) {
        this.toast = text;
        this.toastAt = System.currentTimeMillis();
    }

    private void drawToast() {
        long age = System.currentTimeMillis() - this.toastAt;
        if (this.toast.isEmpty() || age > 2200) return;
        float a = age < 180 ? age / 180f : age > 1900 ? (2200 - age) / 300f : 1;
        Draw.pushAlpha(Draw.clamp01(a));
        GlassTheme.toast(this.toast, this.mainX + this.mainW / 2, this.mainY + this.mainH - 30);
        Draw.popAlpha();
    }

    // ================================================================ hits and pointer

    private Interaction feel(String key) {
        Interaction i = this.feels.get(key);
        if (i == null) {
            i = new Interaction();
            this.feels.put(key, i);
        }
        return i;
    }

    private boolean interactive() {
        return this.openTime > 0.12f;
    }

    private boolean over(float x, float y, float w, float h) {
        if (!this.interactive()) return false;
        return this.mx >= x && this.mx < x + w && this.my >= y && this.my < y + h && Draw.insideClip(this.mx, this.my);
    }

    private void hit(float x, float y, float w, float h, Action action) {
        this.clipped[0] = x;
        this.clipped[1] = y;
        this.clipped[2] = x + w;
        this.clipped[3] = y + h;
        if (!Draw.clipTo(this.clipped)) return;
        Hit hit;
        if (this.hitCount < this.hits.size()) {
            hit = this.hits.get(this.hitCount);
        } else {
            hit = new Hit();
            this.hits.add(hit);
        }
        this.hitCount++;
        hit.x0 = this.clipped[0];
        hit.y0 = this.clipped[1];
        hit.x1 = this.clipped[2];
        hit.y1 = this.clipped[3];
        hit.action = action;
    }

    private int takeWheel(float x, float y, float w, float h) {
        if (this.wheel == 0 || !this.over(x, y, w, h)) return 0;
        int d = this.wheel;
        this.wheel = 0;
        return d;
    }

    // ================================================================ input

    @Override
    public void handleMouseInput() {
        float x = Mouse.getEventX() / this.ui, y = (mc.displayHeight - Mouse.getEventY() - 1) / this.ui;
        int button = Mouse.getEventButton();
        if (Mouse.getEventButtonState() && button >= 0) this.press(x, y, button);
        int d = Mouse.getEventDWheel();
        if (d != 0) this.wheel += d;
    }

    private void press(float x, float y, int button) {
        if (!this.interactive()) return;
        this.mx = x;
        this.my = y;
        Hit hit = null;
        for (int i = this.hitCount - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (x >= h.x0 && x < h.x1 && y >= h.y0 && y < h.y1) {
                hit = h;
                break;
            }
        }
        boolean inSearch = x >= this.sideX + 8 && x < this.sideX + this.sideW - 8 && y >= this.sideY + 44 && y < this.sideY + 66;
        if (!inSearch) this.searchFocused = false;
        if (hit != null) hit.action.run(button);
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (!this.search.isEmpty()) {
                this.search.clear();
                this.layoutKey = "";
            } else if (this.searchFocused) {
                this.searchFocused = false;
            } else {
                this.close();
            }
            return;
        }
        if (isCtrlKeyDown() && key == Keyboard.KEY_F) {
            this.searchFocused = true;
            this.search.caretToEnd();
            return;
        }
        if (this.searchFocused) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                List<GuideContent.Topic> shown = this.visibleTopics();
                if (!shown.isEmpty() && !shown.contains(this.topic)) this.open(shown.get(0));
                else if (!shown.isEmpty()) this.pendingFind = this.query();
                this.layoutKey = "";
                this.searchFocused = false;
                return;
            }
            if (key != Keyboard.KEY_UP && key != Keyboard.KEY_DOWN && key != Keyboard.KEY_PRIOR && key != Keyboard.KEY_NEXT) {
                String before = this.search.toString();
                if (this.search.key(typed, key)) {
                    if (!before.equals(this.search.toString())) this.searchChanged();
                    return;
                }
            }
        }
        float page = Math.max(40, this.viewH() - 40);
        switch (key) {
            case Keyboard.KEY_UP:
                this.scroll.nudge(-40);
                return;
            case Keyboard.KEY_DOWN:
                this.scroll.nudge(40);
                return;
            case Keyboard.KEY_PRIOR:
                this.scroll.nudge(-page);
                return;
            case Keyboard.KEY_NEXT:
            case Keyboard.KEY_SPACE:
                this.scroll.nudge(page);
                return;
            case Keyboard.KEY_HOME:
                this.scroll.nudge(-this.scroll.max);
                return;
            case Keyboard.KEY_END:
                this.scroll.nudge(this.scroll.max);
                return;
            case Keyboard.KEY_LEFT:
            case Keyboard.KEY_RIGHT: {
                List<GuideContent.Topic> shown = this.visibleTopics();
                int i = shown.indexOf(this.topic) + (key == Keyboard.KEY_LEFT ? -1 : 1);
                if (i >= 0 && i < shown.size()) this.open(shown.get(i));
                return;
            }
            default:
                break;
        }
        // Typing anywhere searches.
        if (!isCtrlKeyDown() && (typed > ' ' && typed != 127 || key == 0 && typed >= ' ')) {
            this.searchFocused = true;
            this.search.caretToEnd();
            String before = this.search.toString();
            this.search.key(typed, key);
            if (!before.equals(this.search.toString())) this.searchChanged();
        }
    }

    private void searchChanged() {
        this.layoutKey = "";
        this.sideTarget = 0;
        String q = this.query();
        if (q.isEmpty()) return;
        // Follow the search: stay on this topic if it matches, otherwise show the best one.
        GuideContent.Topic best = null;
        int most = 0;
        for (GuideContent.Topic t : this.topics) {
            int n = t.matches(q);
            if (n > most) {
                most = n;
                best = t;
            }
        }
        if (this.topic.matches(q) > 0) {
            this.pendingFind = q;
        } else if (best != null) {
            this.open(best);
        }
    }

    // ================================================================ scrolling

    /** A scroll offset that eases towards where the wheel put it. */
    private static final class Scroller {
        float value, target, max;

        void limit(float newMax) {
            this.max = Math.max(0, newMax);
            this.target = Math.max(0, Math.min(this.max, this.target));
        }

        void update(float dt) {
            this.value += (this.target - this.value) * (1 - (float) Math.exp(-16 * dt));
            if (Math.abs(this.target - this.value) < 0.01f) this.value = this.target;
            if (this.value > this.max) this.value = Math.max(this.max, this.value - (this.value - this.max) * 0.5f);
        }

        void nudge(float amount) {
            this.target = Math.max(0, Math.min(this.max, this.target + amount));
        }

        void jump(float to) {
            this.target = this.value = Math.max(0, Math.min(this.max, to));
        }
    }
}
