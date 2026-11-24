package myau.ui.glass;

import myau.ui.GlassTextField;
import myau.ui.GlassTheme;
import myau.via.ViaDialogView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiConfirmOpenLink;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.GuiConnecting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A dialog a 1.21.6+ server shows before Join Game (rules to accept, a login, a check), drawn by
 * the client: its text, its inputs and its buttons, plus a way to leave. Opened over the connecting
 * screen, which it keeps ticking, because that screen is what drives the connection until the
 * world loads; Join Game or a disconnect replace this screen as they would the connecting screen.
 */
public class GlassServerDialog extends GuiScreen implements myau.util.ime.TextInputScreen {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Logger LOGGER = LogManager.getLogger("BetterMyau/Via");
    private static final float MIN_W = 420, MIN_H = 300, PANEL_W = 340, PAD = 18, HEADER_H = 46;
    private static final float LINE_H = 12, BUTTON_H = 26, FIELD_H = 24;

    private final ViaDialogView view;
    /** The connecting screen (or whatever was open); null when the dialog came during play. */
    private final GuiScreen parent;

    private float ui = 2, zoom = 1, sw = MIN_W, sh = MIN_H, mx, my, openTime;
    private float px, py, pw, ph, contentY, contentH, viewH;
    private float scroll, scrollTarget;
    private int wheel;
    private float pressX;
    private ViaDialogView.Range dragging;
    private float dragX, dragW;
    private final Map<String, Interaction> feels = new HashMap<>();
    private final Map<ViaDialogView.Field, GlassTextField> fields = new IdentityHashMap<>();
    private final Map<ViaDialogView.Field, Anim.Smooth> fieldFocus = new IdentityHashMap<>();
    private final Map<ViaDialogView.Item, List<String>> wrapped = new IdentityHashMap<>();
    private float wrappedFor = -1;

    private static final class Hit {
        float x0, y0, x1, y1;
        Runnable action;
    }

    private final List<Hit> hits = new ArrayList<>();
    private int hitCount;
    private final float[] clipped = new float[4];

    public GlassServerDialog(ViaDialogView view, GuiScreen parent) {
        this.view = view;
        this.parent = parent;
    }

    /** Shows server dialogs as this screen, over the connecting screen (see ViaDialogView.Presenter). */
    public static ViaDialogView.Presenter presenter() {
        return new ViaDialogView.Presenter() {
            @Override
            public void show(final ViaDialogView view) {
                mc.addScheduledTask(() -> {
                    if (!view.connected()) return;
                    GuiScreen current = mc.currentScreen, under;
                    if (current instanceof GlassServerDialog) {
                        under = ((GlassServerDialog) current).parent;
                    } else if (current instanceof GuiConnecting || mc.theWorld != null) {
                        under = current;
                    } else {
                        LOGGER.warn("A server dialog arrived while {} is open; it is not shown",
                                current == null ? "no screen" : current.getClass().getSimpleName());
                        return;
                    }
                    mc.displayGuiScreen(new GlassServerDialog(view, under));
                });
            }

            @Override
            public void close(final ViaDialogView view) {
                mc.addScheduledTask(() -> {
                    GuiScreen current = mc.currentScreen;
                    if (current instanceof GlassServerDialog && ((GlassServerDialog) current).view == view) {
                        mc.displayGuiScreen(((GlassServerDialog) current).parent);
                    }
                });
            }

            @Override
            public void openUrl(final String url) {
                mc.addScheduledTask(() -> {
                    if (mc.currentScreen instanceof GlassServerDialog) ((GlassServerDialog) mc.currentScreen).confirmUrl(url);
                });
            }

            @Override
            public void copy(String text) {
                GuiScreen.setClipboardString(text);
            }
        };
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
    public void updateScreen() {
        for (GlassTextField field : fields.values()) field.updateCursorCounter();
        // Until the world loads, the connecting screen is what processes the connection.
        if (parent != null) parent.updateScreen();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ================================================================ layout

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

    private float inner() {
        return pw - PAD * 2;
    }

    private float footerHeight() {
        // The main buttons, the leave button and the bottom margin, with a little air above them.
        return 6 + (view.waiting || view.footer.isEmpty() ? 0 : BUTTON_H + 8) + 22 + 12;
    }

    private void layout() {
        pw = Math.min(PANEL_W, sw - 40);
        if (wrappedFor != pw) {
            wrapped.clear();
            wrappedFor = pw;
        }
        contentH = view.waiting ? 70 : contentHeight();
        float wanted = HEADER_H + contentH + footerHeight();
        ph = Math.max(120, Math.min(sh - 40, wanted));
        px = (sw - pw) / 2;
        py = (sh - ph) / 2;
        contentY = py + HEADER_H;
        viewH = ph - HEADER_H - footerHeight();
    }

    private List<String> lines(ViaDialogView.Item item, SmoothFont font) {
        List<String> lines = wrapped.get(item);
        if (lines == null) {
            lines = wrap(font, item.label, inner());
            wrapped.put(item, lines);
        }
        return lines;
    }

    /** Breaks text into lines that fit: at spaces where there are any, else between characters. */
    private static List<String> wrap(SmoothFont font, String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            float width = 0;
            for (int i = 0; i < paragraph.length(); i++) {
                char c = paragraph.charAt(i);
                float advance = font.width(String.valueOf(c));
                if (width + advance > maxWidth && line.length() > 0) {
                    int space = line.lastIndexOf(" ");
                    if (c != ' ' && space > 0 && space < line.length() - 1 && c < 0x2E80) {
                        // A word of a spaced script: it moves to the next line whole.
                        String rest = line.substring(space + 1);
                        lines.add(line.substring(0, space));
                        line = new StringBuilder(rest);
                        width = font.width(rest);
                    } else {
                        lines.add(line.toString());
                        line = new StringBuilder();
                        width = 0;
                        if (c == ' ') continue;
                    }
                }
                line.append(c);
                width += advance;
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private float height(ViaDialogView.Item item) {
        if (item instanceof ViaDialogView.Text) return lines(item, SmoothFont.regular(9)).size() * LINE_H + 8;
        if (item instanceof ViaDialogView.Field) return (item.label.isEmpty() ? 0 : 13) + FIELD_H + 10;
        if (item instanceof ViaDialogView.Toggle) return 24 + 4;
        if (item instanceof ViaDialogView.Choice) return BUTTON_H + 6;
        if (item instanceof ViaDialogView.Range) return 13 + 16 + 10;
        return BUTTON_H + 6;
    }

    private float contentHeight() {
        float total = 0;
        for (ViaDialogView.Item item : view.items) total += height(item);
        return total;
    }

    // ================================================================ frame

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float dt = Anim.dt();
        openTime += dt;
        measure();
        layout();
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

        SmoothFont title = SmoothFont.bold(12), small = SmoothFont.regular(8);
        String heading = view.title.isEmpty() ? "服务器消息" : view.title.replace('\n', ' ');
        title.draw(title.trim(heading, inner()), px + PAD, py + 15, GlassTheme.TEXT);
        small.draw("来自服务器 · 回应后继续进入", px + PAD, py + 31, GlassTheme.FAINT);

        if (view.waiting) {
            drawWaiting();
        } else {
            drawContent(dt);
            drawFooter(dt);
        }
        drawLeave(dt);
        Draw.popTransform();
        Draw.popAlpha();
    }

    private void drawWaiting() {
        float cy = contentY + viewH / 2 - 8;
        DesignKit.spinner(px + pw / 2, cy, 9, 1.8f, GlassTheme.vividAccent());
        SmoothFont font = SmoothFont.regular(9);
        font.drawCentered("已回应，等待服务器…", px + pw / 2, cy + 18, GlassTheme.MUTED);
    }

    private void drawContent(float dt) {
        float max = Math.max(0, contentH - viewH);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        scroll += (scrollTarget - scroll) * Math.min(1, dt * 16);
        if (max == 0) scroll = 0;

        float x = px + PAD, w = inner();
        Draw.pushClip(px + 4, contentY - 1, pw - 8, viewH + 2);
        float y = contentY - scroll;
        int index = 0;
        for (final ViaDialogView.Item item : view.items) {
            float h = height(item);
            if (y + h >= contentY - 4 && y <= contentY + viewH + 4) drawItem(item, index, x, y, w, dt);
            else if (item instanceof ViaDialogView.Field) hideField((ViaDialogView.Field) item);
            y += h;
            index++;
        }
        Draw.popClip();
        if (max > 0 && scroll < max - 1) Draw.gradientV(px + 4, contentY + viewH - 14, pw - 8, 14, 0, 0x00000000, 0x30000000);
    }

    private void drawItem(final ViaDialogView.Item item, int index, float x, float y, float w, float dt) {
        SmoothFont body = SmoothFont.regular(9), label = SmoothFont.regular(8);
        if (item instanceof ViaDialogView.Text) {
            float ty = y + 1;
            for (String line : lines(item, body)) {
                body.draw(line, x, ty, GlassTheme.TEXT);
                ty += LINE_H;
            }
        } else if (item instanceof ViaDialogView.Field) {
            final ViaDialogView.Field field = (ViaDialogView.Field) item;
            float fy = y;
            if (!item.label.isEmpty()) {
                label.draw(label.trim(item.label, w), x, y, GlassTheme.MUTED);
                fy += 13;
            }
            final GlassTextField text = textField(field, x, fy, w);
            Anim.Smooth focus = fieldFocus.get(field);
            if (focus == null) fieldFocus.put(field, focus = new Anim.Smooth(0, 16));
            GlassTheme.field(x, fy, w, FIELD_H, 8, focus.update(text.isFocused(), dt));
            text.drawTextBox();
            hit(x, fy, w, FIELD_H, () -> {
                unfocus();
                int clampedX = Math.round(Math.max(text.xPosition, Math.min(text.xPosition + text.getWidth() - 5, pressX)));
                text.mouseClicked(clampedX, text.yPosition, 0);
                text.setFocused(true);
            });
        } else if (item instanceof ViaDialogView.Toggle) {
            final ViaDialogView.Toggle toggle = (ViaDialogView.Toggle) item;
            Interaction feel = feel("i" + index);
            boolean over = over(x, y, w, 24);
            feel.update(over, over && Mouse.isButtonDown(0), dt);
            if (feel.hot > 0.01f) GlassTheme.card(x - 6, y, w + 12, 24, 8, 0.3f + 0.5f * feel.hot);
            body.draw(body.trim(item.label, w - 40), x, body.centerY(y, 24) - 0.5f, GlassTheme.TEXT);
            float sx = x + w - 28, sy = y + 5;
            int accent = GlassTheme.accent();
            Draw.rect(sx, sy, 28, 14, 7, toggle.value() ? Draw.alpha(accent, 0.9f) : 0x40ffffff);
            Draw.circle(toggle.value() ? sx + 21 : sx + 7, sy + 7, 5, 0xffffffff);
            hit(x - 6, y, w + 12, 24, () -> {
                unfocus();
                UiSound.CLICK.play();
                toggle.set(!toggle.value());
            });
        } else if (item instanceof ViaDialogView.Choice) {
            final ViaDialogView.Choice choice = (ViaDialogView.Choice) item;
            String shown = item.label.isEmpty() ? choice.option() : item.label + "：" + choice.option();
            button("i" + index, x, y, w, BUTTON_H, shown, false, dt, choice::next);
        } else if (item instanceof ViaDialogView.Range) {
            final ViaDialogView.Range range = (ViaDialogView.Range) item;
            String shown = range.shown() == null ? "" : range.shown();
            label.draw(label.trim(item.label, w - 60), x, y, GlassTheme.MUTED);
            label.drawRight(shown, x + w, y, GlassTheme.TEXT);
            final float tx = x + 6, tw = w - 12, ty = y + 13 + 6;
            if (dragging == range) {
                if (Mouse.isButtonDown(0)) range.setFraction((mx - dragX) / dragW);
                else dragging = null;
            }
            int accent = GlassTheme.accent();
            Draw.rect(tx, ty, tw, 4, 2, 0x40ffffff);
            Draw.rect(tx, ty, tw * range.fraction(), 4, 2, Draw.alpha(accent, 0.9f));
            Draw.circle(tx + tw * range.fraction(), ty + 2, 5.5f, 0xffffffff);
            hit(x, y + 13, w, 16, () -> {
                unfocus();
                dragging = range;
                dragX = tx;
                dragW = tw;
                range.setFraction((pressX - tx) / tw);
            });
        } else if (item instanceof ViaDialogView.Button) {
            final ViaDialogView.Button button = (ViaDialogView.Button) item;
            button("i" + index, x, y, w, BUTTON_H, item.label, false, dt, button::click);
        }
    }

    private void drawFooter(float dt) {
        int count = view.footer.size();
        if (count == 0) return;
        float gap = 8, y = py + ph - 12 - 22 - 8 - BUTTON_H;
        float w = (inner() - gap * (count - 1)) / count, x = px + PAD;
        for (int i = 0; i < count; i++) {
            final ViaDialogView.Button button = view.footer.get(i);
            button("f" + i, x, y, w, BUTTON_H, button.label, i == 0, dt, button::click);
            x += w + gap;
        }
    }

    /** Always there: a server that never answers must not hold the player on this screen. */
    private void drawLeave(float dt) {
        float w = 96, h = 22, x = px + pw / 2 - w / 2, y = py + ph - 12 - h;
        Interaction feel = feel("leave");
        boolean over = over(x, y, w, h);
        feel.update(over, over && Mouse.isButtonDown(0), dt);
        if (feel.hot > 0.01f) GlassTheme.card(x, y, w, h, h / 2, 0.3f + 0.6f * feel.hot);
        SmoothFont font = SmoothFont.regular(8.5f);
        font.drawCentered("断开连接", x + w / 2, font.centerY(y, h) - 0.5f, Draw.mix(GlassTheme.FAINT, GlassTheme.TEXT, feel.hot));
        hit(x, y, w, h, this::leave);
    }

    private void button(String id, float x, float y, float w, float h, String text, boolean primary, float dt, final Runnable action) {
        final Interaction feel = feel(id);
        boolean over = over(x, y, w, h);
        feel.update(over, over && Mouse.isButtonDown(0), dt);
        float hot = feel.hot;
        Draw.pushTransform(x + w / 2, y + h / 2, feel.scale(0.03f), 0, 0);
        int accent = GlassTheme.accent();
        if (primary) {
            Draw.rect(x, y, w, h, 8, Draw.alpha(accent, 0.55f + 0.25f * hot));
            Draw.outline(x, y, w, h, 8, 0.8f, Draw.alpha(accent, 0.9f), Draw.alpha(accent, 0.4f));
        } else {
            GlassTheme.card(x, y, w, h, 8, 0.45f + 0.55f * hot);
        }
        SmoothFont font = SmoothFont.bold(9.5f);
        String shown = font.trim(text.replace('\n', ' '), w - 16);
        font.drawCentered(shown, x + w / 2, font.centerY(y, h) - 0.5f, primary ? 0xffffffff : Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, 0.6f + 0.4f * hot));
        Draw.popTransform();
        hit(x, y, w, h, () -> {
            unfocus();
            feel.pulse();
            UiSound.CLICK.play();
            action.run();
        });
    }

    // ================================================================ text fields

    private GlassTextField textField(final ViaDialogView.Field field, float x, float y, float w) {
        int fx = Math.round(x + 9), fy = Math.round(y + 6), fw = Math.round(w - 18);
        GlassTextField text = fields.get(field);
        if (text == null || text.getWidth() != fw) {
            boolean focused = text != null && text.isFocused();
            text = new GlassTextField(0, fontRendererObj, fx, fy, fw, 12);
            text.setMaxStringLength(field.maxLength);
            text.setText(field.value());
            text.setFocused(focused);
            fields.put(field, text);
        }
        text.xPosition = fx;
        text.yPosition = fy;
        text.setVisible(true);
        return text;
    }

    /** Scrolled out of view: it keeps its text but takes no clicks and draws no caret. */
    private void hideField(ViaDialogView.Field field) {
        GlassTextField text = fields.get(field);
        if (text != null) text.setVisible(false);
    }

    private GlassTextField focused() {
        for (GlassTextField text : fields.values()) {
            if (text.isFocused()) return text;
        }
        return null;
    }

    private void unfocus() {
        for (GlassTextField text : fields.values()) text.setFocused(false);
    }

    private void focusNext() {
        List<GlassTextField> order = new ArrayList<>();
        for (ViaDialogView.Item item : view.items) {
            if (item instanceof ViaDialogView.Field && fields.get(item) != null) order.add(fields.get(item));
        }
        if (order.isEmpty()) return;
        int at = order.indexOf(focused());
        unfocus();
        GlassTextField next = order.get((at + 1) % order.size());
        next.setFocused(true);
        next.setCursorPositionEnd();
    }

    private void sync() {
        for (Map.Entry<ViaDialogView.Field, GlassTextField> entry : fields.entrySet()) {
            if (!entry.getValue().getText().equals(entry.getKey().value())) entry.getKey().set(entry.getValue().getText());
        }
    }

    // ================================================================ leaving

    private void leave() {
        UiSound.CLICK.play();
        view.disconnect();
        if (parent == null) {
            // In a world: the game notices the closed connection by itself.
            mc.displayGuiScreen(null);
            return;
        }
        mc.displayGuiScreen(before(parent));
    }

    /** The screen the connecting screen was opened from, as its own Cancel button returns to. */
    private static GuiScreen before(GuiScreen connecting) {
        if (connecting instanceof GuiConnecting) {
            try {
                // Its only GuiScreen field; found by type, so the name's mapping does not matter.
                for (Field field : GuiConnecting.class.getDeclaredFields()) {
                    if (field.getType() != GuiScreen.class) continue;
                    field.setAccessible(true);
                    Object screen = field.get(connecting);
                    if (screen instanceof GuiScreen) return (GuiScreen) screen;
                }
            } catch (Throwable ignored) {
            }
        }
        return new GuiMultiplayer(new GuiMainMenu());
    }

    private void confirmUrl(final String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            LOGGER.warn("The dialog wants to open {}, which is not a web address", url);
            return;
        }
        final GuiScreen self = this;
        mc.displayGuiScreen(new GuiConfirmOpenLink((result, id) -> {
            if (result) {
                try {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                } catch (Throwable t) {
                    LOGGER.warn("Could not open {}", url, t);
                }
            }
            mc.displayGuiScreen(self);
        }, url, 0, false));
    }

    // ================================================================ input

    private Interaction feel(String id) {
        Interaction feel = feels.get(id);
        if (feel == null) {
            feel = new Interaction();
            feels.put(id, feel);
        }
        return feel;
    }

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
        scrollTarget -= notches * 36;
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
        pressX = x;
        for (int i = hitCount - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (x >= hit.x0 && x < hit.x1 && y >= hit.y0 && y < hit.y1) {
                if (hit.action != null) hit.action.run();
                return;
            }
        }
        unfocus();
    }

    @Override
    public boolean wantsTextInput() {
        return focused() != null;
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            // The dialog's own way out, when it has one; otherwise "断开连接" is how to leave.
            if (!view.waiting) view.escape();
            return;
        }
        if (view.waiting) return;
        if (key == Keyboard.KEY_TAB) {
            focusNext();
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            // Enter presses the dialog's first button: its main one, else the first in its list.
            sync();
            if (!view.footer.isEmpty()) {
                view.footer.get(0).click();
                return;
            }
            for (ViaDialogView.Item item : view.items) {
                if (item instanceof ViaDialogView.Button) {
                    ((ViaDialogView.Button) item).click();
                    return;
                }
            }
            return;
        }
        GlassTextField text = focused();
        if (text != null) {
            text.textboxKeyTyped(typed, key);
            sync();
        }
    }
}
