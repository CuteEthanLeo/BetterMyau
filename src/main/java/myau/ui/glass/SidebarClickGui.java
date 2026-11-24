package myau.ui.glass;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.config.ConfigManager;
import myau.module.Module;
import myau.module.modules.GuiModule;
import myau.ui.Component;
import myau.ui.GlassTheme;
import myau.ui.components.CategoryComponent;
import myau.ui.components.ModuleComponent;
import myau.util.FileUtil;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The "sidebar" click GUI layout: one window with the categories down the left and the
 * selected category's modules as two columns of cards. A card opens in place to show its
 * settings; typing searches every category at once. The window can be moved and resized.
 */
public class SidebarClickGui extends ClickGuiBase {
    private static final float SIDE_W = 138, HEAD_H = 46, CARD_H = 30, GAP = 7, PAD = 12, RADIUS = 14;
    private static final float ITEM_H = 26, ITEM_GAP = 2, ITEMS_TOP = 54;
    private static final float MIN_W = 520, MIN_H = 300;
    /** How far a card's shadow and glow reach; cards further out of the page are not drawn. */
    private static final float CULL = 12;
    private static final Object WINDOW = new Object();

    private final List<Category> categories = new ArrayList<>();
    private final List<Card> results = new ArrayList<>();
    private final Scroller resultScroll = new Scroller();
    private final File layoutFile = new File("./config/Myau/", "clickgui-sidebar.json");
    private final Anim.Spring pill = Anim.spring(0, 0.3f, 0.8f);
    private final Anim.Smooth page = new Anim.Smooth(1, 13), pillShow = new Anim.Smooth(1, 14),
            searchGlow = new Anim.Smooth(0, 12), lift = new Anim.Smooth(1, 14), gripHot = new Anim.Smooth(0, 16),
            switchHot = new Anim.Smooth(0, 16), augustusHot = new Anim.Smooth(0, 16);
    private final float[] column = new float[2];

    private float wx, wy, ww = 640, wh = 400;
    private boolean placed, moving, resizing;
    private int selected;
    private String resultQuery, resultText = "";
    private Card revealing;
    private SmoothFont fBig, fBrand, fCard;
    private String versionText;
    /** The tab after the categories: saved configs. */
    private static final String CONFIGS = "Configs";
    private final ConfigList configList = new ConfigList();
    private final Scroller configScroll = new Scroller();
    private final Interaction configTouch = new Interaction(), guideTouch = new Interaction();
    private String configSummary = "", configSummaryKey;

    public SidebarClickGui() {
        for (CategoryComponent component : categories()) categories.add(new Category(component, categories.size()));
        if (categories.isEmpty()) throw new IllegalStateException("no module categories");
        loadLayout();
    }

    // ================================================================ lifecycle

    @Override
    protected void onOpen() {
        if (!placed) {
            ww = Math.min(640, sw - 40);
            wh = Math.min(400, sh - 60);
            wx = (sw - ww) / 2f;
            wy = (sh - wh) / 2f;
            placed = true;
        }
        moving = resizing = false;
        pill.snap(selected);
        page.snap(1);
        versionText = "v" + Myau.version;
        configList.refresh(true);
    }

    private boolean onConfigs() {
        return selected == categories.size();
    }

    @Override
    protected void onClosed() {
        saveLayout();
    }

    @Override
    protected float exitDuration() {
        return 0.2f;
    }

    @Override
    public void reveal(Module module) {
        for (Category category : categories) {
            for (Card card : category.cards) {
                if (card.module != module) continue;
                search.clear();
                selected = category.index;
                card.expanded = true;
                revealing = card;
                return;
            }
        }
    }

    private void select(int index) {
        boolean changed = index != selected || !search.isEmpty();
        selected = index;
        search.clear();
        searchFocus = false;
        if (changed) {
            page.snap(0);
            UiSound.SELECT.play();
        }
    }

    // ================================================================ frame

    @Override
    protected void drawContent(float backdrop) {
        fBig = SmoothFont.bold(13);
        fBrand = SmoothFont.bold(11);
        fCard = SmoothFont.regular(10);

        // Keep the window a usable size and on the screen, whatever the saved layout says.
        ww = Math.max(Math.min(MIN_W, sw - 16), Math.min(ww, sw - 16));
        wh = Math.max(Math.min(MIN_H, sh - 16), Math.min(wh, sh - 16));
        wx = Math.max(8, Math.min(sw - 8 - ww, wx));
        wy = Math.max(8, Math.min(sh - 8 - wh, wy));
        if (moving && drag == null) moving = false;
        if (resizing && drag == null) resizing = false;
        hoverLayer = drawingLayer = WINDOW;

        // Entrance: scale up with a slight overshoot; exit: quick sink and fade.
        float scale, fade, shift;
        if (closing) {
            float q = Anim.clamp01(closeTime / exitDuration());
            scale = 1 - 0.07f * Anim.inCubic(q);
            fade = 1 - q * q;
            shift = 10 * Anim.inCubic(q);
        } else {
            float p = Anim.clamp01(openTime / 0.42f);
            scale = Anim.lerp(0.9f, 1, Anim.outBack(p));
            fade = Anim.outCubic(Math.min(1, p * 1.8f));
            shift = (1 - Anim.outCubic(p)) * 14;
        }
        float lifted = lift.update(moving ? 1.8f : 1.2f, dt);

        Draw.pushTransform(wx + ww / 2, wy + wh / 2, scale, 0, shift);
        Draw.pushAlpha(fade);
        GlassTheme.panel(wx, wy, ww, wh, RADIUS, lifted, pal);
        hit(wx, wy, ww, wh, (cx, cy, button) -> { });
        hit(wx, wy, ww, HEAD_H, (cx, cy, button) -> {
            if (button != 0) return;
            float offsetX = cx - wx, offsetY = cy - wy;
            moving = true;
            drag = (dx, dy) -> {
                wx = dx - offsetX;
                wy = dy - offsetY;
            };
        });

        boolean searching = !query.isEmpty();
        if (searching && !query.equals(resultQuery)) collectResults();
        if (!searching) resultQuery = null;

        drawSidebar(searching);
        drawHeader(searching);
        drawPage(searching);
        drawGrip();
        Draw.popAlpha();
        Draw.popTransform();

        Draw.pushAlpha(backdrop);
        fTiny.drawRight(onConfigs() && !searching
                        ? "Left click: load   Right click: delete   Type to search"
                        : "Left click: toggle   Right click: settings   Middle click: bind   Type to search",
                sw - 10, sh - 10 - fTiny.height(), Draw.fade(pal.faint, 0.9f));
        Draw.popAlpha();
    }

    private void collectResults() {
        resultQuery = query;
        results.clear();
        for (Category category : categories) {
            category.matches = 0;
            for (Card card : category.cards) {
                if (!card.matches(query)) continue;
                results.add(card);
                category.matches++;
            }
            category.matchText = String.valueOf(category.matches);
        }
        resultText = results.size() == 1 ? "1 result" : results.size() + " results";
        resultScroll.jump(0);
    }

    // ================================================================ sidebar

    private void drawSidebar(boolean searching) {
        float x = wx, y = wy;
        Draw.rect(x, y, SIDE_W, wh, RADIUS, 0, 0, RADIUS, Draw.fade(pal.sunken, 0.6f));
        Draw.rect(x + SIDE_W - 0.5f, y + 12, 0.5f, wh - 24, 0, pal.wash(0.10f));

        // Brand
        int a = pal.accentFill(GlassTheme.accent(0)), b = pal.accentFill(GlassTheme.accent(6));
        glow(x + 14, y + 15, 16, 16, 5, 7, Draw.alpha(a, 0.35f));
        Draw.gradientH(x + 14, y + 15, 16, 16, 5, a, b);
        Draw.gradientV(x + 14, y + 15, 16, 16, 5, 0x38ffffff, 0x00ffffff);
        fBrand.draw("BetterMyau", x + 37, y + 14, pal.text);
        fTiny.draw(versionText, x + 37, y + 14 + fBrand.height() - 1, pal.faint);

        // The selection pill glides between categories and steps back while searching.
        float show = pillShow.update(!searching, dt);
        float py = y + ITEMS_TOP + pill.update(selected, dt) * (ITEM_H + ITEM_GAP);
        if (show > 0.01f) {
            int accent = pal.accentInk(GlassTheme.accent(selected * 2L));
            Draw.rect(x + 8, py, SIDE_W - 16, ITEM_H, 8, pal.wash(0.09f * show));
            Draw.outline(x + 8, py, SIDE_W - 16, ITEM_H, 8, 0.6f, pal.wash(0.16f * show), pal.wash(0.04f * show));
            glow(x + 8, py + 7, 2.5f, ITEM_H - 14, 1.25f, 5, Draw.alpha(accent, 0.55f * show));
            Draw.rect(x + 8, py + 7, 2.5f, ITEM_H - 14, 1.25f, Draw.alpha(accent, show));
        }

        for (Category category : categories) {
            float iy = y + ITEMS_TOP + category.index * (ITEM_H + ITEM_GAP), ix = x + 8, iw = SIDE_W - 16;
            boolean hovered = over(ix, iy, iw, ITEM_H);
            category.touch.update(hovered, hovered && Mouse.isButtonDown(0), dt);
            float hot = category.touch.hot;
            boolean current = !searching && category.index == selected;
            if (hot > 0.01f && !current) Draw.rect(ix, iy, iw, ITEM_H, 8, pal.wash(0.05f * hot));
            int color = current ? pal.text : Draw.mix(pal.muted, pal.text, hot);
            int tint = current ? pal.accentInk(GlassTheme.accent(category.index * 2L)) : color;
            icon(category.name, ix + 17, iy + ITEM_H / 2, tint);
            fRow.draw(category.name, ix + 31, fRow.centerY(iy, ITEM_H) - 0.5f, color);
            if (searching) {
                if (category.matches > 0) fSmall.drawRight(category.matchText, ix + iw - 9, fSmall.centerY(iy, ITEM_H) - 0.5f, pal.accentInk(GlassTheme.accent()));
            } else {
                fSmall.drawRight(category.enabledText(), ix + iw - 9, fSmall.centerY(iy, ITEM_H) - 0.5f, pal.faint);
            }
            hit(ix, iy, iw, ITEM_H, (cx, cy, button) -> {
                if (button != 0) return;
                category.touch.pulse();
                select(category.index);
            });
        }

        // Saved configs, as one more tab under the categories.
        {
            int index = categories.size();
            float iy = y + ITEMS_TOP + index * (ITEM_H + ITEM_GAP), ix = x + 8, iw = SIDE_W - 16;
            boolean hovered = over(ix, iy, iw, ITEM_H);
            configTouch.update(hovered, hovered && Mouse.isButtonDown(0), dt);
            float hot = configTouch.hot;
            boolean current = !searching && onConfigs();
            if (hot > 0.01f && !current) Draw.rect(ix, iy, iw, ITEM_H, 8, pal.wash(0.05f * hot));
            int color = current ? pal.text : Draw.mix(pal.muted, pal.text, hot);
            int tint = current ? pal.accentInk(GlassTheme.accent(index * 2L)) : color;
            icon(CONFIGS, ix + 17, iy + ITEM_H / 2, tint);
            fRow.draw(CONFIGS, ix + 31, fRow.centerY(iy, ITEM_H) - 0.5f, color);
            if (ConfigManager.isDirty() && !ConfigManager.isAutoSave()) {
                Draw.circle(ix + iw - 12, iy + ITEM_H / 2, 2.2f, GlassTheme.WARN);
            } else {
                fSmall.drawRight(String.valueOf(configList.count()), ix + iw - 9, fSmall.centerY(iy, ITEM_H) - 0.5f, pal.faint);
            }
            hit(ix, iy, iw, ITEM_H, (cx, cy, button) -> {
                if (button != 0) return;
                configTouch.pulse();
                configList.refresh(true);
                select(index);
            });
        }

        // The guide, as a quieter row above the theme picker.
        {
            float gy = y + wh - 72, gx = x + 8, gw = SIDE_W - 16, gh = 22;
            boolean hovered = over(gx, gy, gw, gh);
            guideTouch.update(hovered, hovered && Mouse.isButtonDown(0), dt);
            float hot = guideTouch.hot;
            if (hot > 0.01f) Draw.rect(gx, gy, gw, gh, 8, pal.wash(0.05f * hot));
            int color = Draw.mix(pal.muted, pal.text, hot);
            float cx = gx + 17, cy = gy + gh / 2;
            Draw.outline(cx - 5, cy - 5, 10, 10, 5, 0.9f, color, color);
            SmoothFont q = SmoothFont.bold(6.5f);
            q.drawCentered("?", cx, q.centerY(cy - 5, 10) - 0.3f, color);
            fSmall.draw("使用指南", gx + 31, fSmall.centerY(gy, gh) - 0.5f, color);
            Draw.chevron(gx + gw - 10 + hot * 1.5f, cy, 2.6f, 0, 1.1f, Draw.fade(color, 0.7f));
            hit(gx, gy, gw, gh, (cx2, cy2, button) -> {
                if (button != 0) return;
                guideTouch.pulse();
                UiSound.CLICK.play();
                mc.displayGuiScreen(new myau.guide.GuideScreen(this));
            });
        }

        // Theme picker and the way to the other layouts.
        GuiModule gui = guiModule();
        if (gui == null) return;
        float by = y + wh - 44;
        fTiny.draw("Theme", x + 14, by, pal.faint);
        // The other two layouts, right-aligned on the same line: "Panels · Augustus".
        float right = x + SIDE_W - 12;
        float augW = fTiny.width("Augustus"), dotW = fTiny.width(" · "), panW = fTiny.width("Panels");
        float augX = right - augW, panX = augX - dotW - panW;
        boolean augHover = over(augX - 2, by - 3, augW + 4, 12);
        boolean panHover = over(panX - 2, by - 3, panW + 4, 12);
        float ah = augustusHot.update(augHover, dt), ph = switchHot.update(panHover, dt);
        fTiny.draw("Augustus", augX, by, Draw.mix(pal.faint, pal.text, ah));
        fTiny.draw(" · ", augX - dotW, by, pal.faint);
        fTiny.draw("Panels", panX, by, Draw.mix(pal.faint, pal.text, ph));
        hit(augX - 2, by - 3, augW + 4, 12, (cx, cy, button) -> {
            if (button != 0) return;
            gui.style.setValue(GuiModule.STYLE_AUGUSTUS);
            UiSound.CLICK.play();
        });
        hit(panX - 2, by - 3, panW + 4, 12, (cx, cy, button) -> {
            if (button != 0) return;
            gui.style.setValue(GuiModule.STYLE_PANELS);
            UiSound.CLICK.play();
        });
        int count = GuiPalette.count();
        float step = Math.min(19, (SIDE_W - 28 - 11) / Math.max(1, count - 1));
        for (int i = 0; i < count; i++) {
            float cx = x + 14 + 5.5f + i * step, cy = by + 21;
            boolean hovered = over(cx - 8, cy - 8, 16, 16);
            boolean current = gui.theme.getValue() == i;
            Draw.circle(cx, cy, 5.5f, GuiPalette.byIndex(i).swatch);
            Draw.outline(cx - 5.5f, cy - 5.5f, 11, 11, 5.5f, 0.7f, pal.wash(hovered ? 0.7f : 0.38f), pal.wash(hovered ? 0.5f : 0.2f));
            if (current) {
                int accent = pal.accentInk(GlassTheme.accent());
                Draw.outline(cx - 8, cy - 8, 16, 16, 8, 1.1f, accent, accent);
            }
            final int index = i;
            hit(cx - 8, cy - 8, 16, 16, (hx, hy, button) -> {
                if (button != 0 || gui.theme.getValue() == index) return;
                gui.theme.setValue(index);
                UiSound.SELECT.play();
            });
        }
    }

    /** Small line icon for a category, centred on (cx, cy). */
    private static void icon(String name, float cx, float cy, int color) {
        switch (name) {
            case "Combat":
                Draw.polyline(1.2f, color, cx - 4, cy + 4, cx + 4, cy - 4);
                Draw.polyline(1.2f, color, cx - 3.4f, cy - 0.2f, cx + 0.2f, cy + 3.4f);
                break;
            case "Movement":
                Draw.polyline(1.2f, color, cx - 4, cy - 3, cx - 1, cy, cx - 4, cy + 3);
                Draw.polyline(1.2f, color, cx + 0.5f, cy - 3, cx + 3.5f, cy, cx + 0.5f, cy + 3);
                break;
            case "Render":
                Draw.outline(cx - 5, cy - 3, 10, 6, 3, 1, color, color);
                Draw.circle(cx, cy, 1.3f, color);
                break;
            case "Player":
                Draw.circle(cx, cy - 2.6f, 1.9f, color);
                Draw.rect(cx - 3.6f, cy + 0.5f, 7.2f, 3.8f, 3.6f, 3.6f, 1, 1, color);
                break;
            case "Misc":
                Draw.rect(cx - 3.7f, cy - 3.7f, 3, 3, 1, color);
                Draw.rect(cx + 0.7f, cy - 3.7f, 3, 3, 1, color);
                Draw.rect(cx - 3.7f, cy + 0.7f, 3, 3, 1, color);
                Draw.rect(cx + 0.7f, cy + 0.7f, 3, 3, 1, color);
                break;
            case CONFIGS:
                // Two stacked sheets.
                Draw.outline(cx - 2.6f, cy - 4.4f, 7, 7.6f, 1.6f, 1, color, color);
                Draw.rect(cx - 4.6f, cy - 2.4f, 7, 7.6f, 1.6f, color);
                break;
            default:
                Draw.circle(cx, cy, 2, color);
        }
    }

    // ================================================================ header

    private void drawHeader(boolean searching) {
        float x = wx + SIDE_W, w = ww - SIDE_W, y = wy;
        String title, subtitle;
        if (searching) {
            title = "Search";
            subtitle = resultText;
        } else if (onConfigs()) {
            title = CONFIGS;
            subtitle = configSummary();
        } else {
            Category category = categories.get(selected);
            title = category.name;
            subtitle = category.summaryText();
        }
        fBig.draw(title, x + PAD + 2, y + 10, pal.text);
        fSmall.draw(subtitle, x + PAD + 2, y + 10 + fBig.height() - 0.5f, pal.faint);

        float fw = Math.max(120, Math.min(210, w - 150)), fh = 24, fx = x + w - PAD - fw, fy = y + 11;
        boolean active = !search.isEmpty(), focused = (searchFocus || active) && !editing();
        float glow = searchGlow.update(active || focused, dt);
        GlassTheme.field(fx, fy, fw, fh, fh / 2, glow, pal);
        hit(fx, fy, fw, fh, (cx, cy, button) -> focusSearch());
        int icon = Draw.mix(pal.faint, pal.text, glow);
        Draw.outline(fx + 9, fy + 7.5f, 7, 7, 3.5f, 1, icon, icon);
        Draw.polyline(1, icon, fx + 15, fy + 13.5f, fx + 17.5f, fy + 16);
        float ty = fRow.centerY(fy, fh) - 0.5f;
        if (active) {
            drawEdit(search, fRow, fx + 23, fy, fw - 36, fh, pal.text, focused, false);
        } else {
            fRow.draw("Search modules", fx + 23, ty, pal.faint);
            if (focused) drawEdit(search, fRow, fx + 23, fy, fw - 36, fh, pal.text, true, false);
        }
        Draw.rect(x + PAD, y + HEAD_H - 0.5f, w - PAD * 2, 0.5f, 0, pal.wash(0.09f));
    }

    // ================================================================ page of cards

    private String configSummary() {
        boolean dirty = ConfigManager.isDirty() && !ConfigManager.isAutoSave();
        String key = ConfigManager.current() + '|' + ConfigManager.isAutoSave() + '|' + dirty;
        if (!key.equals(configSummaryKey)) {
            configSummaryKey = key;
            configSummary = "Current: " + ConfigManager.current()
                    + (ConfigManager.isAutoSave() ? "  ·  auto-save on" : dirty ? "  ·  unsaved changes" : "  ·  auto-save off");
        }
        return configSummary;
    }

    private void drawConfigPage() {
        float x = wx + SIDE_W, w = ww - SIDE_W;
        float px = x + PAD, pw = w - PAD * 2, py = wy + HEAD_H + 1, ph = wh - HEAD_H - 1 - 6;
        float content = configList.height(pw) + 12;
        configScroll.limit(content - ph);
        configScroll.update();

        float appear = Anim.clamp01(page.update(1, dt));
        float slide = (1 - appear) * 8;
        Draw.pushClip(x, py, w, ph);
        Draw.pushAlpha(appear);
        scrollHit(x, py, w, ph, notches -> configScroll.nudge(-notches * 46f));
        configList.draw(px, py + 6 - configScroll.value + slide, pw);
        Draw.popAlpha();
        Draw.popClip();

        if (configScroll.max > 0.5f) {
            float track = ph - 12, thumb = Math.max(18, track * ph / content);
            float ty = py + 6 + (track - thumb) * (configScroll.value / configScroll.max);
            Draw.rect(wx + ww - 5.5f, ty, 2, thumb, 1, pal.wash(0x50ffffff));
        }
    }

    private void drawPage(boolean searching) {
        if (!searching && onConfigs()) {
            drawConfigPage();
            return;
        }
        float x = wx + SIDE_W, w = ww - SIDE_W;
        float px = x + PAD, pw = w - PAD * 2, py = wy + HEAD_H + 1, ph = wh - HEAD_H - 1 - 6;
        List<Card> shown;
        Scroller scroller;
        if (searching) {
            shown = results;
            scroller = resultScroll;
        } else {
            Category category = categories.get(selected);
            shown = category.cards;
            scroller = category.scroller;
        }

        // Two independent columns: opening a card only pushes down the cards below it.
        float cardW = (pw - GAP) / 2f;
        column[0] = column[1] = 6;
        for (int i = 0; i < shown.size(); i++) {
            Card card = shown.get(i);
            int c = i & 1;
            card.column = c;
            card.top = column[c];
            column[c] += card.layout() + GAP;
        }
        float content = Math.max(column[0], column[1]) - GAP + 8;
        scroller.limit(content - ph);
        if (revealing != null && shown.contains(revealing)) {
            scroller.jump(revealing.top - 6);
            revealing = null;
        }
        scroller.update();

        // A new page slides up into place.
        float appear = Anim.clamp01(page.update(1, dt));
        float slide = (1 - appear) * 8;
        Draw.pushClip(x, py, w, ph);
        Draw.pushAlpha(appear);
        scrollHit(x, py, w, ph, notches -> scroller.nudge(-notches * 46f));
        if (shown.isEmpty()) {
            fRow.drawCentered("No modules match", x + w / 2, py + ph / 2 - fRow.height(), pal.muted);
            fSmall.drawCentered("“" + search + "”", x + w / 2, py + ph / 2 + 2, pal.faint);
        }
        for (int i = 0; i < shown.size(); i++) {
            Card card = shown.get(i);
            float cx = px + card.column * (cardW + GAP), cy = py + card.top - scroller.value + slide;
            if (cy + card.height > py - CULL && cy < py + ph + CULL) card.draw(cx, cy, cardW, searching);
            else card.skip();
        }
        Draw.popAlpha();
        Draw.popClip();

        if (scroller.max > 0.5f) {
            float track = ph - 12, thumb = Math.max(18, track * ph / content);
            float ty = py + 6 + (track - thumb) * (scroller.value / scroller.max);
            Draw.rect(wx + ww - 5.5f, ty, 2, thumb, 1, pal.wash(0x50ffffff));
        }
    }

    private void drawGrip() {
        float gx = wx + ww - 15, gy = wy + wh - 15;
        boolean hovered = over(gx, gy, 15, 15);
        float hot = gripHot.update(hovered || resizing, dt);
        int color = pal.wash(0.22f + 0.4f * hot);
        Draw.polyline(1, color, gx + 11, gy + 5, gx + 5, gy + 11);
        Draw.polyline(1, color, gx + 11, gy + 8.5f, gx + 8.5f, gy + 11);
        hit(gx, gy, 15, 15, (cx, cy, button) -> {
            if (button != 0) return;
            float offsetX = wx + ww - cx, offsetY = wy + wh - cy;
            resizing = true;
            drag = (dx, dy) -> {
                ww = Math.max(MIN_W, dx + offsetX - wx);
                wh = Math.max(MIN_H, dy + offsetY - wy);
            };
        });
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
            String name = json.has("category") ? json.get("category").getAsString() : "";
            for (Category category : categories) if (category.name.equals(name)) selected = category.index;
            if (CONFIGS.equals(name)) selected = categories.size();
            placed = true;
        } catch (Exception e) {
            Myau.LOGGER.warn("Ignoring unreadable click GUI layout", e);
            placed = false;
        }
    }

    private void saveLayout() {
        JsonObject json = new JsonObject();
        json.addProperty("x", wx);
        json.addProperty("y", wy);
        json.addProperty("w", ww);
        json.addProperty("h", wh);
        json.addProperty("category", onConfigs() ? CONFIGS : categories.get(selected).name);
        try {
            FileUtil.writeAtomic(layoutFile, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException e) {
            Myau.LOGGER.error("Failed to save click GUI layout", e);
        }
    }

    // ================================================================ category

    private final class Category {
        final String name;
        final int index;
        final List<Card> cards = new ArrayList<>();
        final Scroller scroller = new Scroller();
        final Interaction touch = new Interaction();
        int matches, shownEnabled = -1;
        String matchText = "", enabledText = "", summaryText = "";

        Category(CategoryComponent component, int index) {
            this.name = component.getName();
            this.index = index;
            for (Component c : component.getModules()) {
                if (c instanceof ModuleComponent) cards.add(new Card(((ModuleComponent) c).mod, this, cards.size()));
            }
        }

        private void refresh() {
            int enabled = 0;
            for (Card card : cards) if (card.module.isEnabled()) enabled++;
            if (enabled == shownEnabled) return;
            shownEnabled = enabled;
            enabledText = String.valueOf(enabled);
            summaryText = enabled + " of " + cards.size() + " enabled";
        }

        String enabledText() {
            refresh();
            return enabledText;
        }

        String summaryText() {
            refresh();
            return summaryText;
        }
    }

    // ================================================================ module card

    private final class Card extends ModuleView {
        final Category category;
        final int index;
        final Anim.Spring knob;
        final Anim.Smooth chevronHot = new Anim.Smooth(0, 18);
        float top, height = CARD_H;
        int column;

        Card(Module module, Category category, int index) {
            super(module);
            this.category = category;
            this.index = index;
            this.knob = new Anim.Spring(module.isEnabled() ? 1 : 0, 520, 30);
        }

        float layout() {
            float open = layoutSettings();
            height = CARD_H + settingsHeight * open;
            return height;
        }

        /** Out of view: no animation should be waiting when the card scrolls back in. */
        void skip() {
            float on = module.isEnabled() ? 1 : 0;
            enabledAnim.snap(on);
            knob.snap(on);
        }

        void draw(float x, float y, float w, boolean tagged) {
            float radius = 9, open = Anim.clamp01(expand.value);
            boolean hovered = over(x, y, w, CARD_H);
            boolean enabled = module.isEnabled();
            float on = enabledAnim.update(enabled, dt);
            touch.update(hovered, hovered && (Mouse.isButtonDown(0) || Mouse.isButtonDown(1)), dt);
            float hot = touch.hot;
            // A closed card sinks a little while held; an open one stays put so its controls do not move.
            float press = open < 0.01f ? touch.scale(0.018f) : 1;
            Draw.pushTransform(x + w / 2, y + height / 2, press, 0, 0);

            Draw.rect(x, y, w, height, radius, pal.wash(0.055f + 0.035f * hot));
            Draw.outline(x, y, w, height, radius, 0.6f, pal.wash(0.13f + 0.08f * hot), pal.wash(0.04f));
            int a = pal.accentFill(GlassTheme.accent(index)), b = pal.accentFill(GlassTheme.accent(index + 3L));
            if (on > 0.01f) {
                Draw.gradientH(x, y, w, height, radius, Draw.alpha(a, pal.fill(0.26f) * on), Draw.alpha(b, pal.fill(0.09f) * on));
                Draw.outline(x, y, w, height, radius, 0.8f, Draw.alpha(a, 0.6f * on), Draw.alpha(b, 0.22f * on));
            }
            if (hovered) spot(x, y, w, height, radius, 70, 0x16ffffff);

            // Right to left: expand chevron, switch, key cap.
            float mid = y + CARD_H / 2;
            boolean overChevron = over(x + w - 22, y, 22, CARD_H);
            float ch = chevronHot.update(overChevron, dt);
            if (ch > 0.01f) Draw.rect(x + w - 20, y + 6, 16, CARD_H - 12, 6, pal.wash(0.09f * ch));
            Draw.chevron(x + w - 12, mid, 2.6f, 90 * open, 1.1f, Draw.mix(pal.faint, pal.text, Math.max(ch, open * 0.6f)));

            float sx = x + w - 26 - 22, sy = mid - 6, sw2 = 22, sh2 = 12;
            float k = knob.update(enabled ? 1 : 0, dt);
            if (on > 0.01f) glow(sx, sy, sw2, sh2, 6, 6, Draw.alpha(a, 0.4f * on));
            Draw.rect(sx, sy, sw2, sh2, 6, Draw.mix(pal.wash(0x40ffffff), a, on));
            float kx = sx + 6 + (sw2 - 12) * Math.max(-0.08f, Math.min(1.08f, k));
            Draw.shadow(kx - 4.1f, sy + 2.2f, 8.2f, 8.2f, 4.1f, 2.5f, 0x50000000);
            Draw.circle(kx, mid, 4.3f, 0xffffffff);

            float right = sx - 7;
            float keyRight = right, keyWidth = 0;
            if (binding == module) {
                String hint = "Esc to clear";
                fTiny.drawRight(hint, right, fTiny.centerY(y, CARD_H) - 0.5f, pal.accentInk(GlassTheme.accent()));
                right -= fTiny.width(hint) + 6;
            } else {
                String key = keyName();
                if (key != null) {
                    float kw = fTiny.width(key) + 9;
                    keyWidth = kw;
                    Draw.rect(right - kw, mid - 6.5f, kw, 13, 4, pal.wash(0.09f));
                    Draw.outline(right - kw, mid - 6.5f, kw, 13, 4, 0.6f, pal.wash(0.16f), pal.wash(0.05f));
                    fTiny.drawCentered(key, right - kw / 2, fTiny.centerY(y, CARD_H) - 0.5f, pal.muted);
                    right -= kw + 6;
                }
            }

            int color = Draw.mix(Draw.mix(pal.muted, pal.text, hot), pal.text, on);
            float nameX = x + 12, room = right - nameX;
            String name = fCard.trim(module.getName(), room);
            float end = fCard.draw(name, nameX, fCard.centerY(y, CARD_H) - 0.5f, color);
            if (tagged && right - end > fTiny.width(category.name) + 10) {
                fTiny.draw(category.name, end + 7, fTiny.centerY(y, CARD_H), pal.faint);
            }

            float keyLeft = keyRight - keyWidth;
            hit(x, y, w, CARD_H, (cx, cy, button) -> {
                touch.pulse();
                if (button == 0) {
                    if (cx >= keyLeft && cx < keyRight && cy >= mid - 6.5f && cy < mid + 6.5f) {
                        binding = module;
                        UiSound.CLICK.play();
                    } else {
                        module.toggle();
                    }
                } else if (button == 1) toggleExpanded();
                else if (button == 2) {
                    expanded = true;
                    binding = module;
                    UiSound.EXPAND.play();
                }
            });
            hit(x + w - 22, y, 22, CARD_H, (cx, cy, button) -> {
                if (button == 0 || button == 1) toggleExpanded();
            });

            if (open > 0.002f) {
                Draw.rect(x + 10, y + CARD_H - 0.5f, w - 20, 0.5f, 0, pal.wash(0.11f * open));
                drawSettings(x, w, x + 3, y + CARD_H, w - 6, open, false);
            }
            Draw.popTransform();
        }

        private void toggleExpanded() {
            expanded = !expanded;
            (expanded ? UiSound.EXPAND : UiSound.COLLAPSE).play();
        }
    }
}
