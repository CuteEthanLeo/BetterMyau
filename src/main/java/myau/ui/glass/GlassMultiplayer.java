package myau.ui.glass;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import me.ksyz.accountmanager.gui.GuiAccountManager;
import myau.Myau;
import myau.ui.GlassTextField;
import myau.ui.GlassTheme;
import myau.via.ViaUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenAddServer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.network.LanServerDetector;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The multiplayer screen rebuilt in glass: a top bar with search, quick connect and the
 * account, the server list as cards that can be dragged into a new order, a panel with the
 * selected server's details, and the usual add / refresh / back actions.
 * <p>
 * It extends {@link GuiMultiplayer} so everything that checks for the multiplayer screen
 * (the connecting screen's "back", the disconnect screen, other mods) still sees one, but it
 * never runs the vanilla screen's own code: every method that would touch the vanilla list is
 * replaced. Saving, pinging, LAN discovery and connecting work exactly as in vanilla.
 * <p>
 * Everything is laid out in "design units", {@link #ui} pixels each, chosen so the screen
 * always has room (at least 760 x 420) whatever the GUI scale is.
 */
public class GlassMultiplayer extends GuiMultiplayer implements myau.util.ime.TextInputScreen {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final float MARGIN = 14, PAD = 14, TOP_H = 26, BOTTOM_H = 26;
    private static final float CARD_H = 46, CARD_GAP = 6, STEP = CARD_H + CARD_GAP, R_CARD = 9, ICON = 34;
    private static final float SECTION_H = 24, MIN_W = 760, MIN_H = 420;
    private static final long DOUBLE_CLICK_MS = 320, DELETE_ARM_MS = 3000;

    private final GuiScreen parent;
    private ServerList servers;
    private final List<ServerSlot> saved = new ArrayList<>(), lan = new ArrayList<>();
    private LanServerDetector.LanServerList lanList;
    private LanServerDetector.ThreadLanServerFind lanFinder;
    private boolean initialized;

    private ServerSlot selected;
    private boolean adding, editing;
    private ServerData pending;
    private ServerSlot editTarget;

    // Frame
    private float ui = 2, zoom = 1, sw = MIN_W, sh = MIN_H, mx, my, openTime;
    private final Anim.Smooth detailFade = new Anim.Smooth(1, 14);
    private final Anim.Smooth historyShow = new Anim.Smooth(0, 18);
    private final Anim.Smooth searchFocus = new Anim.Smooth(0, 16), quickFocus = new Anim.Smooth(0, 16);
    private final Map<String, Interaction> feels = new HashMap<>();
    private String query = "";

    // Layout (design units), recomputed every frame
    private float px, py, pw, ph, listX, listY, listW, listH, detailX, detailW;
    private float searchX, searchW, quickX, quickW, fieldY, chipX, chipW, viaX, viaW;
    /** The version settings this list was last pinged with (see ViaUi.revision). */
    private int viaRevision = ViaUi.revision();
    private final float[] dropdown = new float[4];

    // Input
    private static final class Hit {
        float x0, y0, x1, y1;
        Runnable action;
        boolean keepsQuick;
    }

    private final List<Hit> hits = new ArrayList<>();
    private int hitCount;
    private final float[] clipped = new float[4];
    private GlassTextField search, quick;
    private int wheel;
    private int eventButton = -1;

    // Cards
    private ServerSlot pressSlot, lastClickSlot, dragSlot;
    private long lastClickTime;
    private float pressX, pressY, grabOffset, dragY;
    private int dragTo;
    private float scrollTarget, scroll, contentH;
    private float playersScroll, playersMax;
    private ServerSlot deleteArmed;
    private long deleteArmedAt, copiedAt;
    private final boolean[] iconChanged = new boolean[1];

    public GlassMultiplayer(GuiScreen parent) {
        super(parent);
        this.parent = parent;
    }

    // ================================================================ lifecycle

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        measure();
        layout();
        createFields();
        if (!initialized) {
            initialized = true;
            servers = new ServerList(mc);
            servers.loadServerList();
            rebuildSaved();
            openTime = 0;
            scroll = scrollTarget = 0;
        } else {
            // Leaving for the add / edit screen cut off the pings that were still running.
            for (ServerSlot slot : saved) {
                if (slot.pinging()) slot.reping();
            }
        }
        if (lanList == null) lanList = new LanServerDetector.LanServerList();
        if (lanFinder == null) {
            try {
                lanFinder = new LanServerDetector.ThreadLanServerFind(lanList);
                lanFinder.start();
            } catch (Exception e) {
                Myau.LOGGER.warn("Unable to start LAN server detection: {}", e.getMessage());
            }
        }
    }

    @Override
    public void createButtons() {
        // No vanilla buttons: everything is drawn by this screen.
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (lanFinder != null) {
            lanFinder.interrupt();
            lanFinder = null;
        }
        getOldServerPinger().clearPendingNetworks();
        pressSlot = null;
        dragSlot = null;
    }

    @Override
    public void updateScreen() {
        if (search != null) search.updateCursorCounter();
        if (quick != null) quick.updateCursorCounter();
        if (lanList != null && lanList.getWasUpdated()) {
            List<LanServerDetector.LanServer> found = lanList.getLanServers();
            lanList.setWasNotUpdated();
            List<ServerSlot> next = new ArrayList<>();
            for (LanServerDetector.LanServer server : found) {
                ServerSlot slot = null;
                for (ServerSlot old : lan) {
                    if (old.lan.getServerIpPort().equals(server.getServerIpPort())) slot = old;
                }
                if (slot == null) {
                    slot = new ServerSlot(server);
                    slot.appearDelay = openTime;
                }
                next.add(slot);
            }
            if (selected != null && selected.isLan() && !next.contains(selected)) select(null);
            lan.clear();
            lan.addAll(next);
        }
        getOldServerPinger().pingPendingNetworks();
    }

    private void rebuildSaved() {
        List<ServerSlot> next = new ArrayList<>();
        for (int i = 0; i < servers.countServers(); i++) {
            ServerSlot slot = new ServerSlot(servers.getServerData(i));
            slot.appearDelay = 0.08f + Math.min(i, 12) * 0.035f;
            next.add(slot);
        }
        saved.clear();
        saved.addAll(next);
    }

    /** Pixels per design unit: the GUI scale, or less when that leaves too little room. */
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
        px = MARGIN;
        py = MARGIN;
        pw = sw - MARGIN * 2;
        ph = sh - MARGIN * 2;
        fieldY = py + 12;
        SmoothFont title = SmoothFont.bold(12);
        float titleEnd = px + PAD + 4 + title.width(I18n.format("multiplayer.title")) + 10 + countWidth() + 16;
        chipW = Math.min(170, SmoothFont.regular(8.5f).width(mc.getSession().getUsername()) + 36);
        chipX = px + pw - PAD - chipW;
        viaW = Math.max(74, Math.min(130, SmoothFont.bold(8.5f).width(ViaUi.globalLabel()) + SmoothFont.regular(8).width("版本") + 30));
        viaX = chipX - 8 - viaW;
        quickW = Math.max(150, Math.min(240, (viaX - 10 - titleEnd) * 0.5f));
        quickX = viaX - 10 - quickW;
        searchX = titleEnd;
        searchW = Math.max(120, Math.min(240, quickX - 10 - searchX));
        listY = py + 12 + TOP_H + 14;
        detailW = Math.max(230, Math.min(300, pw * 0.34f));
        listX = px + PAD;
        listW = pw - PAD * 2 - detailW - 12;
        detailX = listX + listW + 12;
        listH = py + ph - BOTTOM_H - 22 - listY;
    }

    private float countWidth() {
        return SmoothFont.bold(7.5f).width(countLabel()) + 12;
    }

    private String countLabel() {
        int n = saved.size();
        return n + (n == 1 ? " server" : " servers");
    }

    private void createFields() {
        String searchText = search == null ? "" : search.getText();
        String quickText = quick == null ? "" : quick.getText();
        boolean searchFocused = search != null && search.isFocused(), quickFocused = quick != null && quick.isFocused();
        search = new GlassTextField(0, fontRendererObj, Math.round(searchX + 22), Math.round(fieldY + 7), Math.round(searchW - 24), 12);
        search.setMaxStringLength(64);
        search.setText(searchText);
        search.setFocused(searchFocused);
        quick = new GlassTextField(1, fontRendererObj, Math.round(quickX + 22), Math.round(fieldY + 7), Math.round(quickW - 24), 12);
        quick.setMaxStringLength(128);
        quick.setText(quickText);
        quick.setFocused(quickFocused);
    }

    // ================================================================ frame

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float dt = Anim.dt();
        openTime += dt;
        measure();
        layout();
        // The fields follow the layout (the server count in the title moves the search box).
        if (search.xPosition != Math.round(searchX + 22) || search.getWidth() != Math.round(searchW - 24)
                || quick.xPosition != Math.round(quickX + 22) || quick.getWidth() != Math.round(quickW - 24)
                || search.yPosition != Math.round(fieldY + 7)) {
            createFields();
        }
        mx = Mouse.getX() / ui;
        my = (mc.displayHeight - Mouse.getY() - 1) / ui;
        query = search.getText().trim().toLowerCase(Locale.ROOT);
        if (viaRevision != ViaUi.revision()) {
            // A version setting changed: the old pings were made with the old setting.
            viaRevision = ViaUi.revision();
            for (ServerSlot slot : saved) slot.reping();
        }

        GlassTheme.screenBackdrop(width, height);
        Draw.pushTransform(0, 0, zoom, 0, 0);
        Draw.beginBatch();
        try {
            hitCount = 0;
            applyWheel();
            updateDrag(dt);
            drawFrame(dt);
        } finally {
            Draw.endBatch();
            Draw.popTransform();
        }
        if (iconChanged[0]) {
            iconChanged[0] = false;
            servers.saveServerList();
        }
    }

    private boolean interactive() {
        return openTime > 0.12f;
    }

    private void drawFrame(float dt) {
        GlassTheme.panel(px, py, pw, ph, GlassTheme.R_PANEL, 1.2f);
        ScreenTransition.focus(px * zoom, py * zoom, pw * zoom, ph * zoom, GlassTheme.R_PANEL * zoom);

        drawTopBar(dt);
        Draw.rect(px + PAD, listY - 8, pw - PAD * 2, 0.6f, 0, 0x1effffff);
        drawList(dt);
        drawDetail(dt);
        drawBottomBar(dt);
        drawHistory(dt);
    }

    // ================================================================ top bar

    private void drawTopBar(float dt) {
        float y = fieldY, h = TOP_H;
        float reveal = Anim.outCubic(openTime / 0.3f);
        Draw.pushAlpha(reveal);
        SmoothFont title = SmoothFont.bold(12);
        float end = title.draw(I18n.format("multiplayer.title"), px + PAD + 4, title.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        // How many servers, as a small capsule after the title.
        SmoothFont small = SmoothFont.bold(7.5f);
        float cw = countWidth(), ch = 13, cx = end + 10, cy = y + (h - ch) / 2;
        int accent = GlassTheme.accent();
        Draw.rect(cx, cy, cw, ch, ch / 2, Draw.alpha(accent, 0.18f));
        small.drawCentered(countLabel(), cx + cw / 2, small.centerY(cy, ch) - 0.3f, GlassTheme.TEXT);

        // Search
        float sf = searchFocus.update(search.isFocused(), dt);
        GlassTheme.field(searchX, y, searchW, h, h / 2, sf);
        drawMagnifier(searchX + 12, y + h / 2, Draw.mix(GlassTheme.FAINT, GlassTheme.TEXT, sf));
        hit(searchX, y, searchW, h, null, false);
        if (search.getText().isEmpty() && !search.isFocused()) {
            SmoothFont font = SmoothFont.regular(9);
            font.draw("Search servers", searchX + 22, font.centerY(y, h) - 0.5f, GlassTheme.FAINT);
        }
        search.drawTextBox();

        // Quick connect
        float qf = quickFocus.update(quick.isFocused(), dt);
        GlassTheme.field(quickX, y, quickW, h, h / 2, qf);
        Draw.chevron(quickX + 12, y + h / 2, 3.2f, 0, 1.4f, Draw.mix(GlassTheme.FAINT, accent, 0.4f + 0.6f * qf));
        hit(quickX, y, quickW, h, null, true);
        if (quick.getText().isEmpty() && !quick.isFocused()) {
            SmoothFont font = SmoothFont.regular(9);
            font.draw("Direct connect", quickX + 22, font.centerY(y, h) - 0.5f, GlassTheme.FAINT);
        }
        quick.drawTextBox();
        if (!quick.getText().trim().isEmpty()) {
            SmoothFont font = SmoothFont.regular(7.5f);
            font.drawRight("Enter", quickX + quickW - 10, font.centerY(y, h) - 0.5f, Draw.alpha(accent, 0.9f));
        }

        drawVersionChip(y, h, dt, accent);

        // Account
        Interaction feel = feel("account");
        boolean over = over(chipX, y, chipW, h);
        feel.update(over, over && Mouse.isButtonDown(0), dt);
        Draw.pushTransform(chipX + chipW / 2, y + h / 2, feel.scale(0.04f), 0, 0);
        GlassTheme.card(chipX, y, chipW, h, h / 2, 0.3f + 0.7f * feel.hot);
        if (feel.hot > 0.01f) Draw.outline(chipX, y, chipW, h, h / 2, 0.8f, Draw.alpha(accent, 0.7f * feel.hot), Draw.alpha(accent, 0.3f * feel.hot));
        int skin = Draw.textureId(accountSkin());
        float head = 15, hx = chipX + 5.5f, hy = y + (h - head) / 2;
        if (skin > 0) {
            Draw.image(hx, hy, head, head, head / 2, skin, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f);
            Draw.image(hx, hy, head, head, head / 2, skin, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f);
        }
        SmoothFont name = SmoothFont.regular(8.5f);
        name.draw(name.trim(mc.getSession().getUsername(), chipW - head - 18), hx + head + 6, name.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        Draw.popTransform();
        hit(chipX, y, chipW, h, () -> {
            feel.pulse();
            UiSound.CLICK.play();
            mc.displayGuiScreen(new GuiAccountManager(this));
        }, false);
        Draw.popAlpha();
    }

    /** Cross-version: the version used for all servers; opens the picker. */
    private void drawVersionChip(float y, float h, float dt, int accent) {
        Interaction feel = feel("via");
        boolean over = over(viaX, y, viaW, h);
        feel.update(over, over && Mouse.isButtonDown(0), dt);
        Draw.pushTransform(viaX + viaW / 2, y + h / 2, feel.scale(0.04f), 0, 0);
        GlassTheme.card(viaX, y, viaW, h, h / 2, 0.3f + 0.7f * feel.hot);
        if (feel.hot > 0.01f) Draw.outline(viaX, y, viaW, h, h / 2, 0.8f, Draw.alpha(accent, 0.7f * feel.hot), Draw.alpha(accent, 0.3f * feel.hot));
        String status = ViaUi.status();
        Draw.circle(viaX + 11, y + h / 2, 2.4f, status == null ? accent : GlassTheme.WARN);
        SmoothFont label = SmoothFont.regular(8), value = SmoothFont.bold(8.5f);
        float lx = viaX + 19;
        float end = label.draw("版本", lx, label.centerY(y, h) - 0.5f, GlassTheme.FAINT);
        value.draw(value.trim(ViaUi.globalLabel(), viaX + viaW - 10 - (end + 5)), end + 5, value.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        Draw.popTransform();
        hit(viaX, y, viaW, h, () -> {
            feel.pulse();
            UiSound.CLICK.play();
            mc.displayGuiScreen(new GlassVersionPicker(this));
        }, false);
    }

    private void drawMagnifier(float cx, float cy, int color) {
        Draw.outline(cx - 4, cy - 4, 6.4f, 6.4f, 3.2f, 1.2f, color, color);
        Draw.polyline(1.3f, color, cx + 1.7f, cy + 1.7f, cx + 4.2f, cy + 4.2f);
    }

    // ================================================================ server list

    /** What the list shows, in order: the saved servers matching the search, then LAN worlds. */
    private List<ServerSlot> shownSaved() {
        if (query.isEmpty()) return saved;
        List<ServerSlot> out = new ArrayList<>();
        for (ServerSlot slot : saved) if (slot.matches(query)) out.add(slot);
        return out;
    }

    private List<ServerSlot> shownLan() {
        if (query.isEmpty()) return lan;
        List<ServerSlot> out = new ArrayList<>();
        for (ServerSlot slot : lan) if (slot.matches(query)) out.add(slot);
        return out;
    }

    private void drawList(float dt) {
        List<ServerSlot> savedShown = shownSaved(), lanShown = shownLan();
        // While dragging, the dragged card's place moves through the list.
        List<ServerSlot> order = savedShown;
        if (dragSlot != null) {
            order = new ArrayList<>(saved);
            order.remove(dragSlot);
            order.add(Math.max(0, Math.min(order.size(), dragTo)), dragSlot);
        }

        float y = 0;
        float emptyH = 0;
        if (order.isEmpty()) {
            emptyH = 74;
            y = emptyH + 8;
        } else {
            for (int i = 0; i < order.size(); i++) placeCard(order.get(i), i * STEP, dt);
            y = order.size() * STEP;
        }
        float lanTop = y;
        y += SECTION_H;
        for (int i = 0; i < lanShown.size(); i++) placeCard(lanShown.get(i), y + i * STEP, dt);
        y += lanShown.size() * STEP;
        contentH = y;

        float max = Math.max(0, contentH - listH + 4);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        scroll = Anim.reduced() ? scrollTarget : scroll + (scrollTarget - scroll) * (1 - (float) Math.exp(-16 * dt));
        if (Math.abs(scroll - scrollTarget) < 0.05f) scroll = scrollTarget;

        Draw.pushClip(listX - 6, listY - 4, listW + 12, listH + 8);
        float top = listY - scroll;
        if (order.isEmpty()) {
            drawEmpty(listX, top, listW, emptyH);
        }
        for (ServerSlot slot : order) {
            if (slot != dragSlot) drawCard(slot, listX, top + slot.y.value, listW, dt);
        }
        drawSection(listX, top + lanTop, listW);
        for (ServerSlot slot : lanShown) drawCard(slot, listX, top + slot.y.value, listW, dt);
        Draw.popClip();

        // The dragged card floats over everything, unclipped at the edges so it can be pushed past them.
        if (dragSlot != null) {
            float cy = Math.max(listY - 10, Math.min(listY + listH - CARD_H + 10, dragY));
            drawCard(dragSlot, listX, cy, listW, dt);
        }

        // Scrollbar
        if (contentH > listH + 1) {
            float track = listH - 8, thumb = Math.max(24, track * listH / contentH);
            float pos = listY + 4 + (track - thumb) * (scroll / Math.max(1, max));
            Draw.rect(listX + listW + 3, pos, 2.2f, thumb, 1.1f, 0x40ffffff);
        }
    }

    private void placeCard(ServerSlot slot, float target, float dt) {
        if (!slot.placed) {
            slot.y.snap(target);
            slot.placed = true;
        } else if (slot != dragSlot) {
            slot.y.update(target, dt);
        }
    }

    private void drawEmpty(float x, float y, float w, float h) {
        GlassTheme.card(x, y, w, h, R_CARD, 0.1f);
        SmoothFont big = SmoothFont.bold(10), small = SmoothFont.regular(8.5f);
        boolean searching = !query.isEmpty();
        big.drawCentered(searching ? "No servers match" : "No servers yet", x + w / 2, y + 20, GlassTheme.TEXT);
        small.drawCentered(searching ? "Try another name, address or MOTD" : "Add one below, or type an address into Direct connect",
                x + w / 2, y + 38, GlassTheme.MUTED);
    }

    private void drawSection(float x, float y, float w) {
        SmoothFont font = SmoothFont.bold(8);
        float cy = y + SECTION_H / 2 + 1;
        float end = font.draw(I18n.format("lanServer.title"), x + 4, font.centerY(y + 1, SECTION_H) - 0.5f, GlassTheme.MUTED);
        // Three dots breathing one after another while LAN worlds are searched for.
        float t = openTime * 2.4f;
        for (int i = 0; i < 3; i++) {
            float k = 0.5f + 0.5f * (float) Math.sin(t * Math.PI - i * 0.9f);
            Draw.circle(end + 10 + i * 6, cy, 1.4f, Draw.alpha(0xffffffff, 0.2f + 0.55f * k));
        }
        float lineX = end + 32;
        if (lineX < x + w - 4) Draw.rect(lineX, cy, x + w - lineX, 0.6f, 0, 0x1effffff);
        if (lan.isEmpty()) {
            SmoothFont small = SmoothFont.regular(7.5f);
            small.drawRight(I18n.format("lanServer.scanning"), x + w, small.centerY(y + 1, SECTION_H) - 0.5f, GlassTheme.FAINT);
        }
    }

    private void drawCard(ServerSlot slot, float x, float y, float w, float dt) {
        if (!Draw.visible(x - 20, y - 20, w + 40, CARD_H + 40) && slot != dragSlot) return;
        slot.pingIfNeeded(getOldServerPinger());

        float t = openTime - slot.appearDelay;
        float appear = Anim.outCubic(t / 0.32f);
        if (appear <= 0.002f) return;
        boolean dragged = slot == dragSlot;
        boolean hovered = dragSlot == null && over(x, y, w, CARD_H);
        slot.feel.update(hovered, hovered && Mouse.isButtonDown(0), dt);
        float sel = slot.select.update(selected == slot, dt);
        float lift = slot.lift.update(dragged, dt);
        float hot = slot.feel.hot;
        int accent = GlassTheme.accent();

        float scale = (0.94f + 0.06f * Anim.outBack(t / 0.42f)) * slot.feel.scale(0.015f) * (1 + 0.025f * lift);
        Draw.pushAlpha(appear);
        Draw.pushTransform(x + w / 2, y + CARD_H / 2, scale, 0, (1 - appear) * 8);

        if (lift > 0.01f) {
            Draw.shadow(x, y + 5, w, CARD_H, R_CARD, 18, Draw.alpha(0, 0.5f * lift));
            Draw.rect(x, y, w, CARD_H, R_CARD, Draw.alpha(0xff1c2028, 0.82f * lift));
        }
        GlassTheme.card(x, y, w, CARD_H, R_CARD, 0.15f + 0.55f * hot + 0.3f * sel);
        if (sel > 0.01f) {
            Draw.rect(x, y, w, CARD_H, R_CARD, Draw.alpha(accent, 0.12f * sel));
            Draw.outline(x, y, w, CARD_H, R_CARD, 0.9f, Draw.alpha(accent, 0.85f * sel), Draw.alpha(accent, 0.35f * sel));
        }
        if (hot > 0.01f) Draw.spot(x, y, w, CARD_H, R_CARD, mx, my, Math.max(80, w * 0.4f), Draw.alpha(0xffffffff, 0.07f * hot));

        // Icon
        float ix = x + 6, iy = y + (CARD_H - ICON) / 2;
        drawIcon(slot, ix, iy, ICON, 7);

        // Right column: signal and latency, then players or the version
        float right = x + w - 10;
        float barsW = 14;
        drawSignal(slot, right - barsW, y + 8, barsW, 9);
        SmoothFont small = SmoothFont.regular(7.5f);
        String latency = latencyLabel(slot);
        float latencyW = latency.isEmpty() ? 0 : small.width(latency) + 5;
        if (!latency.isEmpty()) small.drawRight(latency, right - barsW - 5, y + 7.2f, latencyColor(slot));
        int[] players = slot.players();
        // Servers write the slash in dark grey, which disappears on glass: draw the numbers ourselves.
        String second = slot.isLan() ? "LAN" : slot.incompatible() ? "§c" + ServerSlot.strip(slot.version())
                : players != null ? players[0] + "/" + players[1] : slot.population();
        float secondW = small.width(second);
        if (!second.isEmpty()) small.drawRight(second, right, y + 21, GlassTheme.MUTED);
        float rightW = Math.max(barsW + latencyW + 5, secondW + 4);

        // Name and MOTD
        float tx = ix + ICON + 9, textW = right - rightW - 8 - tx;
        SmoothFont name = SmoothFont.bold(9.5f);
        name.draw(name.trim(slot.name(), textW), tx, y + 6.5f, GlassTheme.TEXT);
        if (slot.pinging()) {
            // Placeholder lines with a sheen running over them while the server answers.
            float sweep = (openTime * 0.9f) % 1.4f;
            Draw.rect(tx, y + 22, textW * 0.62f, 5, 2.5f, 0x16ffffff);
            Draw.rect(tx, y + 32, textW * 0.38f, 5, 2.5f, 0x10ffffff);
            Draw.sheen(tx, y + 22, textW * 0.62f, 15, 2.5f, sweep, 0x18ffffff);
        } else if (slot.failed()) {
            SmoothFont motd = SmoothFont.regular(8);
            motd.draw(motd.trim(ServerSlot.strip(slot.motd()), textW), tx, y + 19.5f, 0xffff7a70);
        } else {
            SmoothFont motd = SmoothFont.regular(8);
            String[] lines = slot.motd().split("\n", 3);
            String carry = "";
            for (int i = 0; i < Math.min(2, lines.length); i++) {
                String line = carry + lines[i];
                carry = net.minecraft.client.gui.FontRenderer.getFormatFromString(line);
                motd.draw(motd.trim(line, w - (tx - x) - 10 - (i == 0 ? Math.max(secondW, 0) + 8 : 0)), tx, y + 19.5f + i * 10.5f, GlassTheme.MUTED);
            }
        }

        Draw.popTransform();
        Draw.popAlpha();
        if (!dragged) hit(x, y, w, CARD_H, () -> pressCard(slot), false);
    }

    private void drawIcon(ServerSlot slot, float x, float y, float size, float r) {
        int texture = slot.icon(iconChanged);
        if (texture > 0) {
            Draw.image(x, y, size, size, r, texture, 0, 0, 1, 1);
            Draw.outline(x, y, size, size, r, 0.6f, 0x24ffffff, 0x10ffffff);
            return;
        }
        // No icon: a tile in the accent colour with the first letter of the name.
        int accent = GlassTheme.accent();
        Draw.gradientV(x, y, size, size, r, Draw.alpha(Draw.mix(accent, 0xffffffff, 0.15f), 0.5f), Draw.alpha(accent, 0.22f));
        Draw.outline(x, y, size, size, r, 0.6f, 0x30ffffff, 0x10ffffff);
        String letter = slot.isLan() ? "LAN" : initial(slot.name());
        SmoothFont font = SmoothFont.bold(slot.isLan() ? size * 0.26f : size * 0.42f);
        font.drawCentered(letter, x + size / 2, font.centerY(y, size) - 0.5f, GlassTheme.TEXT);
    }

    private static String initial(String name) {
        String plain = ServerSlot.strip(name).trim();
        return plain.isEmpty() ? "?" : plain.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private String latencyLabel(ServerSlot slot) {
        if (slot.isLan() || slot.pinging()) return "";
        if (slot.failed()) return "offline";
        return slot.data.pingToServer + " ms";
    }

    private int latencyColor(ServerSlot slot) {
        if (slot.failed()) return GlassTheme.BAD;
        return Island.pingColor((int) Math.max(1, slot.data.pingToServer));
    }

    /** Four bars: lit by latency, a running wave while pinging, a red cross when unreachable. */
    private void drawSignal(ServerSlot slot, float x, float y, float w, float h) {
        float bw = 2.6f, gap = (w - bw * 4) / 3;
        if (slot.failed()) {
            float cx = x + w / 2, cy = y + h / 2;
            Draw.polyline(1.4f, GlassTheme.BAD, cx - 3, cy - 3, cx + 3, cy + 3);
            Draw.polyline(1.4f, GlassTheme.BAD, cx - 3, cy + 3, cx + 3, cy - 3);
            return;
        }
        int lit;
        int color;
        if (slot.isLan()) {
            lit = 4;
            color = GlassTheme.OK;
        } else if (slot.pinging()) {
            lit = -1;
            color = GlassTheme.MUTED;
        } else {
            long ping = slot.data.pingToServer;
            lit = ping < 150 ? 4 : ping < 300 ? 3 : ping < 600 ? 2 : ping < 1000 ? 1 : 0;
            color = Island.pingColor((int) Math.max(1, ping));
        }
        for (int i = 0; i < 4; i++) {
            float bh = h * (0.35f + 0.65f * i / 3f);
            int c;
            if (lit < 0) {
                float wave = 0.5f + 0.5f * (float) Math.sin(openTime * 7 - i * 0.9f);
                c = Draw.alpha(0xffffffff, 0.15f + 0.45f * wave);
            } else {
                c = i < lit ? color : 0x2effffff;
            }
            Draw.rect(x + i * (bw + gap), y + h - bh, bw, bh, 1, c);
        }
    }

    // ================================================================ details

    private void drawDetail(float dt) {
        float x = detailX, y = listY - 4, w = detailW, h = listH + 8;
        float reveal = Anim.outCubic((openTime - 0.08f) / 0.32f);
        Draw.pushAlpha(reveal);
        Draw.pushTransform(x + w / 2, y + h / 2, 1, (1 - reveal) * 10, 0);
        GlassTheme.card(x, y, w, h, 11, 0.2f);
        ServerSlot slot = selected;
        if (slot == null) {
            SmoothFont big = SmoothFont.bold(9.5f), small = SmoothFont.regular(8);
            big.drawCentered("No server selected", x + w / 2, y + h / 2 - 12, GlassTheme.MUTED);
            small.drawCentered("Pick one from the list to see it here", x + w / 2, y + h / 2 + 2, GlassTheme.FAINT);
        } else {
            float fade = detailFade.update(1, dt);
            Draw.pushAlpha(fade);
            Draw.pushTransform(x + w / 2, y + 40, 1, 0, (1 - fade) * 6);
            drawDetailBody(slot, x, y, w, h, dt);
            Draw.popTransform();
            Draw.popAlpha();
        }
        Draw.popTransform();
        Draw.popAlpha();
    }

    private void drawDetailBody(ServerSlot slot, float x, float y, float w, float h, float dt) {
        float pad = 14, big = 52;
        int accent = GlassTheme.accent();
        drawIcon(slot, x + pad, y + pad, big, 11);

        float tx = x + pad + big + 11, tw = x + w - pad - tx;
        SmoothFont title = SmoothFont.bold(11.5f), body = SmoothFont.regular(8.5f), small = SmoothFont.regular(7.5f);
        title.draw(title.trim(slot.name(), tw), tx, y + pad + 2, GlassTheme.TEXT);

        // Address: click to copy.
        boolean copied = System.currentTimeMillis() - copiedAt < 1300;
        String address = copied ? "§aCopied to clipboard" : slot.address();
        float aw = Math.min(tw, body.width(address));
        boolean overAddress = over(tx, y + pad + 16, aw + 4, 12);
        body.draw(body.trim(address, tw), tx, y + pad + 17, overAddress ? GlassTheme.TEXT : GlassTheme.MUTED);
        if (overAddress && !copied) Draw.rect(tx, y + pad + 28.5f, aw, 0.6f, 0, 0x60ffffff);
        hit(tx, y + pad + 16, aw + 4, 12, () -> {
            GuiScreen.setClipboardString(slot.address());
            copiedAt = System.currentTimeMillis();
            UiSound.CLICK.play();
        }, false);

        // Status chips
        float cx = tx, cy = y + pad + 34;
        if (slot.isLan()) {
            cx = chip("LAN world", cx, cy, GlassTheme.OK);
        } else if (slot.pinging()) {
            cx = chip("Pinging…", cx, cy, GlassTheme.MUTED);
        } else if (slot.failed()) {
            cx = chip("Offline", cx, cy, GlassTheme.BAD);
        } else {
            cx = chip(slot.data.pingToServer + " ms", cx, cy, latencyColor(slot));
            String version = ServerSlot.strip(slot.version());
            if (!version.isEmpty()) cx = chip(version, cx, cy, slot.incompatible() ? GlassTheme.BAD : GlassTheme.MUTED);
            String via = ViaUi.badge(slot.data);
            if (via != null) cx = chip(via, cx, cy, accent);
        }

        float sy = y + pad + big + 14;
        sy = drawProtocolRow(slot, x, sy, w, pad, dt);
        // Players
        int[] players = slot.players();
        if (players != null) {
            SmoothFont label = SmoothFont.bold(7.5f);
            label.draw("PLAYERS", x + pad, sy, GlassTheme.FAINT);
            small.drawRight(players[0] + " / " + players[1], x + w - pad, sy, GlassTheme.TEXT);
            float by = sy + 12, bw = w - pad * 2, ratio = players[1] <= 0 ? 0 : Math.min(1, players[0] / (float) players[1]);
            Draw.rect(x + pad, by, bw, 4, 2, 0x22ffffff);
            if (ratio > 0) {
                float fill = Math.max(4, bw * ratio);
                Draw.glow(x + pad, by, fill, 4, 2, 5, Draw.alpha(accent, 0.4f));
                Draw.gradientH(x + pad, by, fill, 4, 2, Draw.mix(accent, 0xffffffff, 0.1f), Draw.mix(accent, 0xffffffff, 0.4f));
            }
            sy = by + 14;
        }

        // MOTD (vanilla's error texts are dark red; shown in a readable red instead)
        String motd = slot.failed() ? "§c" + ServerSlot.strip(slot.motd()) : slot.motd();
        if (!slot.pinging() && !ServerSlot.strip(motd).trim().isEmpty()) {
            SmoothFont label = SmoothFont.bold(7.5f);
            label.draw("MESSAGE", x + pad, sy, GlassTheme.FAINT);
            sy += 12;
            String[] lines = motd.split("\n", 3);
            float boxH = Math.min(2, lines.length) * 10.5f + 10;
            Draw.rect(x + pad, sy, w - pad * 2, boxH, 6, 0x14ffffff);
            SmoothFont font = SmoothFont.regular(8);
            String carry = "";
            for (int i = 0; i < Math.min(2, lines.length); i++) {
                String line = carry + lines[i];
                carry = net.minecraft.client.gui.FontRenderer.getFormatFromString(line);
                font.draw(font.trim(line, w - pad * 2 - 12), x + pad + 6, sy + 5 + i * 10.5f, GlassTheme.MUTED);
            }
            sy += boxH + 10;
        }

        // Buttons sit at the bottom; the online list fills what is left above them.
        float buttonsTop = y + h - pad - (slot.isLan() ? 26 : 26 + 6 + 22);
        String sample = slot.isLan() ? "" : slot.data.playerList;
        if (sample != null && !ServerSlot.strip(sample).trim().isEmpty() && buttonsTop - sy > 30) {
            SmoothFont label = SmoothFont.bold(7.5f);
            label.draw("ONLINE", x + pad, sy, GlassTheme.FAINT);
            sy += 12;
            String[] names = sample.split("\n");
            float areaH = buttonsTop - 10 - sy, lineH = 11;
            playersMax = Math.max(0, names.length * lineH - areaH);
            playersScroll = Math.max(0, Math.min(playersMax, playersScroll));
            Draw.pushClip(x + pad - 2, sy - 1, w - pad * 2 + 4, areaH + 2);
            scrollZone(x + pad, sy, w - pad * 2, areaH);
            for (int i = 0; i < names.length; i++) {
                float ly = sy + i * lineH - playersScroll;
                if (ly > sy + areaH || ly + lineH < sy) continue;
                Draw.circle(x + pad + 2.5f, ly + lineH / 2 - 0.5f, 1.3f, Draw.alpha(accent, 0.8f));
                small.draw(small.trim(names[i], w - pad * 2 - 10), x + pad + 8, small.centerY(ly, lineH) - 0.5f, GlassTheme.TEXT);
            }
            Draw.popClip();
        }

        // Join, then edit / delete
        float bx = x + pad, bw = w - pad * 2;
        button("join", bx, buttonsTop, bw, 26, I18n.format("selectServer.select"), 1, true, () -> join(slot));
        if (!slot.isLan()) {
            float half = (bw - 6) / 2, ry = buttonsTop + 26 + 6;
            button("edit", bx, ry, half, 22, I18n.format("selectServer.edit"), 0, true, () -> edit(slot));
            boolean armed = deleteArmed == slot && System.currentTimeMillis() - deleteArmedAt < DELETE_ARM_MS;
            button("delete", bx + half + 6, ry, half, 22, armed ? "Click to confirm" : I18n.format("selectServer.delete"),
                    armed ? 2 : 3, true, () -> delete(slot));
        }
    }

    /** Cross-version: which version this server joins with; click to change it for this server only. */
    private float drawProtocolRow(ServerSlot slot, float x, float sy, float w, float pad, float dt) {
        SmoothFont label = SmoothFont.bold(7.5f), value = SmoothFont.regular(8);
        label.draw("PROTOCOL", x + pad, sy, GlassTheme.FAINT);
        if (slot.isLan()) {
            value.drawRight("LAN 世界始终用 1.8", x + w - pad, sy - 0.5f, GlassTheme.MUTED);
            return sy + 16;
        }
        String text = ViaUi.serverLabel(slot.address());
        float tw = Math.min(w - pad * 2 - 60, value.width(text));
        float right = x + w - pad, left = right - tw - 10;
        Interaction feel = feel("protocol");
        boolean over = over(left - 4, sy - 3, right - left + 6, 14);
        feel.update(over, over && Mouse.isButtonDown(0), dt);
        int color = Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, 0.4f + 0.6f * feel.hot);
        value.drawRight(value.trim(text, w - pad * 2 - 60), right - 9, sy - 0.5f, color);
        Draw.chevron(right - 3, sy + 3.5f, 2.4f, 0, 1.1f, Draw.mix(GlassTheme.FAINT, GlassTheme.accent(), feel.hot));
        if (feel.hot > 0.01f) Draw.rect(left, sy + 9.5f, right - left - 7, 0.6f, 0, Draw.alpha(0xffffffff, 0.35f * feel.hot));
        hit(left - 4, sy - 3, right - left + 6, 14, () -> {
            feel.pulse();
            UiSound.CLICK.play();
            mc.displayGuiScreen(new GlassVersionPicker(this, slot.address(), ServerSlot.strip(slot.name())));
        }, false);
        return sy + 16;
    }

    private float chip(String text, float x, float y, int color) {
        SmoothFont font = SmoothFont.regular(7.5f);
        float w = font.width(text) + 12, h = 14;
        Draw.rect(x, y, w, h, h / 2, Draw.alpha(color, 0.16f));
        Draw.outline(x, y, w, h, h / 2, 0.6f, Draw.alpha(color, 0.45f), Draw.alpha(color, 0.2f));
        font.drawCentered(text, x + w / 2, font.centerY(y, h) - 0.4f, Draw.mix(color, 0xffffffff, 0.35f));
        return x + w + 5;
    }

    // ================================================================ bottom bar

    private void drawBottomBar(float dt) {
        float y = py + ph - BOTTOM_H - 10;
        float reveal = Anim.outCubic((openTime - 0.12f) / 0.3f);
        Draw.pushAlpha(reveal);
        SmoothFont hint = SmoothFont.regular(7.5f);
        String text = query.isEmpty() ? "Drag to reorder  ·  Double-click to join  ·  Ctrl+F to search"
                : "Clear the search to reorder servers";
        hint.draw(text, px + PAD + 4, hint.centerY(y, BOTTOM_H) - 0.5f, GlassTheme.FAINT);

        float right = px + pw - PAD;
        float back = 78, refresh = 84, add = 104;
        button("back", right - back, y, back, BOTTOM_H, I18n.format("gui.back"), 0, true, this::goBack);
        button("refresh", right - back - 6 - refresh, y, refresh, BOTTOM_H, I18n.format("selectServer.refresh"), 0, true, this::refresh);
        button("add", right - back - 6 - refresh - 6 - add, y, add, BOTTOM_H, "+ " + I18n.format("selectServer.add"), 1, true, this::add);
        Draw.popAlpha();
    }

    /**
     * A glass button.
     *
     * @param style 0 plain, 1 accent, 2 armed (red), 3 plain with a red label
     */
    private void button(String id, float x, float y, float w, float h, String label, int style, boolean enabled, Runnable action) {
        Interaction feel = feel(id);
        boolean over = enabled && over(x, y, w, h);
        feel.update(over, over && Mouse.isButtonDown(0), Anim.dt());
        float r = Math.min(8, h / 2), hot = feel.hot;
        int accent = style == 2 ? GlassTheme.BAD : GlassTheme.accent();
        Draw.pushTransform(x + w / 2, y + h / 2, feel.scale(0.04f), 0, 0);
        Draw.pushAlpha(enabled ? 1 : 0.45f);
        if (style == 1 || style == 2) {
            Draw.glow(x, y, w, h, r, 8, Draw.alpha(accent, 0.18f + 0.22f * hot));
            Draw.gradientV(x, y, w, h, r, Draw.mix(accent, 0xffffffff, 0.12f + 0.1f * hot), Draw.mix(accent, 0xff000000, 0.12f));
            Draw.outline(x, y, w, h, r, 0.75f, 0x50ffffff, 0x10ffffff);
        } else {
            GlassTheme.card(x, y, w, h, r, 0.3f + 0.7f * hot);
            if (hot > 0.01f) Draw.outline(x, y, w, h, r, 0.75f, Draw.alpha(accent, 0.7f * hot), Draw.alpha(accent, 0.3f * hot));
        }
        if (hot > 0.01f) Draw.spot(x, y, w, h, r, mx, my, Math.max(40, w * 0.5f), Draw.alpha(0xffffffff, 0.12f * hot));
        SmoothFont font = style == 1 || style == 2 ? SmoothFont.bold(9) : SmoothFont.regular(9);
        int color = style == 3 ? Draw.mix(0xffff8a80, 0xffffffff, 0.3f * hot)
                : style == 0 ? Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, 0.6f + 0.4f * hot) : ink(accent);
        font.drawCentered(font.trim(label, w - 8), x + w / 2, font.centerY(y, h) - 0.5f, color);
        Draw.popAlpha();
        Draw.popTransform();
        if (enabled) hit(x, y, w, h, () -> {
            feel.pulse();
            UiSound.CLICK.play();
            action.run();
        }, false);
    }

    /** Text colour on an accent fill: dark on bright accents (yellow, lime), white otherwise. */
    private static int ink(int fill) {
        float r = (fill >> 16 & 255) / 255f, g = (fill >> 8 & 255) / 255f, b = (fill & 255) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b > 0.6f ? 0xff14171d : 0xffffffff;
    }

    private Interaction feel(String id) {
        Interaction feel = feels.get(id);
        if (feel == null) {
            feel = new Interaction();
            feels.put(id, feel);
        }
        return feel;
    }

    // ================================================================ quick connect history

    private List<String> history() {
        List<String> list = new ArrayList<>(RecentServers.all());
        String last = mc.gameSettings.lastServer;
        if (list.isEmpty() && last != null && !last.trim().isEmpty()) list.add(last.trim());
        String typed = quick.getText().trim().toLowerCase(Locale.ROOT);
        if (!typed.isEmpty()) list.removeIf(entry -> !entry.toLowerCase(Locale.ROOT).contains(typed) || entry.equalsIgnoreCase(typed));
        return list;
    }

    private void drawHistory(float dt) {
        List<String> list = quick.isFocused() ? history() : Collections.<String>emptyList();
        float show = historyShow.update(!list.isEmpty(), dt);
        if (show < 0.01f || list.isEmpty()) {
            dropdown[2] = dropdown[3] = 0;
            return;
        }
        float rowH = 20, x = quickX, w = quickW, y = fieldY + TOP_H + 5;
        float h = 8 + list.size() * rowH + 18;
        dropdown[0] = x;
        dropdown[1] = y;
        dropdown[2] = w;
        dropdown[3] = h;
        Draw.pushAlpha(show);
        Draw.pushTransform(x + w / 2, y, 0.96f + 0.04f * show, 0, (1 - show) * -4);
        GlassTheme.panel(x, y, w, h, 10, 1.1f);
        SmoothFont label = SmoothFont.bold(7);
        label.draw("RECENT", x + 10, y + 7, GlassTheme.FAINT);
        SmoothFont font = SmoothFont.regular(8.5f);
        for (int i = 0; i < list.size(); i++) {
            String entry = list.get(i);
            float ry = y + 18 + i * rowH;
            boolean over = overTop(x + 4, ry, w - 8, rowH);
            if (over) Draw.rect(x + 4, ry, w - 8, rowH, 6, Draw.alpha(GlassTheme.accent(), 0.22f));
            Draw.chevron(x + 13, ry + rowH / 2, 2.4f, 0, 1.1f, over ? GlassTheme.TEXT : GlassTheme.FAINT);
            font.draw(font.trim(entry, w - 34), x + 22, font.centerY(ry, rowH) - 0.5f, over ? GlassTheme.TEXT : GlassTheme.MUTED);
            hit(x + 4, ry, w - 8, rowH, () -> {
                quick.setText(entry);
                quickConnect();
            }, true);
        }
        Draw.popTransform();
        Draw.popAlpha();
    }

    // ================================================================ actions

    private void select(ServerSlot slot) {
        if (slot == selected) return;
        selected = slot;
        detailFade.snap(0);
        playersScroll = 0;
        deleteArmed = null;
    }

    private void join(ServerSlot slot) {
        if (slot == null) return;
        if (!slot.isLan()) RecentServers.add(slot.address());
        connect(slot.isLan() ? new ServerData(slot.lan.getServerMotd(), slot.lan.getServerIpPort(), true) : slot.data);
    }

    private void quickConnect() {
        String address = quick.getText().trim();
        if (address.isEmpty()) return;
        mc.gameSettings.lastServer = address;
        mc.gameSettings.saveOptions();
        RecentServers.add(address);
        quick.setFocused(false);
        connect(new ServerData(I18n.format("selectServer.defaultName"), address, false));
    }

    private void connect(ServerData data) {
        FMLClientHandler.instance().connectToServer(this, data);
    }

    private void add() {
        adding = true;
        pending = new ServerData(I18n.format("selectServer.defaultName"), "", false);
        mc.displayGuiScreen(new GuiScreenAddServer(this, pending));
    }

    private void edit(ServerSlot slot) {
        if (slot == null || slot.isLan()) return;
        editing = true;
        editTarget = slot;
        pending = new ServerData(slot.data.serverName, slot.data.serverIP, false);
        pending.copyFrom(slot.data);
        mc.displayGuiScreen(new GuiScreenAddServer(this, pending));
    }

    /** First click arms the button, a second one within three seconds deletes. */
    private void delete(ServerSlot slot) {
        if (slot == null || slot.isLan()) return;
        long now = System.currentTimeMillis();
        if (deleteArmed != slot || now - deleteArmedAt >= DELETE_ARM_MS) {
            deleteArmed = slot;
            deleteArmedAt = now;
            return;
        }
        deleteArmed = null;
        int index = saved.indexOf(slot);
        if (index < 0) return;
        servers.removeServerData(index);
        servers.saveServerList();
        saved.remove(index);
        if (saved.isEmpty()) select(null);
        else select(saved.get(Math.min(index, saved.size() - 1)));
    }

    private void refresh() {
        servers.loadServerList();
        ServerSlot was = selected;
        int index = was == null ? -1 : saved.indexOf(was);
        rebuildSaved();
        for (ServerSlot slot : saved) slot.appearDelay = openTime + 0.02f + Math.min(saved.indexOf(slot), 12) * 0.03f;
        selected = null;
        if (index >= 0 && index < saved.size()) select(saved.get(index));
        else if (was != null && was.isLan() && lan.contains(was)) select(was);
    }

    private void goBack() {
        mc.displayGuiScreen(parent);
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        if (adding) {
            adding = false;
            if (result && pending != null) {
                servers.addServerData(pending);
                servers.saveServerList();
                ServerSlot slot = new ServerSlot(pending);
                slot.appearDelay = openTime + 0.15f;
                saved.add(slot);
                search.setText("");
                select(slot);
                scrollTarget = (saved.size() - 1) * STEP;
            }
        } else if (editing) {
            editing = false;
            if (result && editTarget != null && pending != null) {
                ServerData data = editTarget.data;
                data.serverName = pending.serverName;
                data.serverIP = pending.serverIP;
                data.copyFrom(pending);
                servers.saveServerList();
                editTarget.reping();
            }
            editTarget = null;
        }
        pending = null;
        mc.displayGuiScreen(this);
    }

    @Override
    public void connectToSelected() {
        join(selected);
    }

    @Override
    public void selectServer(int index) {
        select(index >= 0 && index < saved.size() ? saved.get(index) : null);
    }

    @Override
    public ServerList getServerList() {
        return servers;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        // Buttons other mods add to the multiplayer screen are not shown here.
    }

    // ================================================================ cards: press, drag, drop

    private void pressCard(ServerSlot slot) {
        long now = System.currentTimeMillis();
        boolean twice = lastClickSlot == slot && now - lastClickTime < DOUBLE_CLICK_MS;
        lastClickSlot = slot;
        lastClickTime = now;
        select(slot);
        if (twice) {
            lastClickSlot = null;
            join(slot);
            return;
        }
        pressSlot = slot;
        pressX = mx;
        pressY = my;
        grabOffset = my - (listY - scroll + slot.y.value);
    }

    private boolean canDrag(ServerSlot slot) {
        return slot != null && !slot.isLan() && query.isEmpty() && saved.size() > 1;
    }

    private void updateDrag(float dt) {
        if (pressSlot != null && !Mouse.isButtonDown(0)) {
            drop();
            return;
        }
        if (pressSlot == null) return;
        if (dragSlot == null && canDrag(pressSlot) && Math.hypot(mx - pressX, my - pressY) > 4) {
            dragSlot = pressSlot;
            dragTo = saved.indexOf(dragSlot);
            lastClickSlot = null;
        }
        if (dragSlot == null) return;
        dragY = my - grabOffset;
        // Near the edges the list scrolls by itself, faster the closer the cursor is.
        float edge = 26;
        if (my < listY + edge) scrollTarget -= 420 * dt * Math.min(1, (listY + edge - my) / edge);
        else if (my > listY + listH - edge) scrollTarget += 420 * dt * Math.min(1, (my - (listY + listH - edge)) / edge);
        float content = dragY - listY + scroll;
        dragTo = Math.max(0, Math.min(saved.size() - 1, Math.round(content / STEP)));
    }

    private void drop() {
        ServerSlot slot = dragSlot;
        pressSlot = null;
        dragSlot = null;
        if (slot == null) return;
        int from = saved.indexOf(slot), to = Math.max(0, Math.min(saved.size() - 1, dragTo));
        // Continue from where it was let go, so it glides into its place.
        slot.y.snap(dragY - listY + scroll);
        if (from < 0 || from == to) return;
        move(from, to);
    }

    /** Move a saved server, the way vanilla's Shift+arrow does: one swap at a time, then save. */
    private void move(int from, int to) {
        int i = from;
        while (i < to) {
            servers.swapServers(i, i + 1);
            i++;
        }
        while (i > to) {
            servers.swapServers(i, i - 1);
            i--;
        }
        servers.saveServerList();
        ServerSlot slot = saved.remove(from);
        saved.add(to, slot);
        UiSound.CLICK.play();
    }

    // ================================================================ hit testing

    private void hit(float x, float y, float w, float h, Runnable action, boolean keepsQuick) {
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
        hit.keepsQuick = keepsQuick;
    }

    /** Hovered, and not covered by the quick connect dropdown. */
    private boolean over(float x, float y, float w, float h) {
        if (!interactive() || inDropdown(mx, my)) return false;
        return overTop(x, y, w, h);
    }

    /** Hovered, ignoring the dropdown (for the dropdown itself). */
    private boolean overTop(float x, float y, float w, float h) {
        return interactive() && mx >= x && mx < x + w && my >= y && my < y + h && Draw.insideClip(mx, my);
    }

    private boolean inDropdown(float x, float y) {
        return dropdown[2] > 0 && x >= dropdown[0] && x < dropdown[0] + dropdown[2] && y >= dropdown[1] && y < dropdown[1] + dropdown[3];
    }

    private Hit hitAt(float x, float y) {
        for (int i = hitCount - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (x >= hit.x0 && x < hit.x1 && y >= hit.y0 && y < hit.y1) return hit;
        }
        return null;
    }

    private static boolean inside(float x, float y, float bx, float by, float bw, float bh) {
        return x >= bx && x < bx + bw && y >= by && y < by + bh;
    }

    // ================================================================ input

    @Override
    public void handleMouseInput() {
        // Vanilla's version also feeds its own list, which this screen does not have.
        float x = Mouse.getEventX() / ui, y = (mc.displayHeight - Mouse.getEventY() - 1) / ui;
        int button = Mouse.getEventButton();
        if (Mouse.getEventButtonState()) {
            eventButton = button;
            press(x, y, button);
        } else if (button != -1) {
            eventButton = -1;
            release(button);
        }
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) wheel += dWheel;
    }

    /** For callers that click the screen directly (GUI coordinates). */
    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        press(mouseX / zoom, mouseY / zoom, button);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        release(button);
    }

    private void press(float x, float y, int button) {
        if (!interactive()) return;
        mx = x;
        my = y;
        Hit hit = hitAt(x, y);
        boolean inSearch = inside(x, y, searchX, fieldY, searchW, TOP_H);
        boolean inQuick = inside(x, y, quickX, fieldY, quickW, TOP_H);
        if (button == 0) {
            focusField(search, inSearch, x);
            focusField(quick, inQuick || hit != null && hit.keepsQuick, x);
            if (inSearch || inQuick) return;
            if (hit != null && hit.action != null) {
                hit.action.run();
                return;
            }
            if (hit == null && inside(x, y, listX, listY, listW, listH)) select(null);
        } else if (button == 1 && inside(x, y, listX, listY, listW, listH)) {
            // Right click on a card just selects it.
            for (ServerSlot slot : allShown()) {
                if (inside(x, y, listX, listY - scroll + slot.y.value, listW, CARD_H)) {
                    select(slot);
                    break;
                }
            }
        }
    }

    private void focusField(GlassTextField field, boolean focus, float x) {
        if (!focus) {
            field.setFocused(false);
            return;
        }
        int clampedX = Math.round(Math.max(field.xPosition, Math.min(field.xPosition + field.getWidth() - 5, x)));
        field.mouseClicked(clampedX, field.yPosition, 0);
        field.setFocused(true);
    }

    private void release(int button) {
        if (button == 0 && pressSlot != null) drop();
    }

    private List<ServerSlot> allShown() {
        List<ServerSlot> all = new ArrayList<>(shownSaved());
        all.addAll(shownLan());
        return all;
    }

    private void applyWheel() {
        if (wheel == 0) return;
        float notches = wheel / 120f;
        wheel = 0;
        if (!interactive()) return;
        if (playersMax > 0 && inside(mx, my, playersZone[0], playersZone[1], playersZone[2], playersZone[3])) {
            playersScroll = Math.max(0, Math.min(playersMax, playersScroll - notches * 22));
            return;
        }
        if (inside(mx, my, listX - 6, listY - 4, listW + 12, listH + 8)) {
            scrollTarget -= notches * STEP * 1.2f;
        }
    }

    private final float[] playersZone = new float[4];

    private void scrollZone(float x, float y, float w, float h) {
        playersZone[0] = x;
        playersZone[1] = y;
        playersZone[2] = w;
        playersZone[3] = h;
    }

    /** Typing anywhere searches, so the input method stays available on this screen. */
    @Override
    public boolean wantsTextInput() {
        return true;
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (quick.isFocused()) {
            if (key == Keyboard.KEY_ESCAPE) quick.setFocused(false);
            else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) quickConnect();
            else if (key == Keyboard.KEY_TAB) {
                quick.setFocused(false);
                search.setFocused(true);
            } else quick.textboxKeyTyped(typed, key);
            return;
        }
        if (search.isFocused()) {
            if (key == Keyboard.KEY_ESCAPE) {
                if (search.getText().isEmpty()) search.setFocused(false);
                else search.setText("");
                return;
            }
            if (key == Keyboard.KEY_TAB) {
                search.setFocused(false);
                quick.setFocused(true);
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
            case Keyboard.KEY_NUMPADENTER:
                if (selected == null) {
                    List<ServerSlot> shown = allShown();
                    if (!query.isEmpty() && shown.size() == 1) select(shown.get(0));
                }
                join(selected);
                return;
            case Keyboard.KEY_F5:
                refresh();
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
        if (key == Keyboard.KEY_F && isCtrlKeyDown()) {
            search.setFocused(true);
            search.setCursorPositionEnd();
            return;
        }
        if (key == Keyboard.KEY_L && isCtrlKeyDown()) {
            quick.setFocused(true);
            return;
        }
        // Typing anywhere searches.
        if (!isCtrlKeyDown() && ChatAllowedCharacters.isAllowedCharacter(typed) && typed != ' ') {
            search.setFocused(true);
            search.setCursorPositionEnd();
            search.textboxKeyTyped(typed, key);
        }
    }

    /** Up / down moves the selection; with Shift it moves the selected server, like vanilla. */
    private void arrow(int direction) {
        List<ServerSlot> shown = allShown();
        if (shown.isEmpty()) return;
        if (isShiftKeyDown() && selected != null && canDrag(selected)) {
            int from = saved.indexOf(selected), to = from + direction;
            if (from >= 0 && to >= 0 && to < saved.size()) move(from, to);
            ensureVisible(selected);
            return;
        }
        int index = selected == null ? (direction > 0 ? -1 : shown.size()) : shown.indexOf(selected);
        int next = Math.max(0, Math.min(shown.size() - 1, index + direction));
        select(shown.get(next));
        ensureVisible(shown.get(next));
    }

    private void ensureVisible(ServerSlot slot) {
        float top = slot.y.value;
        if (top < scrollTarget) scrollTarget = top - 4;
        else if (top + CARD_H > scrollTarget + listH) scrollTarget = top + CARD_H - listH + 4;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ================================================================ account face

    private static final Map<String, ResourceLocation> SKINS = new ConcurrentHashMap<>();
    private static final Set<String> LOOKING_UP = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** The signed-in account's skin; the default skin until the real one has been fetched. */
    private static ResourceLocation accountSkin() {
        GameProfile profile = mc.getSession().getProfile();
        String key = profile.getName();
        ResourceLocation known = SKINS.get(key);
        if (known != null) return known;
        UUID id = profile.getId() != null ? profile.getId() : EntityPlayer.getOfflineUUID(key);
        if (profile.getId() != null && LOOKING_UP.add(key)) {
            Thread thread = new Thread(() -> {
                try {
                    GameProfile filled = mc.getSessionService().fillProfileProperties(profile, false);
                    mc.getSkinManager().loadProfileTextures(filled, (type, location, texture) -> {
                        if (type == MinecraftProfileTexture.Type.SKIN) SKINS.put(key, location);
                    }, false);
                } catch (Exception ignored) {
                    // Offline or rate limited: the default skin stays.
                }
            }, "BetterMyau Skin Lookup");
            thread.setDaemon(true);
            thread.start();
        }
        return DefaultPlayerSkin.getDefaultSkin(id);
    }
}
