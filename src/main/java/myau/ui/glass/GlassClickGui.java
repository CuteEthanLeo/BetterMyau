package myau.ui.glass;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.module.Module;
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
 * The "panels" click GUI layout: floating frosted-glass panels, one per category. Panels can be
 * dragged and collapsed; modules expand in place to show their settings.
 */
public class GlassClickGui extends ClickGuiBase {
    private static final float PANEL_W = 126, HEADER_H = 26, ROW_H = 19, GAP = 10;

    private final List<Panel> panels = new ArrayList<>();
    private final File layoutFile = new File("./config/Myau/", "clickgui-glass.json");
    private final Anim.Smooth searchGlow = new Anim.Smooth(0, 12), layoutLink = new Anim.Smooth(0, 16), augustusLink = new Anim.Smooth(0, 16), guideLink = new Anim.Smooth(0, 16);
    private final String versionText = "BetterMyau v" + Myau.version;

    private final ConfigList configList = new ConfigList();

    public GlassClickGui() {
        for (CategoryComponent category : categories()) panels.add(new Panel(category));
        panels.add(new Panel("Configs", configList));
        loadLayout();
    }

    // ================================================================ lifecycle

    @Override
    protected void onOpen() {
        boolean placed = false;
        for (Panel panel : panels) placed |= panel.placed && panel.configs == null;
        if (!placed) {
            int count = 0;
            for (Panel panel : panels) if (panel.configs == null) count++;
            float total = count * PANEL_W + (count - 1) * GAP;
            float x = Math.max(8, (sw - total) / 2f);
            for (Panel panel : panels) {
                if (panel.configs != null) continue;
                panel.x = x;
                panel.y = 46;
                panel.open = true;
                panel.placed = true;
                x += PANEL_W + GAP;
            }
        }
        // The configs panel (new, or never placed): next to the rightmost panel when there is
        // room, otherwise folded up in the bottom-right corner.
        for (Panel panel : panels) {
            if (panel.configs == null || panel.placed) continue;
            float right = 0, top = 46;
            for (Panel other : panels) {
                if (other.configs != null || other.x + PANEL_W <= right) continue;
                right = other.x + PANEL_W;
                top = other.y;
            }
            if (right + GAP + PANEL_W <= sw - 8) {
                panel.x = right + GAP;
                panel.y = top;
                panel.open = true;
            } else {
                panel.x = sw - PANEL_W - 8;
                panel.y = sh - HEADER_H - 30;
                panel.open = false;
            }
            panel.placed = true;
        }
        configList.refresh(true);
        // Stagger the entrance from the centre of the screen outwards.
        List<Panel> byDistance = new ArrayList<>(panels);
        byDistance.sort((a, b) -> Float.compare(Math.abs(a.x + PANEL_W / 2 - sw / 2), Math.abs(b.x + PANEL_W / 2 - sw / 2)));
        for (int i = 0; i < byDistance.size(); i++) byDistance.get(i).rank = i;
    }

    @Override
    protected void onClosed() {
        saveLayout();
    }

    @Override
    protected float exitDuration() {
        return 0.2f + (panels.size() - 1) * 0.02f;
    }

    @Override
    public void reveal(Module module) {
        for (Panel panel : panels) {
            for (Entry entry : panel.entries) {
                if (entry.module != module) continue;
                panel.open = true;
                panel.toFront();
                entry.expanded = true;
                panel.revealing = entry;
                return;
            }
        }
    }

    // ================================================================ frame

    @Override
    protected void drawContent(float backdrop) {
        // Which panel is under the cursor (topmost first), from this frame's positions.
        hoverLayer = null;
        for (int i = panels.size() - 1; i >= 0 && interactive; i--) {
            Panel panel = panels.get(i);
            if (mx >= panel.x && mx < panel.x + PANEL_W && my >= panel.y && my < panel.y + panel.height) {
                hoverLayer = panel;
                break;
            }
        }
        // A plain loop over a copy-free index: bringing a panel to the front happens on click, not here.
        for (int i = 0; i < panels.size(); i++) {
            Panel panel = panels.get(i);
            drawingLayer = panel;
            panel.draw();
        }
        drawingLayer = null;

        Draw.pushAlpha(backdrop);
        drawSearch(backdrop);
        float end = fTiny.draw(versionText, 10, sh - 10 - fTiny.height(), Draw.fade(pal.muted, 0.75f));
        // The way to the other layout, for whoever has not found the setting yet.
        float linkW = fTiny.width("Sidebar layout"), linkY = sh - 10 - fTiny.height();
        boolean linkHover = over(end + 8, linkY - 3, linkW + 8, fTiny.height() + 6);
        float linkEnd = fTiny.draw("Sidebar layout", end + 12, linkY, Draw.mix(pal.faint, pal.text, layoutLink.update(linkHover, dt)));
        hit(end + 8, linkY - 3, linkW + 8, fTiny.height() + 6, (cx, cy, button) -> {
            if (button != 0 || guiModule() == null) return;
            guiModule().style.setValue(myau.module.modules.GuiModule.STYLE_SIDEBAR);
            UiSound.CLICK.play();
        });
        float augW = fTiny.width("Augustus layout"), augX = linkEnd + 10;
        boolean augHover = over(augX - 4, linkY - 3, augW + 8, fTiny.height() + 6);
        linkEnd = fTiny.draw("Augustus layout", augX, linkY, Draw.mix(pal.faint, pal.text, augustusLink.update(augHover, dt)));
        hit(augX - 4, linkY - 3, augW + 8, fTiny.height() + 6, (cx, cy, button) -> {
            if (button != 0 || guiModule() == null) return;
            guiModule().style.setValue(myau.module.modules.GuiModule.STYLE_AUGUSTUS);
            UiSound.CLICK.play();
        });
        // And the guide, as a small capsule after it.
        float gx = linkEnd + 10, gw = fTiny.width("? 使用指南") + 14, gh = fTiny.height() + 7, gy = linkY - 3.5f;
        boolean guideHover = over(gx, gy, gw, gh);
        float gh2 = guideLink.update(guideHover, dt);
        Draw.rect(gx, gy, gw, gh, gh / 2, pal.wash(0.06f + 0.08f * gh2));
        fTiny.draw("? 使用指南", gx + 7, linkY, Draw.mix(pal.muted, pal.text, gh2));
        hit(gx, gy, gw, gh, (cx, cy, button) -> {
            if (button != 0) return;
            UiSound.CLICK.play();
            mc.displayGuiScreen(new myau.guide.GuideScreen(this));
        });
        fTiny.drawRight("Left click: toggle   Right click: settings   Middle click: bind   Type to search",
                sw - 10, sh - 10 - fTiny.height(), Draw.fade(pal.faint, 0.9f));
        Draw.popAlpha();
    }

    private int matchCount = -1;
    private String matchQuery, matchText = "";

    private void drawSearch(float appear) {
        float w = 196, h = 22, x = (sw - w) / 2f, y = 12 - (1 - appear) * 14;
        boolean active = !search.isEmpty(), focused = (searchFocus || active) && !editing();
        float glow = searchGlow.update(active || focused, dt);
        GlassTheme.panel(x, y, w, h, h / 2, 0.8f, pal);
        hit(x, y, w, h, (cx, cy, button) -> focusSearch());
        if (glow > 0.01f) {
            int accent = pal.accentInk(GlassTheme.accent());
            glow(x, y, w, h, h / 2, 9, Draw.alpha(accent, 0.3f * glow));
            Draw.outline(x, y, w, h, h / 2, 0.9f, Draw.alpha(accent, 0.85f * glow), Draw.alpha(accent, 0.4f * glow));
        }
        int icon = Draw.mix(pal.faint, pal.text, glow);
        Draw.outline(x + 9, y + 6.5f, 7, 7, 3.5f, 1, icon, icon);
        Draw.polyline(1, icon, x + 15, y + 12.5f, x + 17.5f, y + 15);
        SmoothFont font = fRow;
        float ty = font.centerY(y, h) - 0.5f;
        if (active) {
            drawEdit(search, font, x + 23, y, w - 70, h, pal.text, focused, false);
            if (!query.equals(matchQuery)) {
                // Counted when the text changes, not every frame.
                matchQuery = query;
                int matches = 0;
                for (Panel panel : panels) for (Entry entry : panel.entries) if (entry.matches(query)) matches++;
                if (matches != matchCount) {
                    matchCount = matches;
                    matchText = matches + " found";
                }
            }
            fSmall.drawRight(matchText, x + w - 11, fSmall.centerY(y, h) - 0.5f, pal.faint);
        } else {
            matchQuery = null;
            font.draw("Search modules", x + 23, ty, pal.faint);
            if (focused) drawEdit(search, font, x + 23, y, w - 70, h, pal.text, true, false);
        }
    }

    // ================================================================ layout file

    private void loadLayout() {
        if (!layoutFile.exists()) return;
        try {
            JsonElement parsed = new JsonParser().parse(FileUtil.readString(layoutFile));
            if (parsed == null || !parsed.isJsonObject()) return;
            JsonObject json = parsed.getAsJsonObject();
            for (Panel panel : panels) {
                JsonElement element = json.get(panel.name);
                if (element == null || !element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                panel.x = entry.get("x").getAsFloat();
                panel.y = entry.get("y").getAsFloat();
                panel.open = entry.get("open").getAsBoolean();
                panel.placed = true;
            }
        } catch (Exception e) {
            Myau.LOGGER.warn("Ignoring unreadable click GUI layout", e);
            for (Panel panel : panels) panel.placed = false;
        }
    }

    private void saveLayout() {
        JsonObject json = new JsonObject();
        for (Panel panel : panels) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", panel.x);
            entry.addProperty("y", panel.y);
            entry.addProperty("open", panel.open);
            json.add(panel.name, entry);
        }
        try {
            FileUtil.writeAtomic(layoutFile, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException e) {
            Myau.LOGGER.error("Failed to save click GUI layout", e);
        }
    }

    // ================================================================ panel

    private final class Panel {
        final String name;
        final List<Entry> entries = new ArrayList<>();
        /** Set for the saved-configs panel, which shows that list instead of modules. */
        final ConfigList configs;
        final Anim.Smooth openAnim = new Anim.Smooth(1, 16), lift = new Anim.Smooth(1, 14), dimmed = new Anim.Smooth(0, 12);
        final Scroller scroller = new Scroller();
        float x, y, height = HEADER_H;
        boolean open = true, placed, dragging;
        int rank, countEnabled = -1;
        String countText = "";
        Entry revealing;

        Panel(CategoryComponent category) {
            this.name = category.getName();
            this.configs = null;
            for (Component component : category.getModules()) {
                if (component instanceof ModuleComponent) entries.add(new Entry(((ModuleComponent) component).mod, entries.size()));
            }
        }

        Panel(String name, ConfigList configs) {
            this.name = name;
            this.configs = configs;
        }

        void draw() {
            x = Math.max(30 - PANEL_W, Math.min(sw - 30, x));
            y = Math.max(0, Math.min(sh - HEADER_H, y));
            boolean searching = !query.isEmpty();
            boolean anyMatch = false;
            int enabled = 0;
            float content = 4, revealAt = -1;
            if (configs != null) {
                // Not a module list: the search neither opens nor dims it.
                anyMatch = true;
                enabled = configs.count();
                content = 2 + configs.height(PANEL_W);
            }
            for (Entry entry : entries) {
                anyMatch |= entry.matches(query);
                if (entry.module.isEnabled()) enabled++;
                if (entry == revealing) revealAt = content;
                content += entry.layout();
            }
            float openness = Anim.clamp01(openAnim.update(searching && configs == null ? anyMatch : open, dt));
            float maxBody = Math.max(60, Math.min(360, sh - y - HEADER_H - 16));
            float body = Math.min(content, maxBody) * openness;
            scroller.limit(content - maxBody);
            if (revealAt >= 0) {
                scroller.jump(revealAt - 4);
                revealing = null;
            }
            scroller.update();
            float maxScroll = scroller.max, scroll = scroller.value;
            height = HEADER_H + body;

            // Entrance: staggered scale-up with a slight overshoot; exit: quick sink and fade.
            float scale, fade, shift, sheen = -1;
            if (closing) {
                float q = Anim.clamp01((closeTime - (panels.size() - 1 - rank) * 0.02f) / 0.2f);
                scale = 1 - 0.1f * Anim.inCubic(q);
                fade = 1 - q * q;
                shift = 12 * Anim.inCubic(q);
            } else {
                float delay = rank * 0.045f;
                float p = Anim.clamp01((openTime - delay) / 0.42f);
                scale = Anim.lerp(0.86f, 1, Anim.outBack(p));
                fade = Anim.outCubic(Math.min(1, p * 1.8f));
                shift = (1 - Anim.outCubic(p)) * 16;
                sheen = (openTime - delay - 0.26f) / 0.6f;
            }
            float lifted = lift.update(dragging ? 2f : 1f, dt);
            scale *= 1 + (lifted - 1) * 0.018f;
            float dim = dimmed.update(searching && !anyMatch, dt);

            Draw.pushTransform(x + PANEL_W / 2, y + height / 2, scale, 0, shift);
            Draw.pushAlpha(fade * (1 - 0.55f * dim));
            GlassTheme.panel(x, y, PANEL_W, height, GlassTheme.R_PANEL, lifted, pal);
            if (hoverLayer == this) {
                spot(x, y, PANEL_W, height, GlassTheme.R_PANEL, 95, 0x12ffffff);
                spotOutline(x, y, PANEL_W, height, GlassTheme.R_PANEL, 1, 120, 0x8cffffff);
            }
            hit(x, y, PANEL_W, height, (cx, cy, button) -> toFront());
            scrollHit(x, y, PANEL_W, height, notches -> scroller.nudge(-notches * 38f));

            // Header
            int accent = pal.accentInk(GlassTheme.accent(rank * 2L));
            glow(x + 10, y + HEADER_H / 2 - 2.5f, 5, 5, 2.5f, 5, Draw.alpha(accent, 0.55f));
            Draw.circle(x + 12.5f, y + HEADER_H / 2, 2.5f, accent);
            fTitle.draw(name, x + 21, fTitle.centerY(y, HEADER_H) - 0.5f, pal.text);
            if (enabled != countEnabled) {
                countEnabled = enabled;
                countText = configs != null ? String.valueOf(enabled) : enabled + "/" + entries.size();
            }
            fSmall.drawRight(countText, x + PANEL_W - 22, fSmall.centerY(y, HEADER_H) - 0.5f, pal.faint);
            Draw.chevron(x + PANEL_W - 13, y + HEADER_H / 2, 3, 90 * openness, 1, over(x, y, PANEL_W, HEADER_H) ? pal.text : pal.muted);
            hit(x, y, PANEL_W, HEADER_H, (cx, cy, button) -> {
                toFront();
                if (button == 1 || (button == 0 && cx >= x + PANEL_W - 24)) {
                    open = !open;
                    (open ? UiSound.EXPAND : UiSound.COLLAPSE).play();
                } else if (button == 0) {
                    float offsetX = cx - x, offsetY = cy - y;
                    dragging = true;
                    drag = (dx, dy) -> {
                        x = dx - offsetX;
                        y = dy - offsetY;
                    };
                }
            });
            if (dragging && drag == null) dragging = false;

            if (body > 0.5f) {
                Draw.rect(x + 10, y + HEADER_H - 0.5f, PANEL_W - 20, 0.5f, 0, pal.wash(0.12f * openness));
                Draw.pushClip(x, y + HEADER_H, PANEL_W, body - 3);
                float top = y + HEADER_H, bottom = top + body, yy = top + 2 - scroll;
                if (configs != null) configs.draw(x, yy, PANEL_W);
                for (Entry entry : entries) {
                    if (entry.height > 0.2f && yy + entry.height > top && yy < bottom) entry.draw(x, yy, PANEL_W);
                    yy += entry.height;
                }
                Draw.popClip();
                if (maxScroll > 0.5f && openness > 0.9f) {
                    float track = body - 10, thumb = Math.max(14, track * maxBody / content);
                    float ty = y + HEADER_H + 4 + (track - thumb) * (scroll / maxScroll);
                    Draw.rect(x + PANEL_W - 3.5f, ty, 1.5f, thumb, 0.75f, pal.wash(0x50ffffff));
                }
            }
            if (sheen > 0 && sheen < 1 && !pal.light) Draw.sheen(x, y, PANEL_W, height, GlassTheme.R_PANEL, sheen, Draw.alpha(0xffffff, 0.2f * (1 - sheen * sheen)));
            Draw.popAlpha();
            Draw.popTransform();
        }

        void toFront() {
            if (panels.get(panels.size() - 1) != this) {
                panels.remove(this);
                panels.add(this);
            }
        }
    }

    // ================================================================ module entry

    private final class Entry extends ModuleView {
        final int index;
        final Anim.Smooth match = new Anim.Smooth(1, 16);
        float height;

        Entry(Module module, int index) {
            super(module);
            this.index = index;
        }

        float layout() {
            float visible = match.update(matches(query), dt);
            float open = layoutSettings();
            height = (ROW_H + settingsHeight * open) * Anim.clamp01(visible);
            return height;
        }

        void draw(float x, float y, float w) {
            float visible = Anim.clamp01(match.value);
            if (visible < 0.999f) {
                Draw.pushClip(x, y, w, height);
                Draw.pushAlpha(visible);
            }
            float rx = x + 5, rw = w - 10, ry = y + 1, rh = ROW_H - 2, radius = 6;
            boolean hovered = over(rx, ry, rw, rh);
            float on = enabledAnim.update(module.isEnabled(), dt);
            touch.update(hovered, hovered && (Mouse.isButtonDown(0) || Mouse.isButtonDown(1)), dt);
            float hot = touch.hot;
            // The row sinks a little while held and springs back when it fires.
            Draw.pushTransform(rx + rw / 2, ry + rh / 2, touch.scale(0.03f), 0, 0);
            int onText = 0xffffffff;
            if (on > 0.01f) {
                int a = pal.accentFill(GlassTheme.accent(index)), b = pal.accentFill(GlassTheme.accent(index + 3L));
                onText = pal.onAccent(Draw.mix(a, b, 0.4f));
                glow(rx, ry, rw, rh, radius, 7, Draw.alpha(a, 0.3f * on));
                Draw.gradientH(rx, ry, rw, rh, radius, Draw.alpha(a, pal.fill(0.78f) * on), Draw.alpha(b, pal.fill(0.5f) * on));
                Draw.gradientV(rx, ry, rw, rh, radius, Draw.alpha(0xffffff, 0.16f * on), 0x00ffffff);
            }
            if (hot > 0.01f) Draw.rect(rx, ry, rw, rh, radius, pal.wash(0.07f * hot));
            if (hovered) spot(rx, ry, rw, rh, radius, 44, 0x1cffffff);

            SmoothFont font = fRow;
            float ty = font.centerY(ry, rh) - 0.5f;
            int color = Draw.mix(Draw.mix(pal.muted, pal.text, hot), onText, on);
            float open = Anim.clamp01(expand.value);
            float right = rx + rw - 15;
            if (binding == module) {
                String hint = "Esc to clear";
                fTiny.drawRight(hint, right, fTiny.centerY(ry, rh) - 0.5f, Draw.fade(color, 0.85f));
                right -= fTiny.width(hint) + 4;
            } else {
                String key = keyName();
                if (key != null) {
                    fTiny.drawRight(key, right, fTiny.centerY(ry, rh) - 0.5f, Draw.fade(color, 0.55f));
                    right -= fTiny.width(key) + 4;
                }
            }
            font.draw(font.trim(module.getName(), right - rx - 8), rx + 8, ty, color);
            Draw.chevron(rx + rw - 8, ry + rh / 2, 2.4f, 90 * open, 1, Draw.fade(color, 0.45f + 0.4f * hot));
            Draw.popTransform();
            hit(rx, ry, rw, rh, (cx, cy, button) -> {
                touch.pulse();
                if (button == 0) module.toggle();
                else if (button == 1) {
                    expanded = !expanded;
                    (expanded ? UiSound.EXPAND : UiSound.COLLAPSE).play();
                } else if (button == 2) {
                    expanded = true;
                    binding = module;
                    UiSound.EXPAND.play();
                }
            });

            if (open > 0.002f) drawSettings(x, w, rx + 1, y + ROW_H, rw - 2, open, true);
            if (visible < 0.999f) {
                Draw.popAlpha();
                Draw.popClip();
            }
        }
    }
}
