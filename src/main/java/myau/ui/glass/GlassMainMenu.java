package myau.ui.glass;

import me.ksyz.accountmanager.gui.GuiAccountManager;
import myau.Myau;
import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The main menu as a left-aligned column of words over the menu scene: the name with an accent
 * bar, the version, then "PLAY" and "SYSTEM" groups of plain-text entries and a row of small
 * links. The signed-in account sits top right; the scene and glass switchers bottom right.
 * <p>
 * Beyond the look: an accent marker that springs between entries, a staggered entrance (handed
 * over from the boot {@link Intro}), keyboard navigation (arrows, Enter, 1-6, R), and a "继续"
 * entry that goes straight back into the last world or server.
 * <p>
 * It extends {@link GuiMainMenu} so everything that checks for the main menu still sees one, but
 * it never runs the vanilla menu's code (its private state stays unused); the vanilla menu with
 * its restyling mixin remains for when HUD {@code glass-main-menu} is off. The constructor and
 * {@link #initGui()} can run before the client has loaded (the first menu is shown during
 * startup), so they touch nothing of it.
 */
public class GlassMainMenu extends GuiMainMenu implements myau.util.ime.TextInputScreen {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final float DESIGN_H = 520, ROW = 17.5f, TITLE = 23, ITEM = 10, TRACK = 2.4f;
    private static final int ONLINE = 0xff3ddc84, OFFLINE = 0xffffb340;
    private static final String NAME = "BETTERMYAU";

    private final DesignKit kit = DesignKit.scale(DESIGN_H);
    private boolean opened, returning;
    private float entranceSpeed = 1;

    private static final int PLAY = 0, SYSTEM = 1;

    private final class Item {
        final String label, sub;
        final int section, key;
        final Runnable action;
        final boolean activates;
        final Anim.Smooth hot = new Anim.Smooth(0, 16);
        float y;

        Item(String label, String sub, int section, int key, boolean activates, Runnable action) {
            this.label = label;
            this.sub = sub;
            this.section = section;
            this.key = key;
            this.activates = activates;
            this.action = action;
        }
    }

    private final class Link {
        final String label;
        final Runnable action;
        final Anim.Smooth hot = new Anim.Smooth(0, 18);

        Link(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private Item resume;

    // Keyboard focus (index into items), or -1 while the mouse leads.
    private int focus = -1;
    private Item lastHovered;
    private float lastMx = Float.NaN, lastMy = Float.NaN;
    private final Anim.Spring markerY = new Anim.Spring(0, 320, 30);
    private final Anim.Smooth markerShow = new Anim.Smooth(0, 14);
    private boolean markerPlaced;

    // Scene switcher label: slides sideways when the scene changes.
    private int shownScene = -1, previousScene = -1, slideDirection = 1;
    private long sceneChangedAt;
    // Width of the glass segment of the switcher, eased between its three labels.
    private final Anim.Smooth glassWidth = new Anim.Smooth(0, 16);

    private String toast;
    private long toastAt;

    // Layout (units)
    private float colX, titleY, titleCenterY;

    public GlassMainMenu() {
        super();
    }

    // ================================================================ lifecycle

    @Override
    public void initGui() {
        this.buttonList.clear();
        buildEntries();
        if (!opened) {
            opened = true;
            kit.restart();
        } else if (returning) {
            // Coming back from another screen: replay the entrance, briskly.
            returning = false;
            kit.restart();
            entranceSpeed = 2.2f;
        }
    }

    @Override
    public void onGuiClosed() {
        returning = true;
        focus = -1;
    }

    @Override
    public void updateScreen() {
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public boolean wantsTextInput() {
        return false;
    }

    private void buildEntries() {
        items.clear();
        links.clear();
        resume = null;
        LastPlayed.Entry last = null;
        try {
            last = LastPlayed.get();
        } catch (RuntimeException ignored) {
        }
        if (last != null && (last.kind == LastPlayed.SERVER || LastPlayed.worldExists(last.id))) {
            LastPlayed.Entry entry = last;
            String sub = (entry.kind == LastPlayed.WORLD ? t("世界 · ", "World · ") : t("服务器 · ", "Server · ")) + entry.name;
            resume = new Item(t("继续游戏", "Continue"), sub, PLAY, Keyboard.KEY_R, true, () -> resume(entry));
            items.add(resume);
        }
        items.add(new Item(plain("menu.singleplayer"), null, PLAY, Keyboard.KEY_1, true,
                () -> mc.displayGuiScreen(new GuiSelectWorld(this))));
        items.add(new Item(plain("menu.multiplayer"), null, PLAY, Keyboard.KEY_2, true,
                () -> mc.displayGuiScreen(new GuiMultiplayer(this))));
        items.add(new Item(t("帐号管理", "Accounts"), null, PLAY, Keyboard.KEY_3, true,
                () -> mc.displayGuiScreen(new GuiAccountManager(this))));
        items.add(new Item(plain("menu.options"), null, SYSTEM, Keyboard.KEY_4, true,
                () -> mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings))));
        items.add(new Item(t("模组", plain("fml.menu.mods")), null, SYSTEM, Keyboard.KEY_5, true,
                () -> mc.displayGuiScreen(new net.minecraftforge.fml.client.GuiModList(this))));
        // Quitting from a stray key press would be rude: its digit only moves the focus there.
        items.add(new Item(plain("menu.quit"), null, SYSTEM, Keyboard.KEY_6, false, mc::shutdown));

        links.add(new Link(t("使用指南", "Guide"), () -> mc.displayGuiScreen(new myau.guide.GuideScreen(this))));
        links.add(new Link(t("音乐", "Music"), () -> mc.displayGuiScreen(new myau.music.ui.MusicScreen())));
        links.add(new Link(plain("options.language"), () -> mc.displayGuiScreen(new GuiLanguage(this, mc.gameSettings, mc.getLanguageManager()))));
        links.add(new Link(t("配置文件夹", "Config folder"), this::openConfigFolder));
        links.add(new Link(t("关于", "About"), () -> mc.displayGuiScreen(new myau.guide.GuideScreen(this, "about"))));
    }

    /** Chinese when the game is in Chinese, English otherwise (for the client's own labels). */
    private static String t(String chinese, String english) {
        String language = mc.gameSettings == null ? null : mc.gameSettings.language;
        return language == null || language.toLowerCase(java.util.Locale.ROOT).startsWith("zh") ? chinese : english;
    }

    /** A vanilla label without the trailing dots ("Options..." reads as a word here). */
    private static String plain(String key) {
        String text = I18n.format(key);
        while (text.endsWith(".") || text.endsWith("…")) text = text.substring(0, text.length() - 1);
        return text.trim();
    }

    // ================================================================ frame

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Intro.frame();
        float dt = kit.begin();
        Draw.useGuiScale();
        MenuBackdrop.draw(this.width, this.height);
        GlassBlur.invalidate();
        ScreenTransition.markGlass();

        kit.push();
        try {
            if (busy()) {
                drawBusy();
            } else {
                trackPointer();
                layout();
                drawTitle();
                drawEntries(dt);
                drawLinks(dt);
                drawAccount(dt);
                drawSwitcher(dt);
                drawFooter();
                drawToast();
            }
        } finally {
            kit.pop();
        }
        if (Intro.running()) {
            Intro.overlay(this.width, this.height, (colX + titleWidth() / 2) * kit.zoom, titleCenterY * kit.zoom, TITLE * kit.zoom);
        }
    }

    /**
     * A world is being started (the menu is shown for a moment in between): no menu to flash.
     * Not {@code getIntegratedServer() != null}: Minecraft creates a placeholder server in its
     * constructor, so that is never null.
     */
    private boolean busy() {
        return mc.isIntegratedServerRunning() && mc.theWorld == null;
    }

    private void drawBusy() {
        float cx = kit.sw / 2, cy = kit.sh / 2;
        DesignKit.spinner(cx, cy - 12, 8, 1.6f, 0xffffffff);
        SmoothFont font = SmoothFont.regular(9);
        font.drawCentered(t("正在进入世界…", "Loading world…"), cx, cy + 6, GlassTheme.TEXT);
    }

    private void trackPointer() {
        if (Float.isNaN(lastMx) || Math.abs(kit.mx - lastMx) > 0.5f || Math.abs(kit.my - lastMy) > 0.5f) {
            if (!Float.isNaN(lastMx)) focus = -1;
            lastMx = kit.mx;
            lastMy = kit.my;
        }
    }

    /** Seconds on the entrance clock (the boot opening's while it runs). */
    private float clock() {
        return Intro.running() ? Intro.menuSeconds() : kit.openTime * entranceSpeed;
    }

    private float appear(float delay) {
        return Anim.outCubic((clock() - delay) / 0.45f);
    }

    private static int accent() {
        return GlassTheme.vividAccent();
    }

    private float titleWidth() {
        return spacedWidth(SmoothFont.bold(TITLE), NAME, TRACK);
    }

    private void layout() {
        SmoothFont title = SmoothFont.bold(TITLE);
        float labelH = 10, gapAfterTitle = 30, sectionGap = 12, linksGap = 16;
        int play = 0, system = 0;
        for (Item item : items) {
            if (item.section == PLAY) play++;
            else system++;
        }
        float blockH = title.height() + 13 + gapAfterTitle + labelH + play * ROW + sectionGap + labelH + system * ROW + linksGap + 10;
        colX = Math.max(36, kit.sw * 0.08f);
        float top = Math.max(64, kit.sh * 0.5f - blockH / 2);
        titleY = top;
        titleCenterY = top + title.height() / 2;
        float y = top + title.height() + 13 + gapAfterTitle + labelH;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (i > 0 && items.get(i - 1).section != item.section) y += sectionGap + labelH;
            item.y = y;
            y += ROW;
        }
    }

    // ================================================================ title

    private void drawTitle() {
        int accent = accent();
        float alpha = Intro.running() ? Intro.titleAlpha() : appear(0);
        if (alpha <= 0) return;
        SmoothFont title = SmoothFont.bold(TITLE);
        float th = title.height();
        Draw.pushAlpha(alpha);
        // Accent bar beside the name.
        float barH = th * 0.72f, barY = titleY + (th - barH) / 2;
        Draw.glow(colX - 12, barY, 2.2f, barH, 1.1f, 6, Draw.alpha(accent, 0.45f));
        Draw.rect(colX - 12, barY, 2.2f, barH, 1.1f, accent);
        drawSpaced(title, NAME, colX, titleY, TRACK, GlassTheme.TEXT);

        SmoothFont small = SmoothFont.regular(7.5f);
        String version = "v" + (Myau.version == null ? "dev" : Myau.version) + "  ·  Minecraft 1.8.9";
        float vy = titleY + th + 1;
        small.drawShadowed(version, colX + 1, vy, Draw.alpha(0xffffff, 0.72f));
        float underline = 46 * Anim.outCubic((clock() - 0.2f) / 0.6f);
        if (Intro.running()) underline *= Intro.titleAlpha();
        if (underline > 0.5f) Draw.gradientH(colX + 1, vy + small.height() + 4, underline, 1.6f, 0.8f, accent, accent & 0xffffff);
        Draw.popAlpha();
    }

    private static float drawSpaced(SmoothFont font, String text, float x, float y, float spacing, int color) {
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            font.drawShadowed(ch, x, y, color);
            x += font.width(ch) + spacing;
        }
        return x;
    }

    private static float spacedWidth(SmoothFont font, String text, float spacing) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += font.width(String.valueOf(text.charAt(i))) + spacing;
        return Math.max(0, w - spacing);
    }

    // ================================================================ entries

    private void drawEntries(float dt) {
        int accent = accent();
        SmoothFont font = SmoothFont.regular(ITEM);
        SmoothFont subFont = SmoothFont.regular(7.5f);
        SmoothFont label = SmoothFont.bold(6);
        Item hovered = null;

        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            float a = appear(0.12f + i * 0.045f);
            boolean firstOfSection = i == 0 || items.get(i - 1).section != item.section;
            if (firstOfSection && a > 0) {
                Draw.pushAlpha(a);
                drawSpaced(label, item.section == PLAY ? "PLAY" : "SYSTEM", colX + 1, item.y - 11, 1.6f, Draw.alpha(0xffffff, 0.48f));
                Draw.popAlpha();
            }
            float labelW = font.width(item.label);
            float subW = item.sub == null ? 0 : subFont.width(item.sub) + 10;
            float hitW = Math.max(150, labelW + subW + 28);
            boolean over = a > 0.6f && kit.over(colX - 16, item.y, hitW, ROW);
            boolean active = focus >= 0 ? focus == i : over;
            if (active) hovered = item;
            float hot = item.hot.update(active, dt);
            if (a <= 0) continue;

            Draw.pushAlpha(a);
            float shift = (1 - a) * -12 + hot * 4;
            if (hot > 0.01f) {
                Draw.gradientH(colX - 16, item.y + 1, hitW + 40, ROW - 2, 4, Draw.alpha(accent, 0.16f * hot), Draw.alpha(accent, 0));
            }
            int color = Draw.mix(Draw.alpha(0xffffff, 0.84f), 0xffffffff, hot);
            float ty = font.centerY(item.y, ROW) - 0.5f;
            font.drawShadowed(item.label, colX + shift, ty, color);
            if (item.sub != null) {
                subFont.drawShadowed(item.sub, colX + shift + labelW + 10, subFont.centerY(item.y, ROW) - 0.3f,
                        Draw.alpha(0xffffff, 0.5f + 0.2f * hot));
            }
            if (focus >= 0) {
                String keyName = item.key == Keyboard.KEY_R ? "R" : String.valueOf(item.key - Keyboard.KEY_1 + 1);
                SmoothFont cap = SmoothFont.bold(6.5f);
                float cw = Math.max(11, cap.width(keyName) + 6), cx = colX + shift + labelW + subW + 10, cy = item.y + (ROW - 11) / 2;
                Draw.rect(cx, cy, cw, 11, 3, Draw.alpha(0x000000, 0.25f));
                Draw.outline(cx, cy, cw, 11, 3, 0.6f, Draw.alpha(0xffffff, 0.3f), Draw.alpha(0xffffff, 0.12f));
                cap.drawCentered(keyName, cx + cw / 2, cap.centerY(cy, 11) - 0.3f, Draw.alpha(0xffffff, 0.7f));
            }
            Draw.popAlpha();
            if (a > 0.6f) {
                final int index = i;
                kit.hit(colX - 16, item.y, hitW, ROW, () -> activate(index));
            }
        }

        if (hovered != lastHovered) {
            if (hovered != null && focus < 0) UiSound.hover();
            lastHovered = hovered;
        }
        // The marker: a short accent bar that springs from entry to entry.
        float show = markerShow.update(hovered != null, dt);
        if (hovered != null) {
            float target = hovered.y + ROW / 2;
            if (!markerPlaced || show < 0.05f) markerY.snap(target);
            else markerY.update(target, dt);
            markerPlaced = true;
        }
        if (show > 0.01f && markerPlaced) {
            float h = 10 * (0.6f + 0.4f * show), y = markerY.value - h / 2;
            Draw.glow(colX - 12, y, 2.2f, h, 1.1f, 6, Draw.alpha(accent, 0.5f * show));
            Draw.rect(colX - 12, y, 2.2f, h, 1.1f, Draw.alpha(accent, show));
        }
    }

    private void activate(int index) {
        if (index < 0 || index >= items.size()) return;
        UiSound.CLICK.play();
        items.get(index).action.run();
    }

    // ================================================================ links

    private void drawLinks(float dt) {
        if (items.isEmpty()) return;
        Item lastItem = items.get(items.size() - 1);
        float y = lastItem.y + ROW + 12;
        float a = appear(0.12f + items.size() * 0.045f + 0.05f);
        if (a <= 0) return;
        SmoothFont font = SmoothFont.regular(7.5f);
        int accent = accent();
        float x = colX;
        float h = 12;
        Draw.pushAlpha(a);
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i);
            float w = font.width(link.label);
            boolean over = a > 0.6f && kit.over(x - 3, y - 1, w + 6, h + 2);
            float hot = link.hot.update(over, dt);
            int color = Draw.mix(Draw.alpha(0xffffff, 0.58f), 0xffffffff, hot);
            font.drawShadowed(link.label, x, font.centerY(y, h) - 0.4f, color);
            if (hot > 0.01f) Draw.rect(x, y + h - 0.5f, w * hot, 0.8f, 0.4f, Draw.alpha(accent, hot));
            // The guide keeps its "new" dot until it has been opened once.
            if (i == 0 && !myau.guide.GuideState.seen()) {
                double pulse = 0.5 + 0.5 * Math.sin(System.nanoTime() / 1e9 * Math.PI * 1.4);
                float dx = x + w + 2.5f, dy = y + 2;
                Draw.glow(dx - 1.8f, dy - 1.8f, 3.6f, 3.6f, 1.8f, 3 + (float) pulse * 2, Draw.alpha(0xff4d6d, 0.55f));
                Draw.circle(dx, dy, 1.8f, 0xffff4d6d);
            }
            if (a > 0.6f) {
                Runnable action = link.action;
                kit.hit(x - 3, y - 1, w + 6, h + 2, () -> {
                    UiSound.CLICK.play();
                    action.run();
                });
            }
            x += w;
            if (i < links.size() - 1) {
                font.draw("·", x + 6, font.centerY(y, h) - 0.4f, Draw.alpha(0xffffff, 0.35f));
                x += 6 + font.width("·") + 6;
            }
        }
        Draw.popAlpha();
    }

    private void openConfigFolder() {
        File dir = new File("./config/Myau/");
        Thread thread = new Thread(() -> {
            try {
                Desktop.getDesktop().open(dir);
            } catch (Exception e) {
                Myau.LOGGER.warn("[Glass] cannot open {}", dir, e);
            }
        }, "BetterMyau-OpenFolder");
        thread.setDaemon(true);
        thread.start();
        showToast(t("已打开配置文件夹", "Opened the config folder"));
    }

    // ================================================================ account (top right)

    private void drawAccount(float dt) {
        float a = appear(0.3f);
        if (a <= 0) return;
        String name = mc.getSession().getUsername();
        String token = mc.getSession().getToken();
        boolean online = token != null && token.length() > 30;
        SmoothFont nameFont = SmoothFont.regular(8.5f), actionFont = SmoothFont.regular(7.5f);
        String action = t("切换帐号", "Switch");
        float h = 22, face = 14;
        float w = 4 + face + 7 + nameFont.width(name) + 9 + actionFont.width(action) + 11;
        float x = kit.sw - 20 - w, y = 18;
        Interaction feel = kit.feel("account");
        boolean over = a > 0.6f && kit.over(x, y, w, h);
        feel.update(over, over && Mouse.isButtonDown(0), dt);
        int accent = accent();

        Draw.pushAlpha(a);
        Draw.pushTransform(x + w / 2, y + h / 2, feel.scale(0.04f), 0, (1 - a) * -6);
        GlassTheme.panel(x, y, w, h, h / 2, 0.8f);
        if (feel.hot > 0.01f) Draw.outline(x, y, w, h, h / 2, 0.8f, Draw.alpha(accent, 0.75f * feel.hot), Draw.alpha(accent, 0.3f * feel.hot));
        float fx = x + 4, fy = y + (h - face) / 2;
        Faces.draw(fx, fy, face, face / 2, Faces.session());
        int status = online ? ONLINE : OFFLINE;
        Draw.circle(fx + face - 1.5f, fy + face - 1.5f, 3.2f, 0xff15161c);
        Draw.glow(fx + face - 4, fy + face - 4, 5, 5, 2.5f, 3, Draw.alpha(status, 0.6f));
        Draw.circle(fx + face - 1.5f, fy + face - 1.5f, 2.2f, status);
        float tx = fx + face + 7;
        float end = nameFont.draw(name, tx, nameFont.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        actionFont.draw(action, end + 9, actionFont.centerY(y, h) - 0.4f, Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, feel.hot));
        Draw.popTransform();
        Draw.popAlpha();
        if (over) {
            SmoothFont tip = SmoothFont.regular(7);
            String text = online ? t("正版会话", "Premium session") : t("离线会话", "Offline session");
            tip.drawRight(text, x + w - 4, y + h + 5, Draw.alpha(status, 0.9f * feel.hot));
        }
        if (a > 0.6f) kit.hit(x, y, w, h, () -> {
            feel.pulse();
            UiSound.CLICK.play();
            mc.displayGuiScreen(new GuiAccountManager(this));
        });
    }

    // ================================================================ switcher (bottom right)

    private void drawSwitcher(float dt) {
        float a = appear(0.35f);
        if (a <= 0) return;
        HUD hud = hud();
        int scene = MenuBackdrop.current();
        long now = System.nanoTime();
        if (shownScene < 0) {
            shownScene = scene;
        } else if (scene != shownScene) {
            previousScene = shownScene;
            shownScene = scene;
            sceneChangedAt = now;
        }

        SmoothFont font = SmoothFont.regular(8);
        float h = 24, arrow = 18;
        float labelW = 0;
        for (int i = 0; i < MenuBackdrop.COUNT; i++) labelW = Math.max(labelW, font.width(MenuBackdrop.label(i)));
        labelW += 12;
        // 0 frosted, 1 liquid, 2 NextGen. The label says what is drawn: a machine that cannot draw
        // NextGen glass shows liquid, whatever the setting holds.
        final boolean canBetter = Draw.betterSupported();
        final int glass = hud != null && !hud.liquidGlass.getValue() ? 0
                : canBetter && (hud == null || hud.betterLiquidGlass.getValue()) ? 2 : 1;
        String glassText = t("玻璃: ", "Glass: ") + (glass == 2 ? t("液态 NextGen", "Liquid NextGen")
                : glass == 1 ? t("液态", "Liquid") : t("磨砂", "Frosted"));
        // The three labels differ a lot in width: the capsule eases to the new one instead of jumping.
        float glassTarget = font.width(glassText) + 22;
        if (glassWidth.value <= 0) glassWidth.snap(glassTarget);
        float glassW = glassWidth.update(glassTarget, dt);
        float w = 4 + arrow + labelW + arrow + 4 + 1 + glassW + 4;
        float x = kit.sw - 20 - w, y = kit.sh - 20 - 12 - h;
        int accent = accent();

        Draw.pushAlpha(a);
        Draw.pushTransform(x + w / 2, y + h / 2, 1, 0, (1 - a) * 8);
        GlassTheme.panel(x, y, w, h, h / 2, 0.8f);

        float sx = x + 4;
        segment("scene-prev", sx, y + 3, arrow, h - 6, a, () -> step(-1));
        Draw.chevron(sx + arrow / 2, y + h / 2, 2.6f, 180, 1.3f, Draw.mix(Draw.alpha(0xffffff, 0.7f), 0xffffffff, kit.feel("scene-prev").hot));
        sx += arrow;

        // The name slides out and the new one in, the way the arrows point.
        float p = previousScene < 0 ? 1 : Anim.outCubic((now - sceneChangedAt) / 1e9f / 0.32f);
        if (p >= 1) previousScene = -1;
        Draw.pushClip(sx, y, labelW, h);
        float cy = font.centerY(y, h) - 0.5f;
        if (previousScene >= 0) {
            Draw.pushAlpha(1 - p);
            font.drawCentered(MenuBackdrop.label(previousScene), sx + labelW / 2 - slideDirection * 14 * p, cy, GlassTheme.TEXT);
            Draw.popAlpha();
        }
        Draw.pushAlpha(p);
        font.drawCentered(MenuBackdrop.label(shownScene), sx + labelW / 2 + slideDirection * 14 * (1 - p), cy, GlassTheme.TEXT);
        Draw.popAlpha();
        Draw.popClip();
        sx += labelW;

        segment("scene-next", sx, y + 3, arrow, h - 6, a, () -> step(1));
        Draw.chevron(sx + arrow / 2, y + h / 2, 2.6f, 0, 1.3f, Draw.mix(Draw.alpha(0xffffff, 0.7f), 0xffffffff, kit.feel("scene-next").hot));
        sx += arrow + 4;

        Draw.rect(sx, y + 6, 0.8f, h - 12, 0, Draw.alpha(0xffffff, 0.22f));
        sx += 1;
        segment("glass", sx + 2, y + 3, glassW - 4, h - 6, a, () -> {
            if (hud == null) return;
            // Frosted, liquid, NextGen, and round again.
            int next = (glass + 1) % 3;
            if (next == 2 && !canBetter) {
                next = 0;
                showToast(t("这台设备画不了液态玻璃 NextGen，已回到磨砂", "Liquid glass NextGen is not available on this GPU; back to frosted"));
            } else {
                showToast(next == 2 ? t("液态玻璃 NextGen：边缘折射场景，高光随视角流动", "Liquid glass NextGen: the edge bends the scene, light follows the view")
                        : next == 1 ? t("液态玻璃：折射与高光边", "Liquid glass: refraction and rim light")
                        : t("磨砂玻璃：更省性能", "Frosted glass: lighter on the GPU"));
            }
            hud.liquidGlass.setValue(next != 0);
            hud.betterLiquidGlass.setValue(next == 2);
        });
        float dot = 4;
        int dotColor = glass == 0 ? Draw.alpha(0xffffff, 0.55f) : accent;
        if (glass == 2) Draw.glow(sx + 10 - dot / 2, y + h / 2 - dot / 2, dot, dot, dot / 2, 4, Draw.alpha(accent, 0.6f));
        Draw.circle(sx + 10, y + h / 2, dot / 2, dotColor);
        font.draw(glassText, sx + 16, cy, Draw.mix(Draw.alpha(0xffffff, 0.85f), 0xffffffff, kit.feel("glass").hot));
        Draw.popTransform();
        Draw.popAlpha();
    }

    /** A clickable part of the switcher capsule with its own hover wash. */
    private void segment(String id, float x, float y, float w, float h, float appear, Runnable action) {
        Interaction feel = kit.feel(id);
        boolean over = appear > 0.6f && kit.over(x, y, w, h);
        feel.update(over, over && Mouse.isButtonDown(0), Anim.dt());
        if (feel.hot > 0.01f) Draw.rect(x, y, w, h, h / 2, Draw.alpha(0xffffff, 0.10f * feel.hot + 0.06f * feel.down));
        if (appear > 0.6f) kit.hit(x, y, w, h, () -> {
            feel.pulse();
            UiSound.CLICK.play();
            action.run();
        });
    }

    private void step(int direction) {
        slideDirection = direction;
        MenuBackdrop.step(direction);
    }

    private static HUD hud() {
        try {
            return Myau.moduleManager == null ? null : (HUD) Myau.moduleManager.modules.get(HUD.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ================================================================ footer and toast

    private void drawFooter() {
        float a = appear(0.4f);
        if (a <= 0) return;
        SmoothFont font = SmoothFont.regular(6.5f);
        Draw.pushAlpha(a);
        font.drawRight("Copyright Mojang AB. Do not distribute!", kit.sw - 20, kit.sh - 16, Draw.alpha(0xffffff, 0.4f));
        String forge;
        try {
            int mods = net.minecraftforge.fml.common.Loader.instance().getActiveModList().size();
            forge = "Minecraft 1.8.9  ·  Forge " + net.minecraftforge.common.ForgeVersion.getVersion() + "  ·  " + mods + t(" 个模组", " mods");
        } catch (RuntimeException e) {
            forge = "Minecraft 1.8.9";
        }
        font.draw(forge, 20, kit.sh - 16, Draw.alpha(0xffffff, 0.34f));
        Draw.popAlpha();
    }

    private void showToast(String text) {
        toast = text;
        toastAt = System.nanoTime();
    }

    private void drawToast() {
        if (toast == null) return;
        float t = (System.nanoTime() - toastAt) / 1e9f;
        if (t > 1.8f) {
            toast = null;
            return;
        }
        float a = Anim.outCubic(t / 0.18f) * (1 - Anim.clamp01((t - 1.5f) / 0.3f));
        Draw.pushAlpha(a);
        GlassTheme.toast(toast, kit.sw / 2, kit.sh - 52 + (1 - a) * 6);
        Draw.popAlpha();
    }

    // ================================================================ actions

    private void resume(LastPlayed.Entry entry) {
        if (entry.kind == LastPlayed.WORLD) {
            if (!mc.getSaveLoader().canLoadWorld(entry.id)) {
                showToast(t("找不到这个世界了", "That world is gone"));
                return;
            }
            FMLClientHandler.instance().tryLoadExistingWorld(new GuiSelectWorld(this), entry.id, entry.name);
            return;
        }
        ServerData data = null;
        try {
            ServerList list = new ServerList(mc);
            list.loadServerList();
            for (int i = 0; i < list.countServers(); i++) {
                ServerData saved = list.getServerData(i);
                if (saved.serverIP.equalsIgnoreCase(entry.id)) {
                    data = saved;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (data == null) data = new ServerData(entry.name, entry.id, false);
        FMLClientHandler.instance().connectToServer(new GuiMultiplayer(this), data);
    }

    // ================================================================ input

    @Override
    public void handleMouseInput() {
        if (Mouse.getEventButtonState() && Mouse.getEventButton() == 0 && kit.interactive()) {
            kit.click(kit.eventX(), kit.eventY());
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0 && kit.interactive()) kit.click(kit.fromGui(mouseX), kit.fromGui(mouseY));
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
    }

    @Override
    protected void actionPerformed(GuiButton button) {
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (items.isEmpty() || busy()) return;
        switch (key) {
            case Keyboard.KEY_UP:
                moveFocus(-1);
                return;
            case Keyboard.KEY_DOWN:
                moveFocus(1);
                return;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
            case Keyboard.KEY_SPACE:
                if (focus >= 0) activate(focus);
                return;
            default:
        }
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.key != key) continue;
            if (item.activates) activate(i);
            else {
                focus = i;
                UiSound.SELECT.play();
            }
            return;
        }
    }

    private void moveFocus(int direction) {
        int n = items.size();
        if (focus < 0) {
            int hovered = lastHovered == null ? -1 : items.indexOf(lastHovered);
            focus = hovered >= 0 ? (hovered + direction + n) % n : (direction > 0 ? 0 : n - 1);
        } else {
            focus = (focus + direction + n) % n;
        }
        UiSound.SCROLL_TICK.play();
    }
}
