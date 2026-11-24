package myau.ui.glass;

import myau.ui.GlassTextField;
import myau.ui.GlassTheme;
import myau.via.ViaSettings;
import myau.via.ViaUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the version to join with: for all servers, or for one server (which can also follow the
 * global choice). Automatic and plain 1.8 come first, then every version ViaVersion reaches from
 * 1.8, newest first, grouped into releases, pre-1.7 versions and specials, with a search box.
 * Picking saves at once and goes back.
 */
public class GlassVersionPicker extends GuiScreen implements myau.util.ime.TextInputScreen {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float MIN_W = 420, MIN_H = 300, PANEL_W = 380, ROW_H = 24, HEADER_H = 22, PAD = 16;

    private final GuiScreen parent;
    /** The server address this picks for, or null for the global choice. */
    private final String address;
    private final String serverName;

    private float ui = 2, zoom = 1, sw = MIN_W, sh = MIN_H, mx, my, openTime;
    private float px, py, pw, ph, listY, listH, fieldY;
    private float scroll, scrollTarget, contentH;
    private int wheel;
    private GlassTextField search;
    private final Anim.Smooth searchFocus = new Anim.Smooth(0, 16);
    private final Map<String, Interaction> feels = new HashMap<>();

    private static final class Hit {
        float x0, y0, x1, y1;
        Runnable action;
    }

    private final List<Hit> hits = new ArrayList<>();
    private int hitCount;
    private final float[] clipped = new float[4];

    /** A row: a group header (choice null, header true) or something to pick. */
    private static final class Row {
        final boolean header;
        final String choice, label, detail;
        final boolean follow;

        Row(boolean header, String choice, String label, String detail, boolean follow) {
            this.header = header;
            this.choice = choice;
            this.label = label;
            this.detail = detail;
            this.follow = follow;
        }
    }

    /** For all servers. */
    public GlassVersionPicker(GuiScreen parent) {
        this(parent, null, null);
    }

    /** For one server ({@code address} as saved in the server list). */
    public GlassVersionPicker(GuiScreen parent, String address, String serverName) {
        this.parent = parent;
        this.address = address;
        this.serverName = serverName;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        measure();
        layout();
        String text = search == null ? "" : search.getText();
        search = new GlassTextField(0, fontRendererObj, Math.round(px + PAD + 22), Math.round(fieldY + 7), Math.round(pw - PAD * 2 - 24), 12);
        search.setMaxStringLength(32);
        search.setText(text);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        if (search != null) search.updateCursorCounter();
    }

    private void measure() {
        float gui = new ScaledResolution(mc).getScaleFactor();
        float scale = gui;
        if (mc.displayWidth / scale < MIN_W || mc.displayHeight / scale < MIN_H) {
            scale = Math.min(mc.displayWidth / MIN_W, mc.displayHeight / MIN_H);
            scale = Math.max(0.5f, (float) Math.floor(scale * 4) / 4f);
        }
        ui = scale;
        zoom = ui / gui;
        sw = mc.displayWidth / ui;
        sh = mc.displayHeight / ui;
    }

    private void layout() {
        pw = Math.min(PANEL_W, sw - 40);
        ph = Math.min(sh - 40, 460);
        px = (sw - pw) / 2;
        py = (sh - ph) / 2;
        fieldY = py + 52;
        listY = fieldY + 26 + 10;
        listH = py + ph - 14 - listY;
    }

    // ================================================================ rows

    private String current() {
        return address == null ? ViaSettings.global() : ViaSettings.override(address);
    }

    private List<Row> rows() {
        String query = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        if (query.isEmpty()) {
            if (address != null) {
                rows.add(new Row(false, null, "跟随全局", "现在是：" + ViaUi.globalLabel(), true));
            }
            rows.add(new Row(false, ViaSettings.AUTO, "自动检测（推荐）", "先问服务器：接受 1.8 就原生连接，否则按它的版本转换", false));
            rows.add(new Row(false, ViaSettings.NATIVE, "1.8.x 原生", "不做任何转换", false));
        }
        for (Map.Entry<String, List<ViaUi.Choice>> group : ViaUi.versionGroups().entrySet()) {
            List<Row> matched = new ArrayList<>();
            for (ViaUi.Choice choice : group.getValue()) {
                if (!query.isEmpty() && !choice.label.toLowerCase(Locale.ROOT).contains(query)) continue;
                matched.add(new Row(false, choice.choice, choice.label, choice.detail, false));
            }
            if (matched.isEmpty()) continue;
            rows.add(new Row(true, null, group.getKey(), null, false));
            rows.addAll(matched);
        }
        return rows;
    }

    private boolean isCurrent(Row row) {
        String current = current();
        if (row.follow) return current == null;
        return current != null && current.equalsIgnoreCase(row.choice);
    }

    private void pick(Row row) {
        if (address == null) ViaUi.setGlobal(row.choice);
        else ViaUi.setOverride(address, row.follow ? null : row.choice);
        UiSound.CLICK.play();
        mc.displayGuiScreen(parent);
    }

    // ================================================================ frame

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float dt = Anim.dt();
        openTime += dt;
        measure();
        layout();
        if (search.xPosition != Math.round(px + PAD + 22) || search.yPosition != Math.round(fieldY + 7)
                || search.getWidth() != Math.round(pw - PAD * 2 - 24)) {
            initGui();
        }
        mx = Mouse.getX() / ui;
        my = (mc.displayHeight - Mouse.getY() - 1) / ui;

        GlassTheme.screenBackdrop(width, height);
        Draw.pushTransform(0, 0, zoom, 0, 0);
        Draw.beginBatch();
        try {
            hitCount = 0;
            applyWheel();
            drawPanel(dt);
        } finally {
            Draw.endBatch();
            Draw.popTransform();
        }
    }

    private void drawPanel(float dt) {
        float reveal = Anim.outCubic(openTime / 0.28f);
        Draw.pushAlpha(reveal);
        Draw.pushTransform(px + pw / 2, py + ph / 2, 0.97f + 0.03f * reveal, 0, (1 - reveal) * 8);
        GlassTheme.panel(px, py, pw, ph, GlassTheme.R_PANEL, 1.3f);
        ScreenTransition.focus(px * zoom, py * zoom, pw * zoom, ph * zoom, GlassTheme.R_PANEL * zoom);

        // Title and what it applies to
        SmoothFont title = SmoothFont.bold(12), small = SmoothFont.regular(8);
        title.draw("协议版本", px + PAD, py + 16, GlassTheme.TEXT);
        String scope = address == null ? "所有服务器（单独设置过的除外）" : "只对 " + (serverName == null || serverName.isEmpty() ? address : serverName);
        small.draw(small.trim(scope, pw - PAD * 2 - 60), px + PAD, py + 33, GlassTheme.MUTED);
        closeButton(px + pw - PAD - 22, py + 14, 22);

        // Search
        float sf = searchFocus.update(search.isFocused(), dt);
        float fx = px + PAD, fw = pw - PAD * 2;
        GlassTheme.field(fx, fieldY, fw, 26, 13, sf);
        Draw.outline(fx + 8, fieldY + 9, 6.4f, 6.4f, 3.2f, 1.2f, GlassTheme.FAINT, GlassTheme.FAINT);
        Draw.polyline(1.3f, GlassTheme.FAINT, fx + 13.7f, fieldY + 14.7f, fx + 16.2f, fieldY + 17.2f);
        if (search.getText().isEmpty() && !search.isFocused()) {
            SmoothFont font = SmoothFont.regular(9);
            int count = ViaUi.versionCount();
            font.draw(count > 0 ? "搜索 " + count + " 个版本" : "搜索版本", fx + 22, font.centerY(fieldY, 26) - 0.5f, GlassTheme.FAINT);
        }
        search.drawTextBox();

        drawList(dt);

        // Not loaded yet / off: say so under the fixed choices.
        String status = ViaUi.status();
        if (status != null) {
            SmoothFont font = SmoothFont.regular(8);
            font.drawCentered(font.trim(status, pw - PAD * 2), px + pw / 2, py + ph - 26, GlassTheme.WARN);
        }
        Draw.popTransform();
        Draw.popAlpha();
    }

    private void drawList(float dt) {
        List<Row> rows = rows();
        contentH = 0;
        for (Row row : rows) contentH += row.header ? HEADER_H : ROW_H + 3;
        float max = Math.max(0, contentH - listH + 6);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        scroll += (scrollTarget - scroll) * Math.min(1, dt * 16);

        float x = px + PAD - 4, w = pw - PAD * 2 + 8;
        Draw.pushClip(x - 2, listY - 2, w + 4, listH + 4);
        float y = listY - scroll;
        int accent = GlassTheme.accent();
        SmoothFont label = SmoothFont.bold(7.5f), name = SmoothFont.bold(9.5f), detail = SmoothFont.regular(7.5f);
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            float h = row.header ? HEADER_H : ROW_H;
            if (y + h >= listY - 4 && y <= listY + listH + 4) {
                if (row.header) {
                    label.draw(row.label.toUpperCase(Locale.ROOT), x + 6, y + 9, GlassTheme.FAINT);
                } else {
                    String id = row.follow ? "follow" : row.choice;
                    Interaction feel = feel(id);
                    boolean over = over(x, y, w, h);
                    feel.update(over, over && Mouse.isButtonDown(0), dt);
                    boolean chosen = isCurrent(row);
                    float hot = feel.hot;
                    Draw.pushTransform(x + w / 2, y + h / 2, feel.scale(0.02f), 0, 0);
                    if (chosen) {
                        Draw.rect(x, y, w, h, 8, Draw.alpha(accent, 0.2f + 0.08f * hot));
                        Draw.outline(x, y, w, h, 8, 0.8f, Draw.alpha(accent, 0.75f), Draw.alpha(accent, 0.3f));
                    } else if (hot > 0.01f) {
                        GlassTheme.card(x, y, w, h, 8, 0.3f + 0.7f * hot);
                        Draw.spot(x, y, w, h, 8, mx, my, Math.max(60, w * 0.4f), Draw.alpha(0xffffffff, 0.08f * hot));
                    }
                    float tx = x + 12;
                    if (chosen) {
                        Draw.circle(x + 9, y + h / 2, 2.2f, accent);
                        tx = x + 18;
                    }
                    name.draw(name.trim(row.label, w * 0.45f), tx, name.centerY(y, h) - 0.5f, chosen ? GlassTheme.TEXT : Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, 0.5f + 0.5f * hot));
                    if (row.detail != null) {
                        float dw = w - (tx - x) - name.width(name.trim(row.label, w * 0.45f)) - 24;
                        detail.drawRight(detail.trim(row.detail, Math.max(20, dw)), x + w - 10, detail.centerY(y, h) - 0.4f, GlassTheme.FAINT);
                    }
                    Draw.popTransform();
                    hit(x, y, w, h, () -> {
                        feel.pulse();
                        pick(row);
                    });
                }
            }
            y += row.header ? HEADER_H : ROW_H + 3;
        }
        Draw.popClip();

        // Scroll hint at the bottom edge
        if (max > 0 && scroll < max - 1) {
            Draw.gradientV(x, listY + listH - 14, w, 14, 0, 0x00000000, 0x30000000);
        }
    }

    private void closeButton(float x, float y, float size) {
        Interaction feel = feel("close");
        boolean over = over(x, y, size, size);
        feel.update(over, over && Mouse.isButtonDown(0), Anim.dt());
        GlassTheme.card(x, y, size, size, size / 2, 0.2f + 0.8f * feel.hot);
        int color = Draw.mix(GlassTheme.FAINT, GlassTheme.TEXT, feel.hot);
        float c = size / 2, r = 3.6f;
        Draw.polyline(1.3f, color, x + c - r, y + c - r, x + c + r, y + c + r);
        Draw.polyline(1.3f, color, x + c + r, y + c - r, x + c - r, y + c + r);
        hit(x, y, size, size, () -> {
            UiSound.CLICK.play();
            mc.displayGuiScreen(parent);
        });
    }

    private Interaction feel(String id) {
        Interaction feel = feels.get(id);
        if (feel == null) {
            feel = new Interaction();
            feels.put(id, feel);
        }
        return feel;
    }

    // ================================================================ input

    private void hit(float x, float y, float w, float h, Runnable action) {
        clipped[0] = x;
        clipped[1] = y;
        clipped[2] = x + w;
        clipped[3] = y + h;
        if (!Draw.clipTo(clipped)) return;
        Hit hit;
        if (hitCount < hits.size()) {
            hit = hits.get(hitCount);
        } else {
            hit = new Hit();
            hits.add(hit);
        }
        hitCount++;
        hit.x0 = clipped[0];
        hit.y0 = clipped[1];
        hit.x1 = clipped[2];
        hit.y1 = clipped[3];
        hit.action = action;
    }

    private boolean over(float x, float y, float w, float h) {
        return openTime > 0.1f && mx >= x && mx < x + w && my >= y && my < y + h && Draw.insideClip(mx, my);
    }

    private void applyWheel() {
        if (wheel == 0) return;
        float notches = wheel / 120f;
        wheel = 0;
        scrollTarget -= notches * (ROW_H + 3) * 2.5f;
    }

    @Override
    public void handleMouseInput() {
        float x = Mouse.getEventX() / ui, y = (mc.displayHeight - Mouse.getEventY() - 1) / ui;
        if (Mouse.getEventButtonState()) press(x, y, Mouse.getEventButton());
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) wheel += dWheel;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        press(mouseX / zoom, mouseY / zoom, button);
    }

    private void press(float x, float y, int button) {
        if (openTime <= 0.1f || button != 0) return;
        mx = x;
        my = y;
        boolean inSearch = x >= px + PAD && x < px + pw - PAD && y >= fieldY && y < fieldY + 26;
        if (inSearch) {
            int clampedX = Math.round(Math.max(search.xPosition, Math.min(search.xPosition + search.getWidth() - 5, x)));
            search.mouseClicked(clampedX, search.yPosition, 0);
            search.setFocused(true);
            return;
        }
        search.setFocused(false);
        for (int i = hitCount - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (x >= hit.x0 && x < hit.x1 && y >= hit.y0 && y < hit.y1) {
                if (hit.action != null) hit.action.run();
                return;
            }
        }
        // A click outside the panel closes it.
        if (x < px || x >= px + pw || y < py || y >= py + ph) mc.displayGuiScreen(parent);
    }

    @Override
    public boolean wantsTextInput() {
        return true;
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (!search.getText().isEmpty()) search.setText("");
            else mc.displayGuiScreen(parent);
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            // Enter picks the first match.
            for (Row row : rows()) {
                if (!row.header) {
                    pick(row);
                    return;
                }
            }
            return;
        }
        if (search.isFocused()) {
            search.textboxKeyTyped(typed, key);
            scrollTarget = 0;
            return;
        }
        if (!isCtrlKeyDown() && ChatAllowedCharacters.isAllowedCharacter(typed) && typed != ' ') {
            search.setFocused(true);
            search.setCursorPositionEnd();
            search.textboxKeyTyped(typed, key);
            scrollTarget = 0;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
