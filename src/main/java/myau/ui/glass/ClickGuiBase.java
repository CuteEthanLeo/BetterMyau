package myau.ui.glass;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.GuiModule;
import myau.property.Property;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.TextProperty;
import myau.ui.ClickGui;
import myau.ui.GlassTheme;
import myau.ui.dataset.Slider;
import myau.ui.dataset.impl.FloatSlider;
import myau.ui.dataset.impl.IntSlider;
import myau.ui.dataset.impl.PercentageSlider;
import myau.util.KeyBindUtil;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import myau.config.Config;
import myau.config.ConfigManager;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What every click GUI layout shares: the frame set-up in its own resolution-based scale, the
 * hit table that turns this frame's drawing into click targets, dragging, key binding, inline
 * editing, the search text, the colour theme, and the setting rows of a module. A layout only
 * decides where modules go ({@link #drawContent}) and draws its own chrome.
 * <p>
 * Still a {@link ClickGui}: other code tests for that class to know the GUI is open.
 */
public abstract class ClickGuiBase extends ClickGui implements myau.util.ime.TextInputScreen {
    protected interface Click { void click(float mx, float my, int button); }
    protected interface Drag { void drag(float mx, float my); }
    /** @return false when the wheel could not move this region any further (an outer one may take it) */
    protected interface Scroll { boolean scroll(float notches); }

    private static final class Hit {
        float x0, y0, x1, y1;
        Click click;
    }

    private static final class Zone {
        float x0, y0, x1, y1;
        Scroll scroll;
    }

    /** How far a row's glows and shadows reach past its rectangle; rows further out are not drawn. */
    private static final float CULL_MARGIN = 12;

    // Pooled: the lists only grow, the counts say how much of them this frame used.
    private final List<Hit> hits = new ArrayList<>();
    private final List<Zone> zones = new ArrayList<>();
    private int hitCount, zoneCount;
    private final float[] scratch = new float[4];

    private final Anim.Clock clock = new Anim.Clock();
    protected final TextEdit search = new TextEdit(24);
    /** The search box was clicked: the input method is on for it until Enter, Esc or another click. */
    protected boolean searchFocus;
    /** The search text, trimmed and lower-cased, for this frame. */
    protected String query = "";

    /** The colours to draw with; fades from one theme to the next when the setting changes. */
    protected final GuiPalette pal = GuiPalette.copyOf(GuiPalette.GLASS);
    private final GuiPalette palFrom = GuiPalette.copyOf(GuiPalette.GLASS);
    private GuiPalette palTo = GuiPalette.GLASS;
    private float palMix = 1;

    protected float ui = 2, sw, sh, mx, my, dt;
    protected float openTime, closeTime;
    protected boolean closing, interactive;
    /** Layer under the cursor and layer being drawn; controls only react when they match. */
    protected Object hoverLayer, drawingLayer;
    protected Drag drag;
    protected Module binding;
    private Object editKey;
    private final TextEdit editText = new TextEdit(64);
    private Consumer<String> editCommit;
    private int wheel;
    private boolean opened, resume;
    private GuiModule guiModule;

    // Fonts for this frame's scale, fetched once per frame instead of once per label.
    protected SmoothFont fTiny, fSmall, fValue, fRow, fTitle;

    // ================================================================ layout hooks

    /** A fresh opening (not a window resize): place things, order the entrance. */
    protected abstract void onOpen();

    /** The screen is going away: save the layout. */
    protected abstract void onClosed();

    /** Seconds the exit animation takes. */
    protected abstract float exitDuration();

    /** Draw everything above the dimmed backdrop. {@code backdrop} 0..1 is how far the GUI has faded in. */
    protected abstract void drawContent(float backdrop);

    /** Bring a module into view with its settings open. */
    public void reveal(Module module) { }

    /** This screen replaces another layout mid-session: appear at once, without the entrance. */
    public void resumeFrom(ClickGuiBase previous) {
        resume = true;
        search.set(previous.search.toString());
        searchFocus = previous.searchFocus;
    }

    protected GuiModule guiModule() {
        if (guiModule == null && Myau.moduleManager != null) guiModule = (GuiModule) Myau.moduleManager.modules.get(GuiModule.class);
        return guiModule;
    }

    // ================================================================ lifecycle

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        measureScreen();
        // Resizing the window re-initialises the screen; that must not replay the entrance.
        if (opened) return;
        opened = true;
        openTime = resume ? 10 : 0;
        closeTime = 0;
        closing = false;
        if (!resume) UiSound.OPEN.play();
        resume = false;
        drag = null;
        binding = null;
        editKey = null;
        searchFocus = false;
        // The search is kept: closing the GUI by accident mid-search must not lose it.
        wheel = 0;
        clock.reset();
        GuiModule gui = guiModule();
        palTo = GuiPalette.byIndex(gui == null ? 0 : gui.theme.getValue());
        pal.set(palTo);
        palMix = 1;
        onOpen();
    }

    private void measureScreen() {
        float scale = Math.round(Math.max(1f, Math.min(4f, mc.displayHeight / 540f)) * 4f) / 4f;
        if (mc.displayWidth / scale < 700) scale = Math.max(0.5f, mc.displayWidth / 700f);
        ui = scale;
        sw = mc.displayWidth / ui;
        sh = mc.displayHeight / ui;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        commitEdit();
        opened = false;
        binding = null;
        drag = null;
        onClosed();
        // Auto-save: whatever was changed in the GUI is written now, not a moment later.
        ConfigManager.flush();
    }

    protected void close() {
        if (closing) return;
        commitEdit();
        closing = true;
        closeTime = 0;
        UiSound.CLOSE.play();
        drag = null;
        binding = null;
    }

    @Override
    public void updateScreen() {
        // The layout setting changed while the GUI is open: hand over to the other screen. Done
        // on the tick, right after input, so no frame is drawn without a screen.
        GuiModule gui = guiModule();
        if (gui == null || closing || mc.currentScreen != this) return;
        ClickGuiBase wanted = gui.screen();
        if (wanted != null && wanted != this) {
            wanted.resumeFrom(this);
            mc.displayGuiScreen(wanted);
            wanted.reveal(gui);
        }
    }

    // ================================================================ frame

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long started = Draw.PERF ? System.nanoTime() : 0;
        float raw = clock.tick();
        dt = raw * Anim.speed();
        measureScreen();
        if (closing) closeTime += dt; else openTime += dt;
        float exitTotal = exitDuration();
        if (Anim.reduced()) {
            // Animations are off: skip straight to the end of the entrance or the exit.
            openTime = Math.max(openTime, 10);
            if (closing) closeTime = exitTotal;
        }
        if (closing && closeTime >= exitTotal) {
            mc.displayGuiScreen(null);
            return;
        }
        interactive = !closing && openTime > 0.22f;
        mx = Mouse.getX() / ui;
        my = (mc.displayHeight - Mouse.getY() - 1) / ui;
        if (drag != null) {
            if (Mouse.isButtonDown(0)) drag.drag(mx, my); else drag = null;
        }
        updatePalette(raw);

        float backdrop = closing ? 1 - Anim.inOutCubic(closeTime / exitTotal) : Anim.outCubic(openTime / 0.35f);
        float factor = ui / guiScaleFactor();
        GlStateManager.pushMatrix();
        GlStateManager.scale(factor, factor, 1);
        Draw.setScale(ui);
        GlassBlur.invalidate();
        if (Draw.PERF) Draw.resetStats();

        // The wheel goes to the innermost scroll region under the cursor, from last frame's layout.
        if (wheel != 0) {
            float notches = wheel / 120f;
            wheel = 0;
            for (int i = zoneCount - 1; i >= 0 && interactive; i--) {
                Zone zone = zones.get(i);
                if (mx >= zone.x0 && mx < zone.x1 && my >= zone.y0 && my < zone.y1 && zone.scroll.scroll(notches)) break;
            }
        }
        hitCount = 0;
        zoneCount = 0;
        fTiny = SmoothFont.regular(7.5f);
        fSmall = SmoothFont.regular(8);
        fValue = SmoothFont.regular(8.5f);
        fRow = SmoothFont.regular(9.5f);
        fTitle = SmoothFont.bold(10.5f);
        query = search.isEmpty() ? "" : search.toString().trim().toLowerCase(Locale.ROOT);

        Draw.beginBatch();
        try {
            // Capture the full-strength blur from the bare scene before anything is drawn over it.
            // Otherwise, while the backdrop is still fading, the first panel would capture the
            // screen after the dim and its own shadow, look dark, and brighten with a jump once
            // the backdrop reached full strength.
            if (pal.blur || pal.backdropBlur) GlassBlur.prime();
            // The backdrop goes from sharp to blurred as the GUI opens, and back as it closes.
            if (pal.backdropBlur) Draw.stageBlur(backdrop);
            GlassTheme.dim(sw, sh, backdrop, pal);
            Draw.stageBlur(1);
            drawContent(backdrop);
            if (Draw.PERF) drawPerf(started);
        } finally {
            Draw.endBatch();
        }

        Draw.setScale(ui);
        GlStateManager.popMatrix();
        Draw.useGuiScale();
    }

    private void updatePalette(float seconds) {
        GuiModule gui = guiModule();
        GuiPalette target = GuiPalette.byIndex(gui == null ? 0 : gui.theme.getValue());
        if (target != palTo) {
            palFrom.set(pal);
            palTo = target;
            palMix = Anim.reduced() ? 1 : 0;
            if (palMix >= 1) pal.set(target);
        }
        if (palMix < 1) {
            palMix = Math.min(1, palMix + seconds / 0.28f);
            pal.blend(palFrom, palTo, Anim.inOutCubic(palMix));
        }
    }

    private int srWidth, srHeight, srSetting = -1;
    private float srFactor = 2;

    private float guiScaleFactor() {
        if (mc.displayWidth != srWidth || mc.displayHeight != srHeight || mc.gameSettings.guiScale != srSetting) {
            srWidth = mc.displayWidth;
            srHeight = mc.displayHeight;
            srSetting = mc.gameSettings.guiScale;
            srFactor = new ScaledResolution(mc).getScaleFactor();
        }
        return srFactor;
    }

    // Performance overlay (-Dmyau.guiPerf=true): what this screen cost, averaged over a second.
    private long perfNanos;
    private int perfFrames, perfLogged;
    private String perfText = "";

    private void drawPerf(long started) {
        perfNanos += System.nanoTime() - started;
        if (++perfFrames >= 60) {
            perfText = String.format(Locale.ROOT, "%s  shapes %d  texts %d  program binds %d  uniforms %d  blurs %d  cpu %.3f ms",
                    getClass().getSimpleName(), Draw.statShapes, Draw.statTexts, Draw.statBinds, Draw.statUniforms, Draw.statBlurs,
                    perfNanos / 1e6 / perfFrames);
            if (++perfLogged % 3 == 0) Myau.LOGGER.info("[GuiPerf] {}", perfText);
            perfNanos = 0;
            perfFrames = 0;
        }
        fTiny.draw(perfText, 8, 4, pal.text);
    }

    // ================================================================ helpers

    protected void hit(float x, float y, float w, float h, Click click) {
        float[] r = scratch;
        r[0] = x; r[1] = y; r[2] = x + w; r[3] = y + h;
        if (!Draw.clipTo(r)) return;
        Hit hit;
        if (hitCount < hits.size()) hit = hits.get(hitCount); else hits.add(hit = new Hit());
        hitCount++;
        hit.x0 = r[0]; hit.y0 = r[1]; hit.x1 = r[2]; hit.y1 = r[3];
        hit.click = click;
    }

    /** Register a region the mouse wheel scrolls; regions registered later (inner ones) get it first. */
    protected void scrollHit(float x, float y, float w, float h, Scroll scroll) {
        float[] r = scratch;
        r[0] = x; r[1] = y; r[2] = x + w; r[3] = y + h;
        if (!Draw.clipTo(r)) return;
        Zone zone;
        if (zoneCount < zones.size()) zone = zones.get(zoneCount); else zones.add(zone = new Zone());
        zoneCount++;
        zone.x0 = r[0]; zone.y0 = r[1]; zone.x1 = r[2]; zone.y1 = r[3];
        zone.scroll = scroll;
    }

    protected boolean over(float x, float y, float w, float h) {
        return interactive && drag == null && hoverLayer == drawingLayer
                && mx >= x && mx < x + w && my >= y && my < y + h && Draw.insideClip(mx, my);
    }

    /** A halo in the accent colour: light added on dark themes, a coloured shadow on light ones. */
    protected void glow(float x, float y, float w, float h, float r, float soft, int color) {
        if (pal.light) Draw.shadow(x, y, w, h, r, soft * 0.8f, Draw.fade(color, 0.85f));
        else Draw.glow(x, y, w, h, r, soft, color);
    }

    /** Highlight that follows the cursor; {@code white} is the colour it has on a dark theme. */
    protected void spot(float x, float y, float w, float h, float r, float radius, int white) {
        if (pal.light) Draw.spotBlend(x, y, w, h, r, 0, mx, my, radius, pal.wash(white));
        else Draw.spot(x, y, w, h, r, mx, my, radius, white);
    }

    protected void spotOutline(float x, float y, float w, float h, float r, float thickness, float radius, int white) {
        if (pal.light) Draw.spotBlend(x, y, w, h, r, thickness, mx, my, radius, pal.wash(white));
        else Draw.spotOutline(x, y, w, h, r, thickness, mx, my, radius, white);
    }

    protected boolean editing() {
        return editKey != null;
    }

    /** Something is being typed: a value edit, or the search (clicked or not empty). */
    public boolean typing() {
        return editKey != null || searchFocus || !search.isEmpty();
    }

    /**
     * Input method on while a value or config name is edited, or the search box was clicked.
     * Typing anywhere still searches, without it: module names are English, W, A, S and D may
     * be InvWalk movement, and the GUI key (right Shift) would switch the IME's language.
     */
    @Override
    public boolean wantsTextInput() {
        return binding == null && !closing && (editKey != null || searchFocus);
    }

    /** Click handler for a search box: typing goes there, caret at the end. */
    protected void focusSearch() {
        commitEdit();
        searchFocus = true;
        search.caretToEnd();
    }

    /**
     * Draw an edit's text in the box (x, y, w, h): its selection, and its caret when
     * {@code focused}, scrolled so the caret stays in view; short text sits at the right edge
     * when {@code right}. The caret is reported to the input method. Returns where the text ends.
     */
    protected float drawEdit(TextEdit edit, SmoothFont font, float x, float y, float w, float h, int color, boolean focused, boolean right) {
        String text = edit.toString();
        float full = font.width(text), caretAt = font.width(text.substring(0, Math.min(edit.caret(), text.length())));
        float room = Math.max(1, w - 2);
        float scroll = 0;
        if (full > room) {
            scroll = edit.scroll;
            if (caretAt - scroll > room) scroll = caretAt - room;
            if (caretAt - scroll < 0) scroll = caretAt;
            scroll = Math.max(0, Math.min(scroll, full - room));
        }
        edit.scroll = scroll;
        float start = right && full <= room ? x + w - 1 - full : x - scroll;
        int accent = pal.accentInk(GlassTheme.accent());
        Draw.pushClip(x - 1, y, w + 2, h);
        if (focused && edit.hasSelection()) {
            float from = font.width(text.substring(0, edit.selectionStart())), to = font.width(text.substring(0, edit.selectionEnd()));
            Draw.rect(start + from, y + 3, to - from, h - 6, 2, Draw.alpha(accent, 0.38f));
        }
        float end = text.isEmpty() ? start : font.draw(text, start, font.centerY(y, h) - 0.5f, color);
        Draw.popClip();
        if (focused) {
            float cx = Math.max(x, Math.min(x + w, start + caretAt));
            if (System.currentTimeMillis() % 1000 < 550) Draw.rect(cx, y + 4.5f, 0.8f, h - 9, 0, accent);
            Draw.reportCaret(cx, y + 3, h - 6);
        }
        return end;
    }

    /** Feed a key to an edit and play the matching sound; false when it was not an editing key. */
    private boolean typeInto(TextEdit edit, char typed, int key) {
        String before = edit.toString();
        if (!edit.key(typed, key)) return false;
        int grew = edit.length() - before.length();
        if (grew > 0 || (grew == 0 && !before.equals(edit.toString()))) UiSound.TYPE.play();
        else if (grew < 0) UiSound.DELETE.play();
        return true;
    }

    /** Start editing a value in place: typing replaces {@code initial}, Enter hands the text to {@code commit}. */
    protected void beginEdit(Object key, String initial, Consumer<String> commit) {
        commitEdit();
        editKey = key;
        editText.set(initial);
        // Typing replaces the old value; an arrow key keeps it to change a part.
        editText.selectAll();
        editCommit = commit;
        UiSound.EXPAND.play();
    }

    protected void commitEdit() {
        if (editKey == null) return;
        Consumer<String> commit = editCommit;
        String value = editText.toString();
        editKey = null;
        editCommit = null;
        try {
            commit.accept(value);
        } catch (RuntimeException ignored) {
            // Invalid input: the property keeps its previous value.
            UiSound.ERROR.play();
        }
    }

    /** The value edit started with {@code key} is open. */
    protected boolean editingKey(Object key) {
        return key != null && editKey == key;
    }

    /** The text of the open value edit, for a layout that draws its own field. */
    protected TextEdit editBuffer() {
        return editText;
    }

    /** Right-aligned value that turns into an inline text field while it is being edited. */
    private void valueText(Object key, String value, float right, float y, float h, int color, float maxWidth) {
        SmoothFont font = fValue;
        if (editKey == key) {
            String text = editText.toString();
            float w = Math.min(maxWidth, Math.max(26, font.width(text) + 10));
            GlassTheme.field(right - w, y + 2, w, h - 4, 4, 1, pal);
            drawEdit(editText, font, right - w + 4, y, w - 8, h, pal.text, true, true);
        } else {
            font.drawRight(font.trim(value, maxWidth), right, font.centerY(y, h) - 0.5f, color);
        }
    }

    protected static String label(String name) {
        String text = name.replace('-', ' ').replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ================================================================ input

    protected static void clearKeybind(Module module) {
        module.setKey(module instanceof GuiModule ? Keyboard.KEY_RSHIFT : 0);
    }

    @Override
    public void handleMouseInput() throws IOException {
        int delta = Mouse.getEventDWheel();
        if (delta != 0) wheel += delta;
        super.handleMouseInput();
    }

    @Override
    public void mouseClicked(int x, int y, int button) {
        if (!interactive) return;
        float cx = Mouse.getEventX() / ui, cy = (mc.displayHeight - Mouse.getEventY() - 1) / ui;
        if (binding != null) {
            if (button != 0) binding.setKey(button - 100);
            binding = null;
            UiSound.CLICK.play();
            return;
        }
        commitEdit();
        searchFocus = false;
        for (int i = hitCount - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (cx >= hit.x0 && cx < hit.x1 && cy >= hit.y0 && cy < hit.y1) {
                hit.click.click(cx, cy, button);
                return;
            }
        }
    }

    @Override
    public void mouseReleased(int x, int y, int button) {
        if (button == 0) drag = null;
    }

    @Override
    public void keyTyped(char typed, int key) {
        if (closing) return;
        if (key == Keyboard.KEY_ESCAPE && Keyboard.isRepeatEvent()) return;
        if (binding != null) {
            // Key 0 is a character from an input method, not a key that can be bound.
            if (key == 0) return;
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_0) clearKeybind(binding);
            else binding.setKey(key);
            binding = null;
            UiSound.CLICK.play();
            return;
        }
        if (editKey != null) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) commitEdit();
            else if (key == Keyboard.KEY_ESCAPE) { editKey = null; editCommit = null; }
            else typeInto(editText, typed, key);
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            if (!search.isEmpty()) search.clear(); else close();
            searchFocus = false;
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            searchFocus = false;
            return;
        }
        Module gui = guiModule();
        if (key != 0 && gui != null && key == gui.getKey() && search.isEmpty() && !searchFocus && openTime > 0.4f && !Keyboard.isRepeatEvent()) {
            close();
            return;
        }
        // A leading space would only hide every result.
        if (typed == ' ' && search.isEmpty()) return;
        typeInto(search, typed, key);
    }

    // ================================================================ saved configs

    /**
     * The saved configs, as both layouts show them: an auto-save switch, Save / New / Folder, and
     * one row per config file. Left click loads a config, right click arms a delete that a second
     * left click confirms. The caller places it, clips it and scrolls it.
     */
    protected final class ConfigList {
        private final Object newKey = new Object();
        private final Map<String, Interaction> touches = new HashMap<>();
        private final Interaction autoTouch = new Interaction(), saveTouch = new Interaction(),
                newTouch = new Interaction(), folderTouch = new Interaction();
        private final Anim.Spring autoKnob = new Anim.Spring(ConfigManager.isAutoSave() ? 1 : 0, 520, 30);
        private List<ConfigManager.Entry> entries = new ArrayList<>();
        private int seenRevision = -1;
        private long scannedAt;
        private String armedDelete;
        private long armedUntil;
        // Sized for the width it is drawn at: roomy in the sidebar page, tight in a 126-wide panel.
        private boolean compact;
        private float rowH, gap, pad, radius;

        private void measure(float w) {
            compact = w < 200;
            rowH = compact ? 18 : 26;
            gap = compact ? 2 : 5;
            pad = compact ? 5 : 0;
            radius = compact ? 6 : 8;
        }

        /** Re-reads the folder after a save or delete, and every couple of seconds for outside changes. */
        public void refresh(boolean force) {
            long now = System.currentTimeMillis();
            if (force || seenRevision != ConfigManager.revision() || now - scannedAt > 2000) {
                entries = ConfigManager.list();
                seenRevision = ConfigManager.revision();
                scannedAt = now;
            }
        }

        public int count() {
            return entries.size();
        }

        /** Height of everything at width {@code w}. */
        public float height(float w) {
            measure(w);
            refresh(false);
            return 2 + (rowH + gap) * 2 + gap + 4 + Math.max(1, entries.size()) * (rowH + gap) + 2;
        }

        public void draw(float x, float y, float w) {
            measure(w);
            refresh(false);
            float rx = x + pad, rw = w - pad * 2, yy = y + 2;
            drawAutoSave(rx, yy, rw);
            yy += rowH + gap;
            drawActions(rx, yy, rw);
            yy += rowH + gap * 2 + 4;
            if (entries.isEmpty()) {
                fSmall.drawCentered("No saved configs", rx + rw / 2, fSmall.centerY(yy, rowH) - 0.5f, pal.faint);
                return;
            }
            long now = System.currentTimeMillis();
            if (armedDelete != null && now > armedUntil) armedDelete = null;
            for (int i = 0; i < entries.size(); i++) {
                drawRow(entries.get(i), i, rx, yy, rw, now);
                yy += rowH + gap;
            }
        }

        private void drawAutoSave(float x, float y, float w) {
            boolean on = ConfigManager.isAutoSave();
            boolean hovered = over(x, y, w, rowH);
            autoTouch.update(hovered, hovered && Mouse.isButtonDown(0), dt);
            float hot = autoTouch.hot, mid = y + rowH / 2;
            GlassTheme.card(x, y, w, rowH, radius, 0.2f + 0.6f * hot, pal);
            if (hovered) spot(x, y, w, rowH, radius, 50, 0x14ffffff);

            float sw2 = compact ? 18 : 22, sh2 = compact ? 10 : 12, sx = x + w - sw2 - (compact ? 6 : 12), sy = mid - sh2 / 2;
            float k = autoKnob.update(on ? 1 : 0, dt), amount = Draw.clamp01(k);
            int accent = pal.accentFill(GlassTheme.accent());
            if (amount > 0.01f) glow(sx, sy, sw2, sh2, sh2 / 2, 6, Draw.alpha(accent, 0.4f * amount));
            Draw.rect(sx, sy, sw2, sh2, sh2 / 2, Draw.mix(pal.wash(0x40ffffff), accent, amount));
            float kr = sh2 * 0.36f, kx = sx + sh2 / 2 + (sw2 - sh2) * Math.max(-0.08f, Math.min(1.08f, k));
            Draw.shadow(kx - kr, mid - kr + 0.6f, kr * 2, kr * 2, kr, 2.5f, 0x50000000);
            Draw.circle(kx, mid, kr, 0xffffffff);

            SmoothFont font = compact ? fValue : fRow;
            float end = font.draw("Auto-save", x + (compact ? 7 : 12), font.centerY(y, rowH) - 0.5f, Draw.mix(pal.muted, pal.text, Math.max(hot, amount)));
            if (!compact) {
                String hint = on ? "Changes are written to the current config" : "Only saved when you press Save";
                if (sx - end - 16 > fTiny.width(hint)) fTiny.draw(hint, end + 8, fTiny.centerY(y, rowH), pal.faint);
            }
            hit(x, y, w, rowH, (cx, cy, button) -> {
                if (button != 0) return;
                autoTouch.pulse();
                boolean next = !ConfigManager.isAutoSave();
                ConfigManager.setAutoSave(next);
                UiSound.toggle(next);
            });
        }

        private void drawActions(float x, float y, float w) {
            if (editKey == newKey) {
                drawNameField(x, y, w);
                return;
            }
            float bw = (w - gap * 2) / 3f;
            button(saveTouch, "Save", x, y, bw, () -> {
                ConfigManager.saveCurrent();
                UiSound.SUCCESS.play();
            });
            button(newTouch, "New", x + bw + gap, y, bw, () -> beginEdit(newKey, "", this::createNamed));
            button(folderTouch, "Folder", x + (bw + gap) * 2, y, bw, this::openFolder);
        }

        private void button(Interaction touch, String label, float x, float y, float w, Runnable action) {
            boolean hovered = over(x, y, w, rowH);
            touch.update(hovered, hovered && Mouse.isButtonDown(0), dt);
            float hot = touch.hot;
            Draw.pushTransform(x + w / 2, y + rowH / 2, touch.scale(0.04f), 0, 0);
            GlassTheme.card(x, y, w, rowH, radius, 0.3f + 0.7f * hot, pal);
            if (hovered) spot(x, y, w, rowH, radius, 40, 0x18ffffff);
            SmoothFont font = compact ? fSmall : fValue;
            font.drawCentered(label, x + w / 2, font.centerY(y, rowH) - 0.5f, Draw.mix(pal.muted, pal.text, hot));
            Draw.popTransform();
            hit(x, y, w, rowH, (cx, cy, button) -> {
                if (button != 0) return;
                touch.pulse();
                action.run();
            });
        }

        private void drawNameField(float x, float y, float w) {
            GlassTheme.field(x, y, w, rowH, radius, 1, pal);
            SmoothFont font = compact ? fSmall : fValue;
            float tx = x + 7, ty = font.centerY(y, rowH) - 0.5f;
            if (editText.isEmpty()) {
                Draw.pushClip(x + 2, y, w - 4, rowH);
                font.draw(compact ? "Name, Enter" : "New config name (letters, digits, _ -), Enter to save", tx + 2, ty, pal.faint);
                Draw.popClip();
            }
            drawEdit(editText, font, tx, y, w - 14, rowH, pal.text, true, false);
        }

        private void createNamed(String value) {
            String typed = value.trim();
            if (typed.isEmpty()) return;
            String name = Config.sanitize(typed);
            // Nothing usable was typed (sanitize falls back to "default"): refuse rather than overwrite it.
            if (name.equals("default") && !typed.equalsIgnoreCase("default")) throw new IllegalArgumentException("invalid config name");
            ConfigManager.save(name);
            refresh(true);
            UiSound.SUCCESS.play();
        }

        private void openFolder() {
            File dir = Config.CONFIG_DIR.getAbsoluteFile();
            // Off the client thread: the desktop call can stall for a moment.
            Thread thread = new Thread(() -> {
                try {
                    java.awt.Desktop.getDesktop().open(dir);
                } catch (Throwable e) {
                    try {
                        org.lwjgl.Sys.openURL(dir.toURI().toString());
                    } catch (Throwable ignored) {
                        Myau.LOGGER.warn("Could not open {}", dir, e);
                    }
                }
            }, "BetterMyau-open-folder");
            thread.setDaemon(true);
            thread.start();
            UiSound.CLICK.play();
        }

        private void drawRow(ConfigManager.Entry entry, int index, float x, float y, float w, long now) {
            if (!Draw.visible(x - CULL_MARGIN, y - CULL_MARGIN, w + CULL_MARGIN * 2, rowH + CULL_MARGIN * 2)) return;
            Interaction touch = touches.computeIfAbsent(entry.name, k -> new Interaction());
            boolean hovered = over(x, y, w, rowH);
            touch.update(hovered, hovered && (Mouse.isButtonDown(0) || Mouse.isButtonDown(1)), dt);
            float hot = touch.hot;
            boolean current = entry.name.equals(ConfigManager.current());
            boolean armed = entry.name.equals(armedDelete);

            Draw.pushTransform(x + w / 2, y + rowH / 2, touch.scale(0.025f), 0, 0);
            Draw.rect(x, y, w, rowH, radius, pal.wash(0.055f + 0.035f * hot));
            Draw.outline(x, y, w, rowH, radius, 0.6f, pal.wash(0.13f + 0.08f * hot), pal.wash(0.04f));
            int textColor = Draw.mix(pal.muted, pal.text, hot);
            if (current) {
                int a = pal.accentFill(GlassTheme.accent(index)), b = pal.accentFill(GlassTheme.accent(index + 3L));
                Draw.gradientH(x, y, w, rowH, radius, Draw.alpha(a, pal.fill(0.26f)), Draw.alpha(b, pal.fill(0.09f)));
                Draw.outline(x, y, w, rowH, radius, 0.8f, Draw.alpha(a, 0.6f), Draw.alpha(b, 0.22f));
                textColor = pal.text;
            }
            if (armed) {
                Draw.rect(x, y, w, rowH, radius, Draw.alpha(GlassTheme.BAD, 0.16f));
                Draw.outline(x, y, w, rowH, radius, 0.8f, Draw.alpha(GlassTheme.BAD, 0.7f), Draw.alpha(GlassTheme.BAD, 0.3f));
            }
            if (hovered) spot(x, y, w, rowH, radius, 60, 0x16ffffff);

            float tx = x + (compact ? 7 : 12), mid = y + rowH / 2;
            if (current) {
                int ink = pal.accentInk(GlassTheme.accent(index));
                Draw.polyline(1.2f, ink, tx, mid, tx + 2.2f, mid + 2.2f, tx + 6.4f, mid - 2.6f);
                tx += 11;
            }
            String right;
            int rightColor;
            if (armed) {
                right = compact ? "Delete?" : "Click again to delete";
                rightColor = GlassTheme.BAD;
            } else if (current && ConfigManager.isDirty() && !ConfigManager.isAutoSave()) {
                right = "unsaved";
                rightColor = GlassTheme.WARN;
            } else {
                right = ago(now - entry.modified);
                rightColor = pal.faint;
            }
            float rightEdge = x + w - (compact ? 7 : 12);
            fTiny.drawRight(right, rightEdge, fTiny.centerY(y, rowH) - 0.5f, rightColor);
            SmoothFont font = compact ? fValue : fRow;
            float room = rightEdge - fTiny.width(right) - 6 - tx;
            font.draw(font.trim(entry.name, room), tx, font.centerY(y, rowH) - 0.5f, textColor);
            Draw.popTransform();

            hit(x, y, w, rowH, (cx, cy, button) -> {
                touch.pulse();
                if (button == 0) {
                    if (entry.name.equals(armedDelete)) {
                        armedDelete = null;
                        if (ConfigManager.delete(entry.name)) {
                            UiSound.DELETE.play();
                            refresh(true);
                        } else {
                            UiSound.ERROR.play();
                        }
                    } else {
                        armedDelete = null;
                        ConfigManager.load(entry.name);
                        UiSound.SELECT.play();
                    }
                } else if (button == 1) {
                    if (entry.name.equals(ConfigManager.current())) {
                        // The config in use cannot be deleted.
                        UiSound.ERROR.play();
                        return;
                    }
                    armedDelete = entry.name;
                    armedUntil = System.currentTimeMillis() + 3000;
                    UiSound.CLICK.play();
                }
            });
        }

        private String ago(long ms) {
            long s = Math.max(0, ms / 1000);
            if (s < 45) return "just now";
            if (s < 3600) return Math.max(1, s / 60) + "m ago";
            if (s < 86400) return s / 3600 + "h ago";
            return s / 86400 + "d ago";
        }
    }

    // ================================================================ scrolling

    /** A scroll offset that eases towards where the wheel put it. */
    protected final class Scroller {
        float value, target, max;

        void limit(float newMax) {
            max = Math.max(0, newMax);
            target = Math.max(0, Math.min(max, target));
        }

        void update() {
            value += (target - value) * (1 - (float) Math.exp(-18 * dt));
            if (Math.abs(target - value) < 0.01f) value = target;
        }

        /** @return false when already at that end */
        boolean nudge(float amount) {
            float next = Math.max(0, Math.min(max, target + amount));
            if (next == target) return false;
            target = next;
            return true;
        }

        void jump(float to) {
            target = value = Math.max(0, Math.min(max, to));
        }
    }

    // ================================================================ a module and its settings

    /** One module as a layout shows it: its animations and, once opened, its setting rows. */
    protected abstract class ModuleView {
        final Module module;
        final String nameLower;
        final Anim.Smooth enabledAnim, expand = new Anim.Smooth(0, 15);
        final Interaction touch = new Interaction();
        List<Row> rows;
        boolean expanded;
        float settingsHeight;
        private int keyCode = Integer.MIN_VALUE;
        private String keyName;

        ModuleView(Module module) {
            this.module = module;
            this.nameLower = module.getName().toLowerCase(Locale.ROOT);
            this.enabledAnim = new Anim.Smooth(module.isEnabled() ? 1 : 0, 14);
        }

        boolean matches(String query) {
            return query.isEmpty() || nameLower.contains(query);
        }

        /** Name of the bound key, or null when the module has none. */
        String keyName() {
            int key = module.getKey();
            if (key != keyCode) {
                keyCode = key;
                keyName = key == 0 ? null : KeyBindUtil.getKeyName(key);
            }
            return keyName;
        }

        /** Advance the expand animation and lay the rows out. @return how far open, 0..1 */
        float layoutSettings() {
            float open = expand.update(expanded, dt);
            settingsHeight = 0;
            if (open > 0.002f) {
                if (rows == null) rows = buildRows(module);
                for (Row row : rows) settingsHeight += row.layout();
                settingsHeight += 6;
            }
            return Anim.clamp01(open);
        }

        /**
         * @param clipX,clipW the column the settings may draw in
         * @param x,w         the rows themselves
         * @param boxed       draw the recessed group behind the rows
         */
        void drawSettings(float clipX, float clipW, float x, float y, float w, float open, boolean boxed) {
            if (rows == null) return;
            Draw.pushClip(clipX, y, clipW, settingsHeight * open);
            Draw.pushAlpha(open);
            if (boxed) {
                Draw.rect(x, y + 1, w, settingsHeight - 4, 6, pal.sunken);
                Draw.outline(x, y + 1, w, settingsHeight - 4, 6, 0.6f, pal.wash(0x06ffffff), pal.wash(0x1cffffff));
            }
            float yy = y + 2;
            for (Row row : rows) {
                if (row.height > 0.2f) {
                    // Rows scrolled out of view are not drawn; the margin keeps a glow that
                    // reaches into view from a row just outside it.
                    if (Draw.visible(x, yy - CULL_MARGIN, w, row.height + CULL_MARGIN * 2)) row.draw(x, yy, w);
                    else row.skip();
                }
                yy += row.height;
            }
            Draw.popAlpha();
            Draw.popClip();
        }
    }

    private List<Row> buildRows(Module module) {
        List<Row> rows = new ArrayList<>();
        rows.add(new BindRow(module));
        List<Property<?>> properties = Myau.propertyManager.properties.get(module.getClass());
        if (properties == null) return rows;
        for (Property<?> p : properties) {
            if (p instanceof BooleanProperty) rows.add(new ToggleRow((BooleanProperty) p));
            else if (p instanceof FloatProperty) rows.add(new SliderRow(p, new FloatSlider((FloatProperty) p)));
            else if (p instanceof PercentProperty) rows.add(new SliderRow(p, new PercentageSlider((PercentProperty) p)));
            else if (p instanceof IntProperty) rows.add(new SliderRow(p, new IntSlider((IntProperty) p)));
            else if (p instanceof ModeProperty) rows.add(new ModeRow((ModeProperty) p));
            else if (p instanceof ColorProperty) rows.add(new ColorRow((ColorProperty) p));
            else if (p instanceof TextProperty) rows.add(new TextRow((TextProperty) p));
            else rows.add(new InfoRow(p));
        }
        return rows;
    }

    // ================================================================ setting rows

    protected abstract class Row {
        final Property<?> property;
        final String label;
        final Anim.Smooth visible = new Anim.Smooth(1, 18);
        float height;

        Row(Property<?> property, String label) {
            this.property = property;
            this.label = label;
        }

        Row(Property<?> property) {
            this(property, label(property.getName()));
        }

        abstract float base();

        float layout() {
            float shown = visible.update(property == null || property.isVisible(), dt);
            height = base() * Anim.clamp01(shown);
            return height;
        }

        void draw(float x, float y, float w) {
            float shown = Anim.clamp01(visible.value);
            if (shown < 0.999f) Draw.pushAlpha(shown);
            paint(x, y, w);
            if (shown < 0.999f) Draw.popAlpha();
        }

        abstract void paint(float x, float y, float w);

        /** Not drawn this frame (out of view): put animations where they would have ended up. */
        void skip() { }

        void name(float x, float y, float h, float maxWidth) {
            fValue.draw(fValue.trim(label, maxWidth), x + 8, fValue.centerY(y, h) - 0.5f, pal.muted);
        }
    }

    private final class InfoRow extends Row {
        InfoRow(Property<?> p) { super(p); }
        @Override float base() { return 17; }
        @Override void paint(float x, float y, float w) {
            name(x, y, 17, w * 0.55f);
            valueText(this, String.valueOf(property.getValue()), x + w - 8, y, 17, pal.text, w * 0.4f);
        }
    }

    private final class BindRow extends Row {
        final Module module;
        final Interaction touch = new Interaction();
        int keyCode = Integer.MIN_VALUE;
        String keyName;
        BindRow(Module module) { super(null, "Keybind"); this.module = module; }
        @Override float base() { return 19; }
        @Override void paint(float x, float y, float w) {
            name(x, y, 19, w * 0.5f);
            boolean listening = binding == module;
            if (module.getKey() != keyCode) {
                keyCode = module.getKey();
                keyName = keyCode == 0 ? "None" : KeyBindUtil.getKeyName(keyCode);
            }
            String text = listening ? "Esc to clear" : keyName == null ? "Unknown" : keyName;
            SmoothFont font = fSmall;
            float kw = Math.max(24, font.width(text) + 12), kx = x + w - 8 - kw, ky = y + 3, kh = 13;
            boolean hovered = over(kx, ky, kw, kh);
            touch.update(hovered, hovered && Mouse.isButtonDown(0), dt);
            Draw.pushTransform(kx + kw / 2, ky + kh / 2, touch.scale(0.06f), 0, 0);
            GlassTheme.card(kx, ky, kw, kh, 4, 0.3f + 0.7f * touch.hot, pal);
            if (listening) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 160.0);
                int accent = pal.accentInk(GlassTheme.accent());
                glow(kx, ky, kw, kh, 4, 6, Draw.alpha(accent, 0.25f + 0.3f * pulse));
                Draw.outline(kx, ky, kw, kh, 4, 0.9f, Draw.alpha(accent, 0.6f + 0.4f * pulse), Draw.alpha(accent, 0.5f));
            }
            font.drawCentered(text, kx + kw / 2, font.centerY(ky, kh) - 0.5f, listening ? pal.text : Draw.mix(pal.muted, pal.text, touch.hot));
            Draw.popTransform();
            hit(x, y, w, 19, (cx, cy, button) -> {
                touch.pulse();
                if (button == 0) binding = module;
                else if (button == 1) clearKeybind(module);
                if (button == 0 || button == 1) UiSound.CLICK.play();
            });
        }
    }

    private final class ToggleRow extends Row {
        final BooleanProperty value;
        final Anim.Spring knob;
        final Anim.Smooth tint, hold = new Anim.Smooth(0, 20);
        ToggleRow(BooleanProperty p) {
            super(p);
            value = p;
            knob = new Anim.Spring(p.getValue() ? 1 : 0, 520, 30);
            tint = new Anim.Smooth(p.getValue() ? 1 : 0, 16);
        }
        @Override float base() { return 17; }
        @Override void skip() {
            float on = value.getValue() ? 1 : 0;
            knob.snap(on);
            tint.snap(on);
            hold.snap(0);
        }
        @Override void paint(float x, float y, float w) {
            name(x, y, 17, w - 44);
            boolean on = value.getValue();
            float k = knob.update(on ? 1 : 0, dt), t = tint.update(on, dt);
            float sx = x + w - 8 - 19, sy = y + 3.5f, sw2 = 19, sh2 = 10;
            int accent = pal.accentFill(GlassTheme.accent());
            if (t > 0.01f) glow(sx, sy, sw2, sh2, 5, 5, Draw.alpha(accent, 0.35f * t));
            Draw.rect(sx, sy, sw2, sh2, 5, Draw.mix(pal.wash(0x40ffffff), accent, t));
            float kx = sx + 5 + (sw2 - 10) * Math.max(-0.08f, Math.min(1.08f, k));
            // Held down, the knob stretches towards the side it is about to move to.
            float stretch = 2.6f * hold.update(over(x, y, w, 17) && Mouse.isButtonDown(0), dt);
            float knobX = kx - 3.6f - (k > 0.5f ? stretch : 0), knobW = 7.2f + stretch;
            Draw.shadow(knobX + 0.1f, sy + 2, knobW - 0.2f, 7, 3.5f, 2.5f, 0x50000000);
            Draw.rect(knobX, sy + sh2 / 2 - 3.6f, knobW, 7.2f, 3.6f, 0xffffffff);
            hit(x, y, w, 17, (cx, cy, button) -> {
                if (button != 0) return;
                value.setValue(!value.getValue());
                (value.getValue() ? UiSound.TOGGLE_ON : UiSound.TOGGLE_OFF).play();
            });
        }
    }

    private final class SliderRow extends Row {
        final Slider slider;
        final Anim.Smooth shown = new Anim.Smooth(-1, 22), grab = new Anim.Smooth(0, 16);
        boolean dragging;
        double textInput = Double.NaN;
        String text = "";
        SliderRow(Property<?> p, Slider slider) { super(p); this.slider = slider; }
        @Override float base() { return 25; }
        @Override void skip() {
            shown.snap(-1);
            grab.snap(0);
        }

        float ratio() {
            double span = slider.getMax() - slider.getMin();
            return span <= 0 ? 0 : Anim.clamp01((float) ((slider.getInput() - slider.getMin()) / span));
        }

        /** The formatted value, rebuilt only when the number behind it changes. */
        String valueString() {
            double input = slider.getInput();
            if (input != textInput) {
                textInput = input;
                text = slider.getValueString();
            }
            return text;
        }

        void set(float cx, float tx, float tw) {
            double fraction = Anim.clamp01((cx - tx) / tw);
            double value = slider.getMin() + fraction * (slider.getMax() - slider.getMin());
            double step = slider.getIncrement();
            if (step > 0) value = Math.round(value / step) * step;
            String before = slider.getValueString();
            slider.setValue(Math.max(slider.getMin(), Math.min(slider.getMax(), value)));
            if (!before.equals(slider.getValueString())) UiSound.sliderTick(ratio());
        }

        @Override void paint(float x, float y, float w) {
            name(x, y, 14, w * 0.58f);
            valueText(this, valueString(), x + w - 8, y, 14, pal.text, w * 0.4f);
            float tx = x + 8, tw = w - 16, ty = y + 17.5f;
            if (dragging && drag == null) dragging = false;
            boolean hovered = over(x, y + 12, w, 13);
            float target = ratio();
            float r = shown.value < 0 ? (shown.value = target) : shown.update(target, dt);
            float g = grab.update(hovered || dragging, dt);
            int a = pal.accentFill(GlassTheme.accent()), b = pal.accentFill(GlassTheme.accent(4));
            Draw.rect(tx, ty, tw, 3, 1.5f, pal.wash(0x38ffffff));
            if (r > 0.001f) {
                glow(tx, ty, tw * r, 3, 1.5f, 4, Draw.alpha(a, 0.3f + 0.25f * g));
                Draw.gradientH(tx, ty, Math.max(3, tw * r), 3, 1.5f, a, b);
            }
            float kx = tx + tw * r, radius = 3.2f + 1.1f * g;
            Draw.shadow(kx - radius, ty + 1.5f - radius + 0.8f, radius * 2, radius * 2, radius, 3, 0x60000000);
            Draw.circle(kx, ty + 1.5f, radius, 0xffffffff);
            hit(x, y + 12, w, 13, (cx, cy, button) -> {
                if (button == 0) {
                    dragging = true;
                    set(cx, tx, tw);
                    drag = (dx, dy) -> set(dx, tx, tw);
                } else if (button == 1) {
                    slider.stepping(cx >= tx + tw / 2);
                }
            });
            hit(x + w * 0.5f, y, w * 0.5f, 12, (cx, cy, button) -> {
                if (button == 0) beginEdit(this, slider.getValueString(), slider::setValueString);
            });
        }
    }

    private final class ModeRow extends Row {
        /** Longer lists scroll inside the row instead of growing it without end. */
        static final int MAX_SHOWN = 8;
        static final float OPTION_H = 14;
        final ModeProperty mode;
        final String[] options;
        final Anim.Smooth open = new Anim.Smooth(0, 16), hoverAt = new Anim.Smooth(0, 24), hoverShow = new Anim.Smooth(0, 18);
        final Anim.Spring selected;
        final Scroller list = new Scroller();
        boolean expanded;
        ModeRow(ModeProperty p) {
            super(p);
            mode = p;
            options = p.getValuePrompt().split(", ");
            selected = Anim.spring(p.getValue(), 0.3f, 0.72f);
        }

        float listHeight() {
            return Math.min(options.length, MAX_SHOWN) * OPTION_H;
        }

        @Override float base() { return 17 + listHeight() * Anim.clamp01(open.update(expanded, dt)); }
        @Override void skip() {
            selected.snap(mode.getValue());
            hoverShow.snap(0);
        }
        @Override void paint(float x, float y, float w) {
            name(x, y, 17, w * 0.5f);
            float o = Anim.clamp01(open.value);
            SmoothFont font = fValue;
            int accent = pal.accentInk(GlassTheme.accent());
            font.drawRight(font.trim(mode.getModeString(), w * 0.42f), x + w - 17, font.centerY(y, 17) - 0.5f, accent);
            Draw.chevron(x + w - 10, y + 8.5f, 2.2f, 90 * o, 1, pal.muted);
            hit(x, y, w, 17, (cx, cy, button) -> {
                if (button == 0) {
                    expanded = !expanded;
                    if (expanded) {
                        // Open with the current choice in view.
                        list.limit(options.length * OPTION_H - listHeight());
                        list.jump((mode.getValue() - (MAX_SHOWN - 1) / 2f) * OPTION_H);
                    }
                    (expanded ? UiSound.EXPAND : UiSound.COLLAPSE).play();
                } else if (button == 1) {
                    mode.nextMode();
                    UiSound.CLICK.play();
                }
            });
            if (o <= 0.002f) return;
            float listY = y + 17, listH = listHeight();
            list.limit(options.length * OPTION_H - listH);
            list.update();
            float top = listY - list.value;
            Draw.pushClip(x, listY, w, listH * o);
            Draw.pushAlpha(o);
            if (list.max > 0) scrollHit(x, listY, w, listH * o, notches -> list.nudge(-notches * OPTION_H * 2));
            // One hover highlight and one selection dot, both gliding between the options.
            int hoveredIndex = -1;
            if (over(x + 4, listY, w - 8, listH)) {
                int index = (int) Math.floor((my - top) / OPTION_H);
                if (index >= 0 && index < options.length) hoveredIndex = index;
            }
            if (hoveredIndex >= 0) {
                if (hoverShow.value < 0.05f) hoverAt.snap(hoveredIndex);
                hoverAt.update(hoveredIndex, dt);
            }
            float glow = hoverShow.update(hoveredIndex >= 0, dt);
            if (glow > 0.01f) Draw.rect(x + 5, top + 0.5f + hoverAt.value * OPTION_H, w - 10, 13, 4, pal.wash(0.095f * glow));
            float dot = top + selected.update(mode.getValue(), dt) * OPTION_H + 7;
            glow(x + 10, dot - 2, 4, 4, 2, 4, Draw.alpha(accent, 0.6f));
            Draw.circle(x + 12, dot, 2, accent);
            int first = Math.max(0, (int) Math.floor(list.value / OPTION_H));
            int last = Math.min(options.length - 1, (int) Math.floor((list.value + listH) / OPTION_H));
            for (int i = first; i <= last; i++) {
                float oy = top + i * OPTION_H;
                boolean lit = mode.getValue() == i || hoveredIndex == i;
                font.draw(font.trim(options[i], w - 30), x + 19, font.centerY(oy, OPTION_H) - 0.5f, lit ? pal.text : pal.muted);
                final int index = i;
                hit(x + 4, oy, w - 8, OPTION_H, (cx, cy, button) -> {
                    if (button == 0) {
                        mode.setValue(index);
                        expanded = false;
                        UiSound.CLICK.play();
                    }
                });
            }
            if (list.max > 0) {
                float track = listH - 6, thumb = Math.max(10, track * listH / (options.length * OPTION_H));
                Draw.rect(x + w - 4, listY + 3 + (track - thumb) * (list.value / list.max), 1.5f, thumb, 0.75f, pal.wash(0x50ffffff));
            }
            Draw.popAlpha();
            Draw.popClip();
        }
    }

    private final class ColorRow extends Row {
        final ColorProperty color;
        final Anim.Smooth open = new Anim.Smooth(0, 16);
        final float[] hsb = new float[3];
        int known = -1, hexOf = -1;
        String hex = "";
        boolean expanded;
        ColorRow(ColorProperty p) { super(p); color = p; }
        @Override float base() { return 17 + 56 * Anim.clamp01(open.update(expanded, dt)); }

        void sync() {
            int rgb = color.getValue() & 0xffffff;
            if (rgb != known) {
                known = rgb;
                Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, hsb);
            }
        }

        void apply() {
            int rgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0xffffff;
            if (color.setValue(rgb)) {
                if (known != rgb) UiSound.sliderTick();
                known = rgb;
            }
        }

        @Override void paint(float x, float y, float w) {
            sync();
            name(x, y, 17, w * 0.45f);
            int rgb = 0xff000000 | (color.getValue() & 0xffffff);
            if (hexOf != rgb) {
                hexOf = rgb;
                hex = String.format("#%06X", rgb & 0xffffff);
            }
            float sx = x + w - 8 - 17;
            glow(sx, y + 4, 17, 9, 3, 4, Draw.fade(rgb, 0.35f));
            Draw.rect(sx, y + 4, 17, 9, 3, rgb);
            Draw.outline(sx, y + 4, 17, 9, 3, 0.6f, pal.wash(0x70ffffff), pal.wash(0x30ffffff));
            valueText(this, hex, sx - 4, y, 17, pal.faint, w * 0.4f);
            hit(x, y, w, 17, (cx, cy, button) -> {
                if (button == 0 && cx < sx - 2 && cx > x + w * 0.45f) {
                    beginEdit(this, String.format("%06X", color.getValue() & 0xffffff), text -> color.parseString(text.replace("#", "")));
                } else if (button == 0) {
                    expanded = !expanded;
                    (expanded ? UiSound.EXPAND : UiSound.COLLAPSE).play();
                }
            });
            float o = Anim.clamp01(open.value);
            if (o <= 0.002f) return;
            Draw.pushClip(x, y + 17, w, 56 * o);
            Draw.pushAlpha(o);
            float px = x + 8, py = y + 19, pw = w - 16, ph = 36;
            Draw.gradientH(px, py, pw, ph, 3, 0xffffffff, 0xff000000 | Color.HSBtoRGB(hsb[0], 1, 1));
            Draw.gradientV(px, py, pw, ph, 3, 0x00000000, 0xff000000);
            Draw.outline(px, py, pw, ph, 3, 0.6f, 0x40ffffff, 0x20ffffff);
            float cxp = px + pw * hsb[1], cyp = py + ph * (1 - hsb[2]);
            Draw.shadow(cxp - 3, cyp - 3, 6, 6, 3, 2.5f, 0x80000000);
            Draw.outline(cxp - 3.2f, cyp - 3.2f, 6.4f, 6.4f, 3.2f, 1.2f, 0xffffffff, 0xffffffff);
            hit(px, py, pw, ph, (cx, cy, button) -> {
                if (button != 0) return;
                Drag pick = (dx, dy) -> {
                    hsb[1] = Anim.clamp01((dx - px) / pw);
                    hsb[2] = 1 - Anim.clamp01((dy - py) / ph);
                    apply();
                };
                pick.drag(cx, cy);
                drag = pick;
            });
            float hy = py + ph + 5;
            Draw.hueBar(px, hy, pw, 5);
            float hx = px + pw * hsb[0];
            Draw.shadow(hx - 1.5f, hy - 1.5f, 3, 8, 1.5f, 2, 0x80000000);
            Draw.rect(hx - 1.5f, hy - 1.5f, 3, 8, 1.5f, 0xffffffff);
            hit(px, hy - 3, pw, 11, (cx, cy, button) -> {
                if (button != 0) return;
                Drag pick = (dx, dy) -> {
                    hsb[0] = Math.min(0.9999f, Anim.clamp01((dx - px) / pw));
                    apply();
                };
                pick.drag(cx, cy);
                drag = pick;
            });
            Draw.popAlpha();
            Draw.popClip();
        }
    }

    private final class TextRow extends Row {
        final TextProperty text;
        TextRow(TextProperty p) { super(p); text = p; }
        @Override float base() { return 17; }
        @Override void paint(float x, float y, float w) {
            name(x, y, 17, w * 0.42f);
            String value = text.getValue();
            boolean empty = value == null || value.isEmpty();
            valueText(this, empty ? "(empty)" : value, x + w - 8, y, 17, empty ? pal.faint : pal.text, w * 0.52f);
            hit(x, y, w, 17, (cx, cy, button) -> {
                if (button == 0) beginEdit(this, text.getValue(), text::setValue);
            });
        }
    }
}
