package myau.ui.glass;

import me.ksyz.accountmanager.gui.GuiAccountManager;
import myau.Myau;
import myau.ui.GlassTextField;
import myau.ui.GlassTheme;
import net.minecraft.client.AnvilConverterException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.GuiRenameWorld;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.SaveFormatComparator;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The singleplayer world list rebuilt in glass, in the same frame as {@link GlassMultiplayer}: a
 * top bar with search and the account, the worlds as cards (a tile of their own colour, name,
 * folder, when last played, game mode), a panel with the selected world's details and actions,
 * and "create" / "back" at the bottom.
 * <p>
 * It extends {@link GuiSelectWorld} because Forge hands the world list to the old-save warning
 * and back, but it never runs the vanilla screen's own code (whose list would not exist here).
 * Playing, renaming, re-creating and deleting do exactly what vanilla does; deleting asks for a
 * second click instead of a separate screen. Sizes on disk are measured on a background thread.
 */
public class GlassSingleplayer extends GuiSelectWorld implements myau.util.ime.TextInputScreen {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final float MARGIN = 14, PAD = 14, TOP_H = 26, BOTTOM_H = 26;
    private static final float CARD_H = 46, CARD_GAP = 6, STEP = CARD_H + CARD_GAP, R_CARD = 9, TILE = 34;
    private static final long DOUBLE_CLICK_MS = 320, DELETE_ARM_MS = 3000, DELETE_GUARD_MS = 250;
    private static final ExecutorService SIZES = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BetterMyau World Sizes");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /** Bumped whenever the list is rebuilt or the screen closes: older size walks stop. */
    private static volatile int generation;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final GuiScreen parent;
    private final DesignKit kit = DesignKit.zoom(760, 420);
    private final List<WorldSlot> worlds = new ArrayList<>();
    private boolean initialized, reload, loading;
    private String error;

    private WorldSlot selected;
    private final Anim.Smooth detailFade = new Anim.Smooth(1, 14);
    private final Anim.Smooth searchFocus = new Anim.Smooth(0, 16);
    private GlassTextField search;
    private String query = "";

    // Layout (design units)
    private float px, py, pw, ph, listX, listY, listW, listH, detailX, detailW, searchX, searchW, fieldY, chipX, chipW;

    // Cards
    private WorldSlot lastClickSlot, deleteArmed;
    private long lastClickTime, deleteArmedAt, copiedAt;
    private float scrollTarget, scroll, contentH;

    private String toast;
    private long toastAt;

    public GlassSingleplayer(GuiScreen parent) {
        super(parent);
        this.parent = parent;
    }

    // ================================================================ lifecycle

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        kit.measure();
        layout();
        createField();
        if (!initialized || reload) {
            boolean first = !initialized;
            initialized = true;
            reload = false;
            loading = false;
            loadWorlds(first);
            if (first) kit.restart();
        }
    }

    @Override
    public void addWorldSelectionButtons() {
        // No vanilla buttons: everything is drawn by this screen.
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        // Coming back (from renaming, creating, the old-save warning) reads the saves again.
        reload = true;
        generation++;
    }

    @Override
    public void updateScreen() {
        if (search != null) search.updateCursorCounter();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Typing anywhere searches, so the input method stays available on this screen. */
    @Override
    public boolean wantsTextInput() {
        return true;
    }

    private void loadWorlds(boolean first) {
        String keep = selected == null ? null : selected.folder();
        worlds.clear();
        selected = null;
        error = null;
        List<SaveFormatComparator> saves;
        try {
            saves = new ArrayList<>(mc.getSaveLoader().getSaveList());
        } catch (AnvilConverterException e) {
            Myau.LOGGER.error("Couldn't load level list", e);
            error = e.getMessage() == null ? "无法读取存档列表" : e.getMessage();
            return;
        } catch (RuntimeException e) {
            Myau.LOGGER.error("Couldn't load level list", e);
            error = "无法读取存档列表";
            return;
        }
        Collections.sort(saves);
        for (int i = 0; i < saves.size(); i++) {
            WorldSlot slot = new WorldSlot(saves.get(i), i);
            slot.appearDelay = first ? 0.08f + Math.min(i, 12) * 0.035f : 0;
            worlds.add(slot);
            if (slot.folder().equals(keep)) selected = slot;
        }
        if (selected == null && !worlds.isEmpty() && first) selected = worlds.get(0);
        measureSizes();
    }

    private void measureSizes() {
        int mine = ++generation;
        File saves = new File(mc.mcDataDir, "saves");
        for (WorldSlot slot : worlds) {
            SIZES.execute(() -> {
                if (generation != mine) return;
                slot.size = folderSize(new File(saves, slot.folder()).toPath(), mine);
            });
        }
    }

    /** Total size of the regular files under {@code root} (links are not followed). */
    private static long folderSize(Path root, int mine) {
        final long[] total = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) total[0] += attrs.size();
                    return generation == mine ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            return -2;
        }
        return total[0];
    }

    private void layout() {
        px = MARGIN;
        py = MARGIN;
        pw = kit.sw - MARGIN * 2;
        ph = kit.sh - MARGIN * 2;
        fieldY = py + 12;
        SmoothFont title = SmoothFont.bold(12);
        float titleEnd = px + PAD + 4 + title.width(I18n.format("selectWorld.title")) + 10 + DesignKit.chipWidth(countLabel()) + 16;
        chipW = DesignKit.accountChipWidth();
        chipX = px + pw - PAD - chipW;
        searchX = titleEnd;
        searchW = Math.max(140, Math.min(260, chipX - 10 - searchX));
        listY = py + 12 + TOP_H + 14;
        detailW = Math.max(230, Math.min(300, pw * 0.34f));
        listX = px + PAD;
        listW = pw - PAD * 2 - detailW - 12;
        detailX = listX + listW + 12;
        listH = py + ph - BOTTOM_H - 22 - listY;
    }

    private String countLabel() {
        return worlds.size() + " 个世界";
    }

    private void createField() {
        String text = search == null ? "" : search.getText();
        boolean focused = search != null && search.isFocused();
        search = new GlassTextField(0, fontRendererObj, Math.round(searchX + 22), Math.round(fieldY + 7), Math.round(searchW - 24), 12);
        search.setMaxStringLength(64);
        search.setText(text);
        search.setFocused(focused);
    }

    // ================================================================ frame

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float dt = kit.begin();
        layout();
        if (search.xPosition != Math.round(searchX + 22) || search.getWidth() != Math.round(searchW - 24)
                || search.yPosition != Math.round(fieldY + 7)) {
            createField();
        }
        query = search.getText().trim().toLowerCase(java.util.Locale.ROOT);

        GlassTheme.screenBackdrop(width, height);
        kit.push();
        Draw.beginBatch();
        try {
            applyWheel();
            GlassTheme.panel(px, py, pw, ph, GlassTheme.R_PANEL, 1.2f);
            ScreenTransition.focus(px * kit.zoom, py * kit.zoom, pw * kit.zoom, ph * kit.zoom, GlassTheme.R_PANEL * kit.zoom);
            drawTopBar(dt);
            Draw.rect(px + PAD, listY - 8, pw - PAD * 2, 0.6f, 0, 0x1effffff);
            drawList(dt);
            drawDetail(dt);
            drawBottomBar();
            drawToast();
        } finally {
            Draw.endBatch();
            kit.pop();
        }
    }

    // ================================================================ top bar

    private void drawTopBar(float dt) {
        float y = fieldY, h = TOP_H;
        float reveal = Anim.outCubic(kit.openTime / 0.3f);
        Draw.pushAlpha(reveal);
        SmoothFont title = SmoothFont.bold(12);
        float end = title.draw(I18n.format("selectWorld.title"), px + PAD + 4, title.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        int accent = GlassTheme.accent();
        String count = countLabel();
        SmoothFont small = SmoothFont.bold(7.5f);
        float cw = DesignKit.chipWidth(count), ch = 13, cx = end + 10, cy = y + (h - ch) / 2;
        Draw.rect(cx, cy, cw, ch, ch / 2, Draw.alpha(accent, 0.18f));
        small.drawCentered(count, cx + cw / 2, small.centerY(cy, ch) - 0.3f, GlassTheme.TEXT);

        float sf = searchFocus.update(search.isFocused(), dt);
        kit.searchBox(searchX, y, searchW, h, search, "搜索世界", sf);
        kit.accountChip("account", chipX, y, chipW, h, () -> mc.displayGuiScreen(new GuiAccountManager(this)));
        Draw.popAlpha();
    }

    // ================================================================ list

    private List<WorldSlot> shown() {
        if (query.isEmpty()) return worlds;
        List<WorldSlot> out = new ArrayList<>();
        for (WorldSlot slot : worlds) if (slot.matches(query)) out.add(slot);
        return out;
    }

    private void drawList(float dt) {
        List<WorldSlot> shown = shown();
        for (int i = 0; i < shown.size(); i++) {
            WorldSlot slot = shown.get(i);
            float target = i * STEP;
            if (!slot.placed) {
                slot.y.snap(target);
                slot.placed = true;
            } else {
                slot.y.update(target, dt);
            }
        }
        contentH = shown.isEmpty() ? 0 : shown.size() * STEP - CARD_GAP;
        float max = Math.max(0, contentH - listH + 4);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        scroll = Anim.reduced() ? scrollTarget : scroll + (scrollTarget - scroll) * (1 - (float) Math.exp(-16 * dt));
        if (Math.abs(scroll - scrollTarget) < 0.05f) scroll = scrollTarget;

        Draw.pushClip(listX - 6, listY - 4, listW + 12, listH + 8);
        float top = listY - scroll;
        if (error != null) {
            drawMessage(listX, top, listW, "读取存档失败", error, true);
        } else if (shown.isEmpty()) {
            boolean searching = !query.isEmpty();
            drawMessage(listX, top, listW, searching ? "没有匹配的世界" : "还没有世界",
                    searching ? "换个名字或文件夹名试试" : "点右下角「新建世界」创建第一个", false);
        }
        for (WorldSlot slot : shown) drawCard(slot, listX, top + slot.y.value, listW, dt);
        Draw.popClip();

        if (contentH > listH + 1) {
            float track = listH - 8, thumb = Math.max(24, track * listH / contentH);
            float pos = listY + 4 + (track - thumb) * (scroll / Math.max(1, max));
            Draw.rect(listX + listW + 3, pos, 2.2f, thumb, 1.1f, 0x40ffffff);
        }
    }

    private void drawMessage(float x, float y, float w, String title, String body, boolean retry) {
        float h = retry ? 96 : 74;
        GlassTheme.card(x, y, w, h, R_CARD, 0.1f);
        SmoothFont big = SmoothFont.bold(10), small = SmoothFont.regular(8.5f);
        big.drawCentered(title, x + w / 2, y + 20, retry ? 0xffff8a80 : GlassTheme.TEXT);
        small.drawCentered(small.trim(body, w - 20), x + w / 2, y + 38, GlassTheme.MUTED);
        if (retry) kit.button("retry", x + w / 2 - 40, y + 58, 80, 22, "重试", DesignKit.PLAIN, true, () -> loadWorlds(false));
    }

    private void drawCard(WorldSlot slot, float x, float y, float w, float dt) {
        if (!Draw.visible(x - 20, y - 20, w + 40, CARD_H + 40)) {
            slot.feel.update(false, false, dt);
            return;
        }
        float t = kit.openTime - slot.appearDelay;
        float appear = Anim.outCubic(t / 0.32f);
        if (appear <= 0.002f) return;
        boolean hovered = kit.over(x, y, w, CARD_H);
        slot.feel.update(hovered, hovered && Mouse.isButtonDown(0), dt);
        float sel = slot.select.update(selected == slot, dt);
        float hot = slot.feel.hot;
        int accent = GlassTheme.accent();

        float scale = (0.94f + 0.06f * Anim.outBack(t / 0.42f)) * slot.feel.scale(0.015f);
        Draw.pushAlpha(appear);
        Draw.pushTransform(x + w / 2, y + CARD_H / 2, scale, 0, (1 - appear) * 8);
        GlassTheme.card(x, y, w, CARD_H, R_CARD, 0.15f + 0.55f * hot + 0.3f * sel);
        if (sel > 0.01f) {
            Draw.rect(x, y, w, CARD_H, R_CARD, Draw.alpha(accent, 0.12f * sel));
            Draw.outline(x, y, w, CARD_H, R_CARD, 0.9f, Draw.alpha(accent, 0.85f * sel), Draw.alpha(accent, 0.35f * sel));
        }
        if (hot > 0.01f) Draw.spot(x, y, w, CARD_H, R_CARD, kit.mx, kit.my, Math.max(80, w * 0.4f), Draw.alpha(0xffffffff, 0.07f * hot));

        float tx = x + 6, ty = y + (CARD_H - TILE) / 2;
        drawTile(slot, tx, ty, TILE, 7);

        // Right: game mode
        float right = x + w - 10;
        String mode = slot.modeLabel();
        float modeW = mode.isEmpty() ? 0 : DesignKit.chipWidth(mode);
        if (!mode.isEmpty()) DesignKit.chip(mode, right - modeW, y + 8, slot.modeColor());
        SmoothFont small = SmoothFont.regular(7.5f);
        String when = DesignKit.ago(slot.save.getLastTimePlayed());
        small.drawRight(when, right, y + 28, GlassTheme.FAINT);

        float nx = tx + TILE + 9, textW = right - Math.max(modeW, small.width(when)) - 10 - nx;
        SmoothFont name = SmoothFont.bold(9.5f);
        name.draw(name.trim(slot.name(), textW), nx, y + 8, GlassTheme.TEXT);
        String second = slot.folder() + (slot.save.requiresConversion() ? "  ·  §e需要转换" : "");
        small.draw(small.trim(second, textW), nx, y + 26, GlassTheme.MUTED);

        Draw.popTransform();
        Draw.popAlpha();
        kit.hit(x, y, w, CARD_H, () -> pressCard(slot));
    }

    private void drawTile(WorldSlot slot, float x, float y, float size, float r) {
        int tint = slot.tint();
        Draw.gradientV(x, y, size, size, r, Draw.alpha(Draw.mix(tint, 0xffffffff, 0.12f), 0.62f), Draw.alpha(Draw.mix(tint, 0xff000000, 0.35f), 0.42f));
        // A little horizon: the lower third darker, like ground under sky.
        Draw.rect(x, y + size * 0.64f, size, size * 0.36f, 0, 0, r, r, Draw.alpha(0x000000, 0.18f));
        Draw.outline(x, y, size, size, r, 0.6f, 0x38ffffff, 0x10ffffff);
        SmoothFont font = SmoothFont.bold(size * 0.42f);
        font.drawShadowed(slot.initial(), x + size / 2 - font.width(slot.initial()) / 2, font.centerY(y, size) - 0.5f, GlassTheme.TEXT);
        if (slot.hardcore()) {
            float d = size * 0.18f;
            Draw.circle(x + size - d * 0.9f, y + d * 0.9f, d / 2, 0xffff5a5a);
        }
    }

    private void pressCard(WorldSlot slot) {
        long now = System.currentTimeMillis();
        boolean twice = lastClickSlot == slot && now - lastClickTime < DOUBLE_CLICK_MS;
        lastClickSlot = slot;
        lastClickTime = now;
        if (selected != slot) {
            select(slot);
            UiSound.SELECT.play();
        }
        if (twice) play(slot);
    }

    private void select(WorldSlot slot) {
        if (selected == slot) return;
        selected = slot;
        deleteArmed = null;
        detailFade.snap(0);
    }

    // ================================================================ details

    private void drawDetail(float dt) {
        float x = detailX, y = listY - 4, w = detailW, h = listH + 8;
        float reveal = Anim.outCubic((kit.openTime - 0.08f) / 0.32f);
        Draw.pushAlpha(reveal);
        Draw.pushTransform(x + w / 2, y + h / 2, 1, (1 - reveal) * 10, 0);
        GlassTheme.card(x, y, w, h, 11, 0.2f);
        WorldSlot slot = selected;
        if (slot == null) {
            SmoothFont big = SmoothFont.bold(9.5f), small = SmoothFont.regular(8);
            big.drawCentered("没有选中世界", x + w / 2, y + h / 2 - 12, GlassTheme.MUTED);
            small.drawCentered("在左侧点一下就能看到详情", x + w / 2, y + h / 2 + 2, GlassTheme.FAINT);
        } else {
            float fade = detailFade.update(1, dt);
            Draw.pushAlpha(fade);
            Draw.pushTransform(x + w / 2, y + 40, 1, 0, (1 - fade) * 6);
            drawDetailBody(slot, x, y, w, h);
            Draw.popTransform();
            Draw.popAlpha();
        }
        Draw.popTransform();
        Draw.popAlpha();
    }

    private void drawDetailBody(WorldSlot slot, float x, float y, float w, float h) {
        float pad = 14, big = 52;
        drawTile(slot, x + pad, y + pad, big, 11);

        float tx = x + pad + big + 11, tw = x + w - pad - tx;
        SmoothFont title = SmoothFont.bold(11.5f), body = SmoothFont.regular(8.5f), small = SmoothFont.regular(7.5f);
        title.draw(title.trim(slot.name(), tw), tx, y + pad + 2, GlassTheme.TEXT);

        // Folder: click to copy.
        boolean copied = System.currentTimeMillis() - copiedAt < 1300;
        String folder = copied ? "§a已复制文件夹名" : slot.folder();
        float fw = Math.min(tw, body.width(folder));
        boolean overFolder = kit.over(tx, y + pad + 16, fw + 4, 12);
        body.draw(body.trim(folder, tw), tx, y + pad + 17, overFolder ? GlassTheme.TEXT : GlassTheme.MUTED);
        if (overFolder && !copied) Draw.rect(tx, y + pad + 28.5f, fw, 0.6f, 0, 0x60ffffff);
        kit.hit(tx, y + pad + 16, fw + 4, 12, () -> {
            GuiScreen.setClipboardString(slot.folder());
            copiedAt = System.currentTimeMillis();
            UiSound.CLICK.play();
        });

        float cx = tx, cy = y + pad + 34;
        String mode = slot.modeLabel();
        if (!mode.isEmpty()) cx = DesignKit.chip(mode, cx, cy, slot.modeColor());
        if (slot.save.getCheatsEnabled()) cx = DesignKit.chip("允许作弊", cx, cy, GlassTheme.WARN);
        if (slot.save.requiresConversion()) DesignKit.chip("需要转换", cx, cy, GlassTheme.WARN);

        float sy = y + pad + big + 16;
        long last = slot.save.getLastTimePlayed();
        sy = infoRow("上次游玩", last > 0 ? DATE.format(new Date(last)) + "（" + DesignKit.ago(last) + "）" : "从未", x + pad, sy, w - pad * 2);
        sy = infoRow("占用空间", WorldSlot.bytes(slot.size), x + pad, sy, w - pad * 2);
        sy = infoRow("文件夹", "saves/" + slot.folder(), x + pad, sy, w - pad * 2);

        // Open the folder
        String open = "打开存档文件夹";
        float ow = small.width(open);
        boolean overOpen = kit.over(x + pad, sy + 2, ow + 4, 12);
        small.draw(open, x + pad, sy + 4, overOpen ? GlassTheme.accent() | 0xff000000 : GlassTheme.MUTED);
        if (overOpen) Draw.rect(x + pad, sy + 14.5f, ow, 0.6f, 0, Draw.alpha(GlassTheme.accent(), 0.8f));
        kit.hit(x + pad, sy + 2, ow + 4, 12, () -> {
            UiSound.CLICK.play();
            openFolder(new File(new File(mc.mcDataDir, "saves"), slot.folder()));
        });

        // Play, then rename / re-create, then delete.
        float bx = x + pad, bw = w - pad * 2;
        float buttonsTop = y + h - pad - 26 - 6 - 22 - 6 - 22;
        kit.button("play", bx, buttonsTop, bw, 26, "进入世界", DesignKit.ACCENT, true, () -> play(slot));
        float half = (bw - 6) / 2, ry = buttonsTop + 26 + 6;
        kit.button("rename", bx, ry, half, 22, I18n.format("selectWorld.rename"), DesignKit.PLAIN, true, () -> rename(slot));
        kit.button("recreate", bx + half + 6, ry, half, 22, I18n.format("selectWorld.recreate"), DesignKit.PLAIN, true, () -> recreate(slot));
        boolean armed = deleteArmed == slot && System.currentTimeMillis() - deleteArmedAt < DELETE_ARM_MS;
        String deleteLabel = armed ? "再点一次删除「" + SmoothFont.regular(9).trim(slot.name(), bw - 110) + "」" : I18n.format("selectWorld.delete");
        kit.button("delete", bx, ry + 22 + 6, bw, 22, deleteLabel, armed ? DesignKit.ARMED : DesignKit.DANGER, true, () -> delete(slot));
    }

    private float infoRow(String label, String value, float x, float y, float w) {
        SmoothFont key = SmoothFont.bold(7.5f), val = SmoothFont.regular(8);
        key.draw(label, x, y + 1, GlassTheme.FAINT);
        float kw = 52;
        val.draw(val.trim(value, w - kw), x + kw, y, GlassTheme.TEXT);
        return y + 14;
    }

    // ================================================================ bottom bar

    private void drawBottomBar() {
        float y = py + ph - BOTTOM_H - 10;
        float reveal = Anim.outCubic((kit.openTime - 0.12f) / 0.3f);
        Draw.pushAlpha(reveal);
        SmoothFont hint = SmoothFont.regular(7.5f);
        hint.draw("双击进入  ·  F2 重命名  ·  Delete 删除  ·  Ctrl+N 新建  ·  直接打字搜索", px + PAD + 4, hint.centerY(y, BOTTOM_H) - 0.5f, GlassTheme.FAINT);
        float right = px + pw - PAD;
        float back = 78, create = 108;
        kit.button("back", right - back, y, back, BOTTOM_H, I18n.format("gui.back"), DesignKit.PLAIN, true, this::goBack);
        kit.button("create", right - back - 6 - create, y, create, BOTTOM_H, "+ 新建世界", DesignKit.ACCENT, true, this::create);
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
        GlassTheme.toast(toast, px + pw / 2, py + ph - BOTTOM_H - 46 + (1 - a) * 6);
        Draw.popAlpha();
    }

    // ================================================================ actions

    /** Exactly vanilla's "play selected world". */
    private void play(WorldSlot slot) {
        if (slot == null || loading) return;
        loading = true;
        mc.displayGuiScreen(null);
        String folder = slot.folder(), name = slot.name();
        if (mc.getSaveLoader().canLoadWorld(folder)) {
            FMLClientHandler.instance().tryLoadExistingWorld(this, folder, name);
        } else {
            loading = false;
        }
    }

    private void rename(WorldSlot slot) {
        if (slot != null) mc.displayGuiScreen(new GuiRenameWorld(this, slot.folder()));
    }

    private void recreate(WorldSlot slot) {
        if (slot == null) return;
        try {
            GuiCreateWorld create = new GuiCreateWorld(this);
            ISaveHandler handler = mc.getSaveLoader().getSaveLoader(slot.folder(), false);
            WorldInfo info = handler.loadWorldInfo();
            handler.flush();
            if (info == null) {
                showToast("读不到这个世界的设置");
                return;
            }
            create.recreateFromExistingWorld(info);
            mc.displayGuiScreen(create);
        } catch (RuntimeException e) {
            Myau.LOGGER.error("Couldn't re-create world {}", slot.folder(), e);
            showToast("读不到这个世界的设置");
        }
    }

    private void create() {
        mc.displayGuiScreen(new GuiCreateWorld(this));
    }

    /** First click arms, a second one within three seconds (not a double click) deletes. */
    private void delete(WorldSlot slot) {
        if (slot == null) return;
        long now = System.currentTimeMillis();
        if (deleteArmed != slot || now - deleteArmedAt >= DELETE_ARM_MS) {
            deleteArmed = slot;
            deleteArmedAt = now;
            return;
        }
        if (now - deleteArmedAt < DELETE_GUARD_MS) return;
        deleteArmed = null;
        generation++;
        ISaveFormat format = mc.getSaveLoader();
        format.flushCache();
        boolean deleted = format.deleteWorldDirectory(slot.folder());
        UiSound.DELETE.play();
        showToast(deleted ? "已删除「" + slot.name() + "」" : "没能完全删除，可能有文件被占用");
        int index = shown().indexOf(slot);
        selected = null;
        loadWorlds(false);
        List<WorldSlot> shown = shown();
        if (!shown.isEmpty()) select(shown.get(Math.max(0, Math.min(shown.size() - 1, index))));
    }

    private void goBack() {
        mc.displayGuiScreen(parent);
    }

    private void openFolder(File dir) {
        Thread thread = new Thread(() -> {
            try {
                Desktop.getDesktop().open(dir);
            } catch (Exception e) {
                Myau.LOGGER.warn("[Glass] cannot open {}", dir, e);
            }
        }, "BetterMyau-OpenFolder");
        thread.setDaemon(true);
        thread.start();
        showToast("已打开存档文件夹");
    }

    // Vanilla entry points other code may call on a world list.
    @Override
    public void func_146615_e(int index) {
        if (index >= 0 && index < worlds.size()) play(worlds.get(index));
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        mc.displayGuiScreen(this);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
    }

    // ================================================================ input

    @Override
    public void handleMouseInput() {
        int button = Mouse.getEventButton();
        if (Mouse.getEventButtonState()) press(kit.eventX(), kit.eventY(), button);
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) kit.addWheel(wheel);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        press(kit.fromGui(mouseX), kit.fromGui(mouseY), button);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
    }

    private void press(float x, float y, int button) {
        if (!kit.interactive()) return;
        boolean inSearch = DesignKit.inside(x, y, searchX, fieldY, searchW, TOP_H);
        if (button == 0) {
            DesignKit.focusField(search, inSearch, x);
            if (inSearch) return;
            if (kit.click(x, y)) return;
            if (DesignKit.inside(x, y, listX, listY, listW, listH)) select(null);
        }
    }

    private void applyWheel() {
        float notches = kit.takeWheel();
        if (notches == 0 || !kit.interactive()) return;
        if (DesignKit.inside(kit.mx, kit.my, listX - 6, listY - 4, listW + 12, listH + 8)) scrollTarget -= notches * STEP * 1.2f;
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (search.isFocused()) {
            if (key == Keyboard.KEY_ESCAPE) {
                if (search.getText().isEmpty()) search.setFocused(false);
                else search.setText("");
                return;
            }
            if (key != Keyboard.KEY_UP && key != Keyboard.KEY_DOWN && key != Keyboard.KEY_RETURN && key != Keyboard.KEY_NUMPADENTER) {
                search.textboxKeyTyped(typed, key);
                return;
            }
        }
        switch (key) {
            case Keyboard.KEY_ESCAPE:
                if (!search.getText().isEmpty()) search.setText("");
                else goBack();
                return;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER: {
                List<WorldSlot> shown = shown();
                if (selected == null && shown.size() == 1) select(shown.get(0));
                if (selected != null) play(selected);
                return;
            }
            case Keyboard.KEY_F2:
                rename(selected);
                return;
            case Keyboard.KEY_F5:
                loadWorlds(false);
                showToast("已刷新");
                return;
            case Keyboard.KEY_DELETE:
                delete(selected);
                return;
            case Keyboard.KEY_UP:
            case Keyboard.KEY_DOWN:
                arrow(key == Keyboard.KEY_UP ? -1 : 1);
                return;
            case Keyboard.KEY_TAB:
                search.setFocused(true);
                return;
            default:
                break;
        }
        if (isCtrlKeyDown() && key == Keyboard.KEY_F) {
            search.setFocused(true);
            search.setCursorPositionEnd();
            return;
        }
        if (isCtrlKeyDown() && key == Keyboard.KEY_N) {
            create();
            return;
        }
        if (!isCtrlKeyDown() && ChatAllowedCharacters.isAllowedCharacter(typed) && typed != ' ') {
            search.setFocused(true);
            search.setCursorPositionEnd();
            search.textboxKeyTyped(typed, key);
        }
    }

    private void arrow(int direction) {
        List<WorldSlot> shown = shown();
        if (shown.isEmpty()) return;
        int index = selected == null ? (direction > 0 ? -1 : shown.size()) : shown.indexOf(selected);
        int next = Math.max(0, Math.min(shown.size() - 1, index + direction));
        select(shown.get(next));
        UiSound.SELECT.play();
        float top = next * STEP;
        if (top < scrollTarget) scrollTarget = top - 4;
        else if (top + CARD_H > scrollTarget + listH) scrollTarget = top + CARD_H - listH + 4;
    }
}
