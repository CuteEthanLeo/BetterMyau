package myau.ui.glass;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import myau.ui.Component;
import myau.ui.GlassTheme;
import myau.ui.components.CategoryComponent;
import myau.ui.components.ModuleComponent;
import myau.ui.dataset.Slider;
import myau.ui.dataset.impl.FloatSlider;
import myau.ui.dataset.impl.IntSlider;
import myau.ui.dataset.impl.PercentageSlider;
import myau.util.FileUtil;
import myau.util.KeyBindUtil;
import net.minecraft.client.renderer.GlStateManager;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "Augustus" click GUI layout, after the window of the old Augustus client: one dark window
 * with the selected category's modules listed down the left, the categories as tabs along the
 * top, and the right-clicked module's settings as plain "name: value" lines. Left click toggles a
 * module, right click shows its settings, middle click binds it; typing searches every category.
 * The window moves by its title bar, resizes from its corner and can fill the screen.
 * <p>
 * Text is either the client's smooth font or Minecraft's own (the {@code font} setting of the
 * ClickGui module); the window keeps its dark look in every theme, the accent is the HUD colour.
 */
public class AugustusClickGui extends ClickGuiBase {
    private static final float DEFAULT_W = 380, DEFAULT_H = 260, MIN_W = 300, MIN_H = 200, RADIUS = 8;
    private static final float TITLE_H = 16, LIST_W = 88, ROW_H = 14, SPLIT_X = 95, TABS_BOTTOM = 42;
    private static final float SETTINGS_X = 100, SETTINGS_TOP = 46, BAR_W = 90, PICKER = 100, CULL = 12;
    private static final int BACKGROUND = 0xc8191919, CHROME = 0xff222222, TITLE_TEXT = 0xffcccccc,
            LABEL = 0xff999999, VALUE = 0xffcccccc, TRUE = 0xff00b400, FALSE = 0xffb40000,
            IDLE = 0xffaaaaaa, TAB_IDLE = 0xffb4b4b4, MODE_IDLE = 0xff777777, BUTTON = 0xff888888,
            CLOSE_HOT = 0xffff5555, TRACK = 0xff222222, HINT = 0xff777777;
    private static final String CONFIGS = "Configs";
    private static final Object WINDOW = new Object(), POPUP = new Object();

    private final List<Tab> tabs = new ArrayList<>();
    private final List<Module> results = new ArrayList<>();
    private final Scroller resultScroll = new Scroller(), settingsScroll = new Scroller(), configScroll = new Scroller();
    private final File layoutFile = new File("./config/Myau/", "clickgui-augustus.json");
    private final ConfigList configList = new ConfigList();
    private final Map<Property<?>, Slider> sliders = new HashMap<>();
    private final Map<ModeProperty, String[]> modeOptions = new HashMap<>();
    private final Anim.Spring underlineX = Anim.spring(0, 0.25f, 0.85f), underlineW = Anim.spring(0, 0.25f, 0.85f);
    private final Anim.Smooth gripHot = new Anim.Smooth(0, 16);

    private float wx, wy, ww = DEFAULT_W, wh = DEFAULT_H;
    private float normalX, normalY, normalW, normalH;
    private boolean placed, maximized, underlinePlaced;
    /** The tab shown on the right (a category or the configs) and the category listed on the left. */
    private int selectedTab, listTab;
    private Module selected;
    private Module revealing;
    private String resultQuery;

    // The colour picker that opens over the window from a colour setting.
    private ColorProperty picking;
    private float pickX, pickY;
    private final float[] pickHsb = new float[3];
    private int pickKnown = -1;

    // Fonts for this frame: smooth ones, or Minecraft's font at a crisp whole-pixel scale.
    private boolean pixel;
    private float pixelScale = 1;
    private final Txt text = new Txt(false), bold = new Txt(true);

    public AugustusClickGui() {
        for (CategoryComponent component : categories()) tabs.add(new Tab(component));
        if (tabs.isEmpty()) throw new IllegalStateException("no module categories");
        loadLayout();
    }

    // ================================================================ lifecycle

    @Override
    protected void onOpen() {
        if (!placed) {
            ww = Math.min(DEFAULT_W, sw - 20);
            wh = Math.min(DEFAULT_H, sh - 20);
            wx = (sw - ww) / 2f;
            wy = (sh - wh) / 2f;
            placed = true;
        }
        underlinePlaced = false;
        configList.refresh(true);
    }

    @Override
    protected void onClosed() {
        saveLayout();
    }

    @Override
    protected float exitDuration() {
        return 0.16f;
    }

    @Override
    public void reveal(Module module) {
        for (int i = 0; i < tabs.size(); i++) {
            if (!tabs.get(i).modules.contains(module)) continue;
            search.clear();
            selectedTab = listTab = i;
            select(module);
            revealing = module;
            return;
        }
    }

    private boolean onConfigs() {
        return selectedTab == tabs.size();
    }

    private void selectTab(int index) {
        if (index == selectedTab && search.isEmpty()) return;
        search.clear();
        searchFocus = false;
        commitEdit();
        selectedTab = index;
        if (index < tabs.size()) {
            listTab = index;
            if (selected != null && !tabs.get(index).modules.contains(selected)) select(null);
        } else {
            configList.refresh(true);
        }
        picking = null;
        UiSound.SELECT.play();
    }

    private void select(Module module) {
        if (module != selected) {
            commitEdit();
            settingsScroll.jump(0);
            picking = null;
        }
        selected = module;
        if (module == null) return;
        // A module picked from the search results takes its category along, so it is still
        // listed (with its marker) once the search is cleared.
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).modules.contains(module)) {
                selectedTab = listTab = i;
                break;
            }
        }
    }

    // ================================================================ frame

    @Override
    protected void drawContent(float backdrop) {
        GuiModule gui = guiModule();
        pixel = gui != null && gui.font.getValue() == GuiModule.FONT_MINECRAFT;
        pixelScale = Math.max(1, Math.round(ui)) / ui;
        text.smooth = SmoothFont.regular(8.5f);
        bold.smooth = SmoothFont.bold(8.5f);

        if (maximized) {
            wx = wy = 0;
            ww = sw;
            wh = sh;
        } else {
            ww = Math.max(Math.min(MIN_W, sw - 8), Math.min(ww, sw - 8));
            wh = Math.max(Math.min(MIN_H, sh - 8), Math.min(wh, sh - 8));
            wx = Math.max(0, Math.min(sw - ww, wx));
            wy = Math.max(0, Math.min(sh - wh, wy));
        }
        boolean popupHovered = picking != null && mx >= pickX - 3 && mx < pickX + PICKER + 3 && my >= pickY - 3 && my < pickY + PICKER + 17;
        hoverLayer = popupHovered ? POPUP : WINDOW;
        drawingLayer = WINDOW;

        // Entrance: a short rise and fade; exit: a quicker sink.
        float scale, fade, shift;
        if (closing) {
            float q = Anim.clamp01(closeTime / exitDuration());
            scale = 1 - 0.04f * Anim.inCubic(q);
            fade = 1 - q * q;
            shift = 6 * Anim.inCubic(q);
        } else {
            float p = Anim.clamp01(openTime / 0.3f);
            scale = Anim.lerp(0.95f, 1, Anim.outCubic(p));
            fade = Anim.outCubic(Math.min(1, p * 1.6f));
            shift = (1 - Anim.outCubic(p)) * 8;
        }

        boolean searching = !query.isEmpty();
        if (searching && !query.equals(resultQuery)) collectResults();
        if (!searching) resultQuery = null;

        Draw.pushTransform(wx + ww / 2, wy + wh / 2, scale, 0, shift);
        Draw.pushAlpha(fade);
        drawWindow();
        drawTabs(searching);
        drawModuleList(searching);
        if (onConfigs() && !searching) drawConfigs(); else drawSettings();
        drawGrip();
        if (picking != null) drawPicker();
        drawingLayer = WINDOW;
        Draw.popAlpha();
        Draw.popTransform();

        Draw.pushAlpha(backdrop);
        fTiny.drawRight("Left click: toggle   Right click: settings   Middle click: bind   Type to search",
                sw - 10, sh - 10 - fTiny.height(), Draw.fade(pal.faint, 0.9f));
        Draw.popAlpha();
    }

    private void collectResults() {
        resultQuery = query;
        results.clear();
        for (Tab tab : tabs) {
            for (Module module : tab.modules) {
                if (module.getName().toLowerCase(Locale.ROOT).contains(query)) results.add(module);
            }
        }
        resultScroll.jump(0);
    }

    // ================================================================ window chrome

    private void drawWindow() {
        int accent = GlassTheme.accent();
        Draw.shadow(wx, wy + 3, ww, wh, RADIUS, 16, 0x70000000);
        if (pal.blur) Draw.blurred(wx, wy, ww, wh, RADIUS, 1);
        Draw.rect(wx, wy, ww, wh, RADIUS, BACKGROUND);
        Draw.rect(wx, wy, ww, TITLE_H, RADIUS, RADIUS, 0, 0, CHROME);
        hit(wx, wy, ww, wh, (cx, cy, button) -> { });
        hit(wx, wy, ww - 76, TITLE_H, (cx, cy, button) -> {
            if (button != 0 || maximized) return;
            float offsetX = cx - wx, offsetY = cy - wy;
            drag = (dx, dy) -> {
                wx = dx - offsetX;
                wy = dy - offsetY;
            };
        });

        // Title, in spaced capitals.
        float tx = wx + 6, ty = bold.centerY(wy, TITLE_H);
        if (pixel) {
            bold.draw("AUGUSTUS CLICKGUI", tx, ty, TITLE_TEXT);
        } else {
            String title = "AUGUSTUS CLICKGUI";
            for (int i = 0; i < title.length(); i++) {
                String c = String.valueOf(title.charAt(i));
                bold.draw(c, tx, ty, TITLE_TEXT);
                tx += bold.width(c) + 0.7f;
            }
        }

        // What is being searched, between the title and the buttons.
        if (!search.isEmpty()) {
            String shown = "search: " + search;
            float right = wx + ww - 80;
            text.draw(text.trim(shown, Math.max(10, right - tx - 12)), Math.max(tx + 12, right - text.width(shown)), text.centerY(wy, TITLE_H), accent);
        }

        // Buttons, right to left: close, maximize, next layout, guide.
        float by = wy, bs = 16;
        float closeX = wx + ww - 18, maxX = wx + ww - 36, layoutX = wx + ww - 54, guideX = wx + ww - 72;
        float cy = by + bs / 2;

        boolean closeHot = over(closeX, by, bs, bs);
        int closeColor = closeHot ? CLOSE_HOT : BUTTON;
        float ccx = closeX + bs / 2;
        Draw.polyline(1.1f, closeColor, ccx - 3, cy - 3, ccx + 3, cy + 3);
        Draw.polyline(1.1f, closeColor, ccx - 3, cy + 3, ccx + 3, cy - 3);
        hit(closeX, by, bs, bs, (x, y, button) -> {
            if (button == 0) close();
        });

        boolean maxHot = over(maxX, by, bs, bs);
        int maxColor = maxHot ? accent : BUTTON;
        float mcx = maxX + bs / 2;
        if (maximized) Draw.rect(mcx - 3.5f, cy - 3.5f, 7, 7, 0.5f, maxColor);
        else Draw.outline(mcx - 3.5f, cy - 3.5f, 7, 7, 0.5f, 1, maxColor, maxColor);
        hit(maxX, by, bs, bs, (x, y, button) -> {
            if (button != 0) return;
            if (maximized) {
                wx = normalX;
                wy = normalY;
                ww = normalW;
                wh = normalH;
                maximized = false;
            } else {
                normalX = wx;
                normalY = wy;
                normalW = ww;
                normalH = wh;
                maximized = true;
            }
            UiSound.CLICK.play();
        });

        boolean layoutHot = over(layoutX, by, bs, bs);
        int layoutColor = layoutHot ? accent : BUTTON;
        float lcx = layoutX + bs / 2;
        Draw.polyline(1, layoutColor, lcx - 4, cy - 2, lcx + 4, cy - 2);
        Draw.polyline(1, layoutColor, lcx + 2, cy - 4, lcx + 4, cy - 2, lcx + 2, cy);
        Draw.polyline(1, layoutColor, lcx + 4, cy + 2, lcx - 4, cy + 2);
        Draw.polyline(1, layoutColor, lcx - 2, cy, lcx - 4, cy + 2, lcx - 2, cy + 4);
        hit(layoutX, by, bs, bs, (x, y, button) -> {
            GuiModule gui = guiModule();
            if (button != 0 || gui == null) return;
            gui.style.setValue(gui.nextStyle());
            UiSound.CLICK.play();
        });

        boolean guideHot = over(guideX, by, bs, bs);
        int guideColor = guideHot ? accent : BUTTON;
        float gcx = guideX + bs / 2;
        Draw.outline(gcx - 4, cy - 4, 8, 8, 4, 0.9f, guideColor, guideColor);
        SmoothFont q = SmoothFont.bold(5.5f);
        q.drawCentered("?", gcx, q.centerY(cy - 4, 8) - 0.3f, guideColor);
        hit(guideX, by, bs, bs, (x, y, button) -> {
            if (button != 0) return;
            UiSound.CLICK.play();
            mc.displayGuiScreen(new myau.guide.GuideScreen(this));
        });

        // Dividers: list | settings, tabs above the settings.
        Draw.rect(wx + SPLIT_X, wy + TITLE_H, 1, wh - TITLE_H, 0, CHROME);
        Draw.rect(wx + SPLIT_X + 1, wy + TABS_BOTTOM, ww - SPLIT_X - 1, 1, 0, CHROME);
    }

    private void drawGrip() {
        if (maximized) return;
        float gx = wx + ww - 12, gy = wy + wh - 12;
        boolean hovered = over(gx, gy, 12, 12);
        int accent = GlassTheme.accent();
        int color = Draw.mix(Draw.fade(accent, 0.8f), accent, gripHot.update(hovered || drag != null && hovered, dt));
        Draw.rect(wx + ww - 10, wy + wh - 10, 10, 2, 0, color);
        Draw.rect(wx + ww - 10, wy + wh - 8, 2, 8, 0, color);
        hit(gx, gy, 12, 12, (cx, cy, button) -> {
            if (button != 0) return;
            float offsetX = wx + ww - cx, offsetY = wy + wh - cy;
            drag = (dx, dy) -> {
                ww = Math.max(MIN_W, dx + offsetX - wx);
                wh = Math.max(MIN_H, dy + offsetY - wy);
            };
        });
    }

    // ================================================================ tabs

    private void drawTabs(boolean searching) {
        int accent = GlassTheme.accent();
        float band = wy + TITLE_H, bandH = TABS_BOTTOM - TITLE_H;
        Draw.pushClip(wx + SPLIT_X + 1, band, ww - SPLIT_X - 1, bandH);
        float tx = wx + 105, targetX = 0, targetW = 0;
        int count = tabs.size() + 1;
        for (int i = 0; i < count; i++) {
            String name = i < tabs.size() ? tabs.get(i).name : CONFIGS;
            float tw = text.width(name) + 10;
            boolean current = i == selectedTab && !searching;
            boolean hovered = over(tx, band, tw, bandH);
            int color = current ? accent : hovered ? 0xffdddddd : TAB_IDLE;
            text.draw(name, tx + 5, text.centerY(band, bandH - 2), color);
            if (i == selectedTab) {
                targetX = tx + 3 - wx;
                targetW = tw - 6;
            }
            final int index = i;
            hit(tx, band, tw, bandH, (cx, cy, button) -> {
                if (button == 0) selectTab(index);
            });
            tx += tw + 4;
        }
        if (!underlinePlaced) {
            underlineX.snap(targetX);
            underlineW.snap(targetW);
            underlinePlaced = true;
        }
        float ux = underlineX.update(targetX, dt), uw = underlineW.update(targetW, dt);
        if (!searching) Draw.rect(wx + ux, wy + TABS_BOTTOM - 2, Math.max(0, uw), 2, 0, accent);
        Draw.popClip();
    }

    // ================================================================ module list

    private void drawModuleList(boolean searching) {
        int accent = GlassTheme.accent();
        Tab tab = tabs.get(Math.min(listTab, tabs.size() - 1));
        List<Module> shown = searching ? results : tab.modules;
        Scroller scroller = searching ? resultScroll : tab.scroller;
        float lx = wx + 4, ly = wy + TITLE_H, lw = LIST_W, lh = wh - TITLE_H - 2;
        float content = shown.size() * ROW_H + 8;
        scroller.limit(content - lh);
        if (revealing != null && shown.contains(revealing)) {
            scroller.jump(shown.indexOf(revealing) * ROW_H - lh / 2);
            revealing = null;
        }
        scroller.update();

        Draw.pushClip(lx, ly, lw, lh);
        scrollHit(lx, ly, lw, lh, notches -> scroller.nudge(-notches * ROW_H * 3));
        if (shown.isEmpty()) text.draw(searching ? "No match" : "Empty", lx + 4, ly + 6, HINT);
        float top = ly + 4 - scroller.value;
        for (int i = 0; i < shown.size(); i++) {
            float ry = top + i * ROW_H;
            if (ry + ROW_H < ly || ry > ly + lh) continue;
            Module module = shown.get(i);
            boolean hovered = over(lx + 2, ry, lw - 4, ROW_H);
            boolean enabled = module.isEnabled(), current = module == selected;
            int color;
            if (binding == module) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 160.0);
                color = Draw.mix(IDLE, accent, pulse);
            } else if (current) {
                color = enabled ? 0xffffffff : 0xffd0d0d0;
            } else if (hovered) {
                color = enabled ? Draw.mix(accent, 0xffffffff, 0.35f) : 0xffbbbbbb;
            } else {
                color = enabled ? accent : IDLE;
            }
            float ty = text.centerY(ry, 12);
            float nameX = lx + (current ? 12 : 4);
            if (current) text.draw(">", lx + 2, ty, accent);
            text.draw(text.trim(module.getName(), lx + lw - 3 - nameX), nameX, ty, color);
            hit(lx, ry, lw, ROW_H, (cx, cy, button) -> {
                if (button == 0) {
                    module.toggle();
                } else if (button == 1) {
                    select(module);
                    UiSound.SELECT.play();
                } else if (button == 2) {
                    select(module);
                    binding = module;
                    UiSound.EXPAND.play();
                }
            });
        }
        Draw.popClip();
        if (scroller.max > 0.5f) {
            float track = lh - 8, thumb = Math.max(14, track * lh / content);
            Draw.rect(wx + SPLIT_X - 3, ly + 4 + (track - thumb) * (scroller.value / scroller.max), 1.5f, thumb, 0.75f, 0x40ffffff);
        }
    }

    // ================================================================ settings

    private float settingsHeight(List<Property<?>> properties, float w) {
        float total = 16 + 14 + 14;
        if (properties != null) {
            for (Property<?> p : properties) if (p.isVisible()) total += rowHeight(p, w);
        }
        return total + 8;
    }

    private float rowHeight(Property<?> p, float w) {
        if (p instanceof BooleanProperty) return 14;
        if (p instanceof ModeProperty) return modeLines((ModeProperty) p, w) * 12 + 2;
        return 16;
    }

    private int modeLines(ModeProperty mode, float w) {
        String[] options = options(mode);
        float start = 6 + text.width(mode.getName() + ": "), cx = start;
        int lines = 1;
        for (int i = 0; i < options.length; i++) {
            float itemW = text.width(options[i] + (i < options.length - 1 ? "," : ""));
            if (i > 0 && cx + itemW > w - 8) {
                cx = start;
                lines++;
            }
            cx += itemW + 4;
        }
        return lines;
    }

    private String[] options(ModeProperty mode) {
        String[] options = modeOptions.get(mode);
        if (options == null) {
            options = mode.getValuePrompt().split(", ");
            modeOptions.put(mode, options);
        }
        return options;
    }

    private Slider slider(Property<?> p) {
        Slider slider = sliders.get(p);
        if (slider == null) {
            if (p instanceof FloatProperty) slider = new FloatSlider((FloatProperty) p);
            else if (p instanceof IntProperty) slider = new IntSlider((IntProperty) p);
            else slider = new PercentageSlider((PercentProperty) p);
            sliders.put(p, slider);
        }
        return slider;
    }

    private void drawSettings() {
        float sx = wx + SETTINGS_X, sy = wy + SETTINGS_TOP, sw2 = ww - SETTINGS_X - 4, sh2 = wh - SETTINGS_TOP - 4;
        if (selected == null) {
            String hint = "Right click a module to see its settings";
            text.draw(text.trim(hint, sw2 - 8), sx + 6, sy + 6, HINT);
            return;
        }
        List<Property<?>> properties = Myau.propertyManager == null ? null : Myau.propertyManager.properties.get(selected.getClass());
        float content = settingsHeight(properties, sw2 - 8);
        settingsScroll.limit(content - sh2);
        settingsScroll.update();
        boolean bar = settingsScroll.max > 0.5f;
        float w = bar ? sw2 - 8 : sw2;
        int accent = GlassTheme.accent();

        Draw.pushClip(sx, sy, sw2, sh2);
        scrollHit(sx, sy, sw2, sh2, notches -> settingsScroll.nudge(-notches * 32));
        float y = sy + 4 - settingsScroll.value;

        if (visible(y, 16, sy, sh2)) bold.draw(selected.getName(), sx + 4, bold.centerY(y, 16) - 1, accent);
        y += 16;
        if (visible(y, 14, sy, sh2)) drawBind(sx, y);
        y += 14;
        if (visible(y, 14, sy, sh2)) drawHidden(sx, y);
        y += 14;
        if (properties != null) {
            for (Property<?> p : properties) {
                if (!p.isVisible()) continue;
                float h = rowHeight(p, w);
                if (visible(y, h, sy, sh2)) {
                    if (p instanceof BooleanProperty) drawBoolean((BooleanProperty) p, sx, y, w);
                    else if (p instanceof FloatProperty || p instanceof IntProperty || p instanceof PercentProperty) drawSlider(p, sx, y, w, accent);
                    else if (p instanceof ModeProperty) drawMode((ModeProperty) p, sx, y, w, accent);
                    else if (p instanceof ColorProperty) drawColor((ColorProperty) p, sx, y, w);
                    else if (p instanceof TextProperty) drawText((TextProperty) p, sx, y, w);
                    else drawInfo(p, sx, y);
                }
                y += h;
            }
        }
        Draw.popClip();

        if (bar) {
            float trackX = sx + sw2 - 6, trackY = sy + 2, trackH = sh2 - 4;
            float handleH = Math.max(24, Math.min(trackH, trackH * sh2 / content));
            float handleY = trackY + (trackH - handleH) * (settingsScroll.value / settingsScroll.max);
            Draw.rect(trackX, trackY, 4, trackH, 0, 0x5a000000);
            Draw.rect(trackX, handleY, 4, handleH, 0, accent);
            hit(trackX - 1, trackY, 6, trackH, (cx, cy, button) -> {
                if (button != 0) return;
                float grab = cy >= handleY && cy <= handleY + handleH ? cy - handleY : handleH / 2;
                Drag move = (dx, dy) -> {
                    float pct = trackH <= handleH ? 0 : Anim.clamp01((dy - grab - trackY) / (trackH - handleH));
                    settingsScroll.jump(pct * settingsScroll.max);
                };
                move.drag(cx, cy);
                drag = move;
            });
        }
    }

    private static boolean visible(float y, float h, float top, float height) {
        return y + h >= top - CULL && y <= top + height + CULL;
    }

    /** "name: " in the label colour; returns where the value starts. */
    private float label(String name, float x, float y, float h) {
        return text.draw(name + ": ", x + 6, text.centerY(y, h), LABEL);
    }

    private void drawBind(float x, float y) {
        Module module = selected;
        float vx = label("Bind", x, y, 12);
        boolean listening = binding == module;
        int key = module.getKey();
        String value = listening ? "Esc to clear" : key == 0 ? "None" : KeyBindUtil.getKeyName(key);
        float vw = text.width(value);
        boolean hovered = over(x + 4, y, vx + vw + 4 - x - 4, 12);
        int accent = GlassTheme.accent();
        int color = listening ? Draw.mix(VALUE, accent, 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 160.0))
                : hovered ? accent : VALUE;
        text.draw(value, vx, text.centerY(y, 12), color);
        hit(x + 4, y, vx + vw + 4 - x - 4, 12, (cx, cy, button) -> {
            if (button == 0) binding = module;
            else if (button == 1) clearKeybind(module);
            else return;
            UiSound.CLICK.play();
        });
    }

    private void drawHidden(float x, float y) {
        Module module = selected;
        float vx = label("Hidden", x, y, 12);
        boolean hidden = module.isHidden();
        String value = String.valueOf(hidden);
        text.draw(value, vx, text.centerY(y, 12), hidden ? TRUE : FALSE);
        hit(x + 4, y, vx + text.width(value) + 4 - x - 4, 12, (cx, cy, button) -> {
            if (button != 0) return;
            module.setHidden(!module.isHidden());
            UiSound.toggle(module.isHidden());
        });
    }

    private void drawBoolean(BooleanProperty p, float x, float y, float w) {
        float vx = label(p.getName(), x, y, 12);
        boolean on = p.getValue();
        float end = text.draw(String.valueOf(on), vx, text.centerY(y, 12), on ? TRUE : FALSE);
        if (over(x + 4, y, w - 8, 12)) Draw.rect(x + 4, y, end - x - 2, 12, 2, 0x10ffffff);
        hit(x + 4, y, w - 8, 12, (cx, cy, button) -> {
            if (button != 0) return;
            p.setValue(!p.getValue());
            (p.getValue() ? UiSound.TOGGLE_ON : UiSound.TOGGLE_OFF).play();
        });
    }

    private void drawSlider(Property<?> p, float x, float y, float w, int accent) {
        Slider slider = slider(p);
        float bx = label(p.getName(), x, y + 1, 13), by = y + 1, bh = 13;
        float barW = Math.min(BAR_W, Math.max(30, x + w - 8 - bx));
        double span = slider.getMax() - slider.getMin();
        float ratio = span <= 0 ? 0 : Anim.clamp01((float) ((slider.getInput() - slider.getMin()) / span));
        Draw.rect(bx, by, barW, bh, 0, TRACK);
        if (ratio > 0) Draw.rect(bx, by, barW * ratio, bh, 0, accent);
        if (editingKey(p)) {
            Draw.rect(bx, by, barW, bh, 0, 0xff151515);
            Draw.outline(bx, by, barW, bh, 0, 0.8f, accent, accent);
            drawEdit(editBuffer(), text.smooth, bx + 3, by, barW - 6, bh, 0xffffffff, true, false);
        } else {
            String value = slider.getValueString();
            text.draw(value, bx + (barW - text.width(value)) / 2, text.centerY(by, bh), 0xffffffff);
        }
        float barX = bx;
        hit(bx, by, barW, bh, (cx, cy, button) -> {
            if (button == 0) {
                setSlider(slider, cx, barX, barW);
                drag = (dx, dy) -> setSlider(slider, dx, barX, barW);
            } else if (button == 1) {
                beginEdit(p, slider.getValueString(), slider::setValueString);
            }
        });
    }

    private void setSlider(Slider slider, float cx, float barX, float barW) {
        double fraction = Anim.clamp01((cx - barX) / barW);
        double value = slider.getMin() + fraction * (slider.getMax() - slider.getMin());
        double step = slider.getIncrement();
        if (step > 0) value = Math.round(value / step) * step;
        String before = slider.getValueString();
        slider.setValue(Math.max(slider.getMin(), Math.min(slider.getMax(), value)));
        if (!before.equals(slider.getValueString())) UiSound.sliderTick((float) fraction);
    }

    private void drawMode(ModeProperty mode, float x, float y, float w, int accent) {
        String[] options = options(mode);
        float start = x + 6 + text.width(mode.getName() + ": ");
        text.draw(mode.getName() + ":", x + 6, text.centerY(y, 12), LABEL);
        float cx = start, cy = y;
        int current = mode.getValue();
        for (int i = 0; i < options.length; i++) {
            String item = options[i] + (i < options.length - 1 ? "," : "");
            float itemW = text.width(item);
            if (i > 0 && cx + itemW > x + w - 8) {
                cx = start;
                cy += 12;
            }
            boolean hovered = over(cx, cy, itemW, 12);
            int color = i == current ? accent : hovered ? 0xffbbbbbb : MODE_IDLE;
            text.draw(item, cx, text.centerY(cy, 12), color);
            final int index = i;
            hit(cx, cy, itemW + 4, 12, (hx, hy, button) -> {
                if (button == 0 && mode.getValue() != index) {
                    mode.setValue(index);
                    UiSound.CLICK.play();
                } else if (button == 1) {
                    mode.nextMode();
                    UiSound.CLICK.play();
                }
            });
            cx += itemW + 4;
        }
    }

    private void drawColor(ColorProperty p, float x, float y, float w) {
        int rgb = 0xff000000 | (p.getValue() & 0xffffff);
        text.draw(p.getName(), x + 6, text.centerY(y, 14), VALUE);
        float swX = x + w - 22, swY = y + 2;
        String hex = String.format("#%06X", rgb & 0xffffff);
        text.draw(hex, swX - 4 - text.width(hex), text.centerY(y, 14), HINT);
        Draw.rect(swX, swY, 14, 11, 0, rgb);
        boolean open = picking == p;
        if (open || over(swX, swY, 14, 11)) Draw.outline(swX - 1, swY - 1, 16, 13, 0, 0.8f, 0xffffffff, 0xffffffff);
        float popupY = y + 18;
        hit(swX - 2, y, 18, 14, (cx, cy, button) -> {
            if (button != 0) return;
            if (picking == p) {
                picking = null;
                UiSound.COLLAPSE.play();
            } else {
                picking = p;
                pickKnown = -1;
                pickX = Math.max(wx + 4, Math.min(x + 10, wx + ww - PICKER - 6));
                pickY = Math.max(wy + TITLE_H + 4, Math.min(popupY, wy + wh - PICKER - 20));
                UiSound.EXPAND.play();
            }
        });
        hit(x + 4, y, swX - 6 - x - 4, 14, (cx, cy, button) -> {
            if (button == 0) beginEdit(p, String.format("%06X", p.getValue() & 0xffffff), value -> p.parseString(value.replace("#", "")));
        });
        if (editingKey(p)) {
            float fw = 60, fx = swX - 6 - fw;
            Draw.rect(fx, y + 1, fw, 13, 0, 0xff151515);
            Draw.outline(fx, y + 1, fw, 13, 0, 0.8f, GlassTheme.accent(), GlassTheme.accent());
            drawEdit(editBuffer(), text.smooth, fx + 3, y + 1, fw - 6, 13, 0xffffffff, true, false);
        }
    }

    private void drawText(TextProperty p, float x, float y, float w) {
        float labelEnd = x + 6 + text.width(p.getName() + ": ");
        text.draw(p.getName() + ":", x + 6, text.centerY(y + 1, 13), LABEL);
        float fieldW = Math.max(20, Math.min(120, w - (labelEnd - x) - 12));
        float fx = labelEnd + 1, fy = y + 1, fh = 13;
        boolean editing = editingKey(p);
        Draw.rect(fx, fy, fieldW + 4, fh, 0, editing ? 0xff151515 : TRACK);
        if (editing) {
            Draw.outline(fx, fy, fieldW + 4, fh, 0, 0.8f, GlassTheme.accent(), GlassTheme.accent());
            drawEdit(editBuffer(), text.smooth, fx + 3, fy, fieldW - 2, fh, 0xffdddddd, true, false);
        } else {
            String value = p.getValue();
            boolean empty = value == null || value.isEmpty();
            text.draw(text.trim(empty ? "..." : value, fieldW - 2), fx + 3, text.centerY(fy, fh), empty ? HINT : 0xffdddddd);
        }
        hit(fx, fy, fieldW + 4, fh, (cx, cy, button) -> {
            if (button == 0) beginEdit(p, p.getValue(), value -> p.setValue(value));
        });
    }

    private void drawInfo(Property<?> p, float x, float y) {
        float vx = label(p.getName(), x, y, 12);
        text.draw(String.valueOf(p.getValue()), vx, text.centerY(y, 12), VALUE);
    }

    // ================================================================ colour picker

    private void drawPicker() {
        ColorProperty p = picking;
        List<Property<?>> properties = selected == null || Myau.propertyManager == null ? null : Myau.propertyManager.properties.get(selected.getClass());
        if (properties == null || !properties.contains(p) || !p.isVisible()) {
            picking = null;
            return;
        }
        drawingLayer = POPUP;
        int rgb = p.getValue() & 0xffffff;
        if (rgb != pickKnown) {
            pickKnown = rgb;
            Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, pickHsb);
        }
        float px = pickX, py = pickY, size = PICKER;
        // A click outside the picker closes it.
        hit(0, 0, sw, sh, (cx, cy, button) -> {
            picking = null;
            UiSound.COLLAPSE.play();
        });
        Draw.shadow(px - 2, py - 2, size + 4, size + 18, 2, 8, 0x80000000);
        Draw.rect(px - 2, py - 2, size + 4, size + 18, 0, CHROME);
        hit(px - 2, py - 2, size + 4, size + 18, (cx, cy, button) -> { });

        Draw.gradientH(px, py, size, size, 0, 0xffffffff, 0xff000000 | Color.HSBtoRGB(pickHsb[0], 1, 1));
        Draw.gradientV(px, py, size, size, 0, 0x00000000, 0xff000000);
        float kx = px + size * pickHsb[1], ky = py + size * (1 - pickHsb[2]);
        Draw.outline(kx - 3, ky - 3, 6, 6, 3, 1.2f, 0xffffffff, 0xffffffff);
        hit(px, py, size, size, (cx, cy, button) -> {
            if (button != 0) return;
            Drag pick = (dx, dy) -> {
                pickHsb[1] = Anim.clamp01((dx - px) / size);
                pickHsb[2] = 1 - Anim.clamp01((dy - py) / size);
                applyPick(p);
            };
            pick.drag(cx, cy);
            drag = pick;
        });

        float hy = py + size + 3;
        Draw.hueBar(px, hy, size, 10);
        float hx = px + size * pickHsb[0];
        Draw.rect(hx - 1, hy - 1, 2, 12, 0, 0xffffffff);
        hit(px, hy, size, 10, (cx, cy, button) -> {
            if (button != 0) return;
            Drag pick = (dx, dy) -> {
                pickHsb[0] = Math.min(0.9999f, Anim.clamp01((dx - px) / size));
                applyPick(p);
            };
            pick.drag(cx, cy);
            drag = pick;
        });
    }

    private void applyPick(ColorProperty p) {
        int rgb = Color.HSBtoRGB(pickHsb[0], pickHsb[1], pickHsb[2]) & 0xffffff;
        if (p.setValue(rgb)) {
            if (pickKnown != rgb) UiSound.sliderTick();
            pickKnown = rgb;
        }
    }

    // ================================================================ configs

    private void drawConfigs() {
        float sx = wx + SETTINGS_X, sy = wy + SETTINGS_TOP, sw2 = ww - SETTINGS_X - 4, sh2 = wh - SETTINGS_TOP - 4;
        float pw = sw2 - 10, content = configList.height(pw) + 10;
        configScroll.limit(content - sh2);
        configScroll.update();
        Draw.pushClip(sx, sy, sw2, sh2);
        scrollHit(sx, sy, sw2, sh2, notches -> configScroll.nudge(-notches * 40));
        configList.draw(sx + 4, sy + 4 - configScroll.value, pw);
        Draw.popClip();
        if (configScroll.max > 0.5f) {
            float trackH = sh2 - 4, handleH = Math.max(24, trackH * sh2 / content);
            Draw.rect(sx + sw2 - 4, sy + 2, 4, trackH, 0, 0x5a000000);
            Draw.rect(sx + sw2 - 4, sy + 2 + (trackH - handleH) * (configScroll.value / configScroll.max), 4, handleH, 0, GlassTheme.accent());
        }
    }

    // ================================================================ layout file

    private void loadLayout() {
        if (!layoutFile.exists()) return;
        try {
            JsonElement parsed = new JsonParser().parse(FileUtil.readString(layoutFile));
            if (parsed == null || !parsed.isJsonObject()) return;
            JsonObject json = parsed.getAsJsonObject();
            wx = json.get("x").getAsFloat();
            wy = json.get("y").getAsFloat();
            ww = json.get("w").getAsFloat();
            wh = json.get("h").getAsFloat();
            if (json.has("maximized") && json.get("maximized").getAsBoolean()) {
                normalX = json.get("normalX").getAsFloat();
                normalY = json.get("normalY").getAsFloat();
                normalW = json.get("normalW").getAsFloat();
                normalH = json.get("normalH").getAsFloat();
                maximized = true;
            }
            String tab = json.has("tab") ? json.get("tab").getAsString() : "";
            for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).name.equals(tab)) selectedTab = listTab = i;
            if (json.has("list")) {
                String list = json.get("list").getAsString();
                for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).name.equals(list)) listTab = i;
            }
            if (CONFIGS.equals(tab)) selectedTab = tabs.size();
            String module = json.has("module") ? json.get("module").getAsString() : "";
            for (Module m : tabs.get(listTab).modules) if (m.getName().equals(module)) selected = m;
            placed = true;
        } catch (Exception e) {
            Myau.LOGGER.warn("Ignoring unreadable Augustus click GUI layout", e);
            placed = false;
            maximized = false;
        }
    }

    private void saveLayout() {
        JsonObject json = new JsonObject();
        json.addProperty("x", wx);
        json.addProperty("y", wy);
        json.addProperty("w", ww);
        json.addProperty("h", wh);
        json.addProperty("maximized", maximized);
        if (maximized) {
            json.addProperty("normalX", normalX);
            json.addProperty("normalY", normalY);
            json.addProperty("normalW", normalW);
            json.addProperty("normalH", normalH);
        }
        json.addProperty("tab", onConfigs() ? CONFIGS : tabs.get(selectedTab).name);
        json.addProperty("list", tabs.get(listTab).name);
        json.addProperty("module", selected == null ? "" : selected.getName());
        try {
            FileUtil.writeAtomic(layoutFile, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException e) {
            Myau.LOGGER.error("Failed to save Augustus click GUI layout", e);
        }
    }

    // ================================================================ parts

    private final class Tab {
        final String name;
        final List<Module> modules = new ArrayList<>();
        final Scroller scroller = new Scroller();

        Tab(CategoryComponent component) {
            this.name = component.getName();
            for (Component c : component.getModules()) {
                if (c instanceof ModuleComponent) modules.add(((ModuleComponent) c).mod);
            }
        }
    }

    /**
     * Text in the font the {@code font} setting picks. The smooth font draws through the batch;
     * Minecraft's font flushes it and draws at a whole number of screen pixels per font pixel, so
     * it stays sharp at any GUI scale. Clipping is a scissor and transforms are on the GL matrix,
     * so both apply to it; the batch's opacity is folded into the colour.
     */
    private final class Txt {
        final boolean bold;
        SmoothFont smooth;

        Txt(boolean bold) {
            this.bold = bold;
        }

        private String vanilla(String s) {
            return bold ? "§l" + s : s;
        }

        float width(String s) {
            return pixel ? mc.fontRendererObj.getStringWidth(vanilla(s)) * pixelScale : smooth.width(s);
        }

        /** The y to draw at so the text sits in the middle of a row from {@code top}, {@code h} high. */
        float centerY(float top, float h) {
            return pixel ? top + (h - 7 * pixelScale) / 2f : smooth.centerY(top, h) - 0.5f;
        }

        String trim(String s, float maxWidth) {
            if (!pixel) return smooth.trim(s, maxWidth);
            if (width(s) <= maxWidth) return s;
            String cut = mc.fontRendererObj.trimStringToWidth(s, Math.max(0, (int) ((maxWidth - width("..")) / pixelScale)));
            return cut + "..";
        }

        /** @return where the text ends */
        float draw(String s, float x, float y, int color) {
            if (!pixel) return smooth.draw(s, x, y, color);
            float a = Draw.alpha() * ((color >>> 24) / 255f);
            // Minecraft's font draws an alpha below 4 as fully opaque: leave nearly invisible text out.
            if (a >= 0.03f) {
                int c = (Math.round(a * 255) << 24) | (color & 0xffffff);
                Draw.flush();
                GlStateManager.enableBlend();
                GlStateManager.enableTexture2D();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.pushMatrix();
                GlStateManager.translate(Math.round(x * ui) / ui, Math.round(y * ui) / ui, 0);
                GlStateManager.scale(pixelScale, pixelScale, 1);
                mc.fontRendererObj.drawString(vanilla(s), 0, 0, c, false);
                GlStateManager.popMatrix();
            }
            return x + width(s);
        }
    }
}
