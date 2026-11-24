package me.ksyz.accountmanager.gui;

import me.ksyz.accountmanager.AccountManager;
import me.ksyz.accountmanager.auth.Account;
import me.ksyz.accountmanager.auth.MicrosoftAuth;
import me.ksyz.accountmanager.auth.SessionManager;
import me.ksyz.accountmanager.utils.Notification;
import me.ksyz.accountmanager.utils.TextFormatting;
import myau.ui.GlassTextField;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.DesignKit;
import myau.ui.glass.Draw;
import myau.ui.glass.Faces;
import myau.ui.glass.ScreenTransition;
import myau.ui.glass.SmoothFont;
import myau.ui.glass.UiSound;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.Session;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/*
 * This file is derived from https://github.com/ksyzov/AccountManager.
 * Originally licensed under the GNU LGPL.
 *
 * This modified version is licensed under the GNU GPL v3.
 */

/**
 * The account manager as a full glass page, in the same frame as the server and world lists: a top
 * bar with search and the current session, the saved accounts as cards (face, name, type, ban
 * state, which one is in use), a panel with the selected account's details and actions, and the
 * ways to add accounts along the bottom. Signing in works exactly as before.
 */
public class GuiAccountManager extends GuiScreen implements myau.util.ime.TextInputScreen {
    private static final Logger LOGGER = LogManager.getLogger("BetterMyau/Accounts");
    private static final String TOKEN_CLIENT = "00000000402b5328";
    private static final float MARGIN = 14, PAD = 14, TOP_H = 26, BOTTOM_H = 26;
    private static final float CARD_H = 42, CARD_GAP = 6, STEP = CARD_H + CARD_GAP, R_CARD = 9, FACE = 26;
    private static final long DOUBLE_CLICK_MS = 320, DELETE_ARM_MS = 3000, DELETE_GUARD_MS = 250;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final GuiScreen previousScreen;
    private final DesignKit kit = DesignKit.zoom(760, 420);
    private Notification notification;
    private ExecutorService executor;
    private CompletableFuture<Session> loginTask;
    private String loginName = "";
    private boolean initialized, reload;

    private Account selected;
    private final Map<Account, Card> cards = new HashMap<>();
    private final Anim.Smooth detailFade = new Anim.Smooth(1, 14);
    private final Anim.Smooth searchFocus = new Anim.Smooth(0, 16);
    private GlassTextField search;
    private String query = "";

    private float px, py, pw, ph, listX, listY, listW, listH, detailX, detailW, searchX, searchW, fieldY, chipX, chipW;
    private Account lastClickAccount, deleteArmed;
    private long lastClickTime, deleteArmedAt, copiedAt;
    private float scrollTarget, scroll, contentH;

    /** Per-account animation state. */
    private static final class Card {
        final myau.ui.glass.Interaction feel = new myau.ui.glass.Interaction();
        final Anim.Smooth select = new Anim.Smooth(0, 14);
        final Anim.Spring y = Anim.spring(0, 0.32f, 0.82f);
        boolean placed;
        float appearDelay;
    }

    public GuiAccountManager(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    public GuiAccountManager(GuiScreen previousScreen, Notification notification) {
        this.previousScreen = previousScreen;
        this.notification = notification;
    }

    /** Show a message on this screen (used when a dialog opened from here returns to it). */
    public void notice(Notification notification) {
        this.notification = notification;
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
            // Reloading replaces the Account objects a running sign-in still holds: never during one.
            if (loginTask == null) AccountManager.load();
            String keep = selected == null ? null : selected.getUsername();
            selected = null;
            cards.clear();
            List<Account> all = AccountManager.accounts;
            for (int i = 0; i < all.size(); i++) {
                Account account = all.get(i);
                card(account).appearDelay = first ? 0.08f + Math.min(i, 12) * 0.035f : 0;
                if (keep != null && keep.equals(account.getUsername())) selected = account;
            }
            if (selected == null) selected = activeAccount();
            if (first) kit.restart();
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        reload = true;
        if (loginTask != null) {
            CompletableFuture<Session> cancelled = loginTask;
            loginTask = null;
            cancelled.cancel(true);
            notification = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public void updateScreen() {
        if (search != null) search.updateCursorCounter();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public boolean wantsTextInput() {
        return true;
    }

    private Card card(Account account) {
        Card card = cards.get(account);
        if (card == null) {
            card = new Card();
            cards.put(account, card);
        }
        return card;
    }

    private void layout() {
        px = MARGIN;
        py = MARGIN;
        pw = kit.sw - MARGIN * 2;
        ph = kit.sh - MARGIN * 2;
        fieldY = py + 12;
        SmoothFont title = SmoothFont.bold(12);
        float titleEnd = px + PAD + 4 + title.width("帐号管理") + 10 + DesignKit.chipWidth(countLabel()) + 16;
        chipW = DesignKit.accountChipWidth();
        chipX = px + pw - PAD - chipW;
        searchX = titleEnd;
        searchW = Math.max(140, Math.min(240, chipX - 10 - searchX));
        listY = py + 12 + TOP_H + 14;
        detailW = Math.max(230, Math.min(300, pw * 0.34f));
        listX = px + PAD;
        listW = pw - PAD * 2 - detailW - 12;
        detailX = listX + listW + 12;
        listH = py + ph - BOTTOM_H - 22 - listY;
    }

    private String countLabel() {
        return AccountManager.accounts.size() + " 个帐号";
    }

    private void createField() {
        String text = search == null ? "" : search.getText();
        boolean focused = search != null && search.isFocused();
        search = new GlassTextField(0, fontRendererObj, Math.round(searchX + 22), Math.round(fieldY + 7), Math.round(searchW - 24), 12);
        search.setMaxStringLength(32);
        search.setText(text);
        search.setFocused(focused);
    }

    // ================================================================ account facts

    private static String type(Account account) {
        if (account.isOffline()) return "离线";
        return TOKEN_CLIENT.equals(account.getClientId()) ? "Token" : "Microsoft";
    }

    private static int typeColor(Account account) {
        if (account.isOffline()) return 0xffa7adba;
        return TOKEN_CLIENT.equals(account.getClientId()) ? 0xffb48cff : 0xff5aa9ff;
    }

    /** True when this account is the one signed in now. */
    private static boolean active(Account account) {
        String name = account.getUsername();
        if (StringUtils.isBlank(name)) return false;
        Session session = SessionManager.get();
        if (account.isOffline()) {
            return session.getSessionType() == Session.Type.LEGACY && session.getToken().isEmpty() && name.equals(session.getUsername());
        }
        if (!StringUtils.isEmpty(account.getAccessToken()) && account.getAccessToken().equals(session.getToken())) return true;
        return name.equals(session.getUsername());
    }

    private static Account activeAccount() {
        for (Account account : AccountManager.accounts) if (active(account)) return account;
        return null;
    }

    private static String displayName(Account account) {
        return StringUtils.isBlank(account.getUsername()) ? "?" : account.getUsername();
    }

    /** The ban as a short label, or null when the account is not banned. */
    private static String ban(Account account) {
        long unban = account.getUnban(), now = System.currentTimeMillis();
        if (unban < 0) return "永久封禁";
        if (unban <= now) return null;
        long diff = unban - now;
        long d = diff / 86_400_000L, h = diff / 3_600_000L % 24, m = diff / 60_000L % 60, s = diff / 1000L % 60;
        String left = d > 0 ? d + "天 " + h + "时" : h > 0 ? h + "时 " + m + "分" : m > 0 ? m + "分 " + s + "秒" : s + "秒";
        return "封禁 " + left;
    }

    private static net.minecraft.util.ResourceLocation face(Account account) {
        UUID id = null;
        String raw = account.getUuid();
        if (!account.isOffline() && raw != null && !raw.isEmpty()) {
            try {
                id = raw.contains("-") ? UUID.fromString(raw) : com.mojang.util.UUIDTypeAdapter.fromString(raw);
            } catch (RuntimeException ignored) {
            }
        }
        if (active(account) && !account.isOffline()) return Faces.session();
        return Faces.of(displayName(account), id);
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
        query = search.getText().trim().toLowerCase(Locale.ROOT);
        if (selected != null && !AccountManager.accounts.contains(selected)) selected = null;

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
            drawNotice();
        } finally {
            Draw.endBatch();
            kit.pop();
        }
    }

    private void drawTopBar(float dt) {
        float y = fieldY, h = TOP_H;
        Draw.pushAlpha(Anim.outCubic(kit.openTime / 0.3f));
        SmoothFont title = SmoothFont.bold(12);
        float end = title.draw("帐号管理", px + PAD + 4, title.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        String count = countLabel();
        SmoothFont small = SmoothFont.bold(7.5f);
        float cw = DesignKit.chipWidth(count), ch = 13, cx = end + 10, cy = y + (h - ch) / 2;
        Draw.rect(cx, cy, cw, ch, ch / 2, Draw.alpha(GlassTheme.accent(), 0.18f));
        small.drawCentered(count, cx + cw / 2, small.centerY(cy, ch) - 0.3f, GlassTheme.TEXT);
        kit.searchBox(searchX, y, searchW, h, search, "搜索帐号", searchFocus.update(search.isFocused(), dt));
        kit.accountChip("session", chipX, y, chipW, h, null);
        Draw.popAlpha();
    }

    private List<Account> shown() {
        List<Account> out = new ArrayList<>();
        for (Account account : AccountManager.accounts) {
            if (query.isEmpty() || displayName(account).toLowerCase(Locale.ROOT).contains(query)) out.add(account);
        }
        return out;
    }

    private void drawList(float dt) {
        List<Account> shown = shown();
        for (int i = 0; i < shown.size(); i++) {
            Card card = card(shown.get(i));
            if (!card.placed) {
                card.y.snap(i * STEP);
                card.placed = true;
            } else {
                card.y.update(i * STEP, dt);
            }
        }
        contentH = shown.isEmpty() ? 0 : shown.size() * STEP - CARD_GAP;
        float max = Math.max(0, contentH - listH + 4);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        scroll = Anim.reduced() ? scrollTarget : scroll + (scrollTarget - scroll) * (1 - (float) Math.exp(-16 * dt));
        if (Math.abs(scroll - scrollTarget) < 0.05f) scroll = scrollTarget;

        Draw.pushClip(listX - 6, listY - 4, listW + 12, listH + 8);
        float top = listY - scroll;
        if (shown.isEmpty()) {
            boolean searching = !query.isEmpty();
            GlassTheme.card(listX, top, listW, 74, R_CARD, 0.1f);
            SmoothFont big = SmoothFont.bold(10), small = SmoothFont.regular(8.5f);
            big.drawCentered(searching ? "没有匹配的帐号" : "还没有保存的帐号", listX + listW / 2, top + 20, GlassTheme.TEXT);
            small.drawCentered(searching ? "换个名字试试" : "用下方的按钮添加微软帐号、Token，或者生成一个离线帐号",
                    listX + listW / 2, top + 38, GlassTheme.MUTED);
        }
        for (Account account : shown) drawCard(account, listX, top + card(account).y.value, listW, dt);
        Draw.popClip();

        if (contentH > listH + 1) {
            float track = listH - 8, thumb = Math.max(24, track * listH / contentH);
            float pos = listY + 4 + (track - thumb) * (scroll / Math.max(1, max));
            Draw.rect(listX + listW + 3, pos, 2.2f, thumb, 1.1f, 0x40ffffff);
        }
    }

    private void drawCard(Account account, float x, float y, float w, float dt) {
        Card card = card(account);
        if (!Draw.visible(x - 20, y - 20, w + 40, CARD_H + 40)) {
            card.feel.update(false, false, dt);
            return;
        }
        float t = kit.openTime - card.appearDelay;
        float appear = Anim.outCubic(t / 0.32f);
        if (appear <= 0.002f) return;
        boolean hovered = kit.over(x, y, w, CARD_H);
        card.feel.update(hovered, hovered && Mouse.isButtonDown(0), dt);
        float sel = card.select.update(selected == account, dt);
        float hot = card.feel.hot;
        int accent = GlassTheme.accent();
        boolean active = active(account);

        Draw.pushAlpha(appear);
        Draw.pushTransform(x + w / 2, y + CARD_H / 2, (0.94f + 0.06f * Anim.outBack(t / 0.42f)) * card.feel.scale(0.015f), 0, (1 - appear) * 8);
        GlassTheme.card(x, y, w, CARD_H, R_CARD, 0.15f + 0.55f * hot + 0.3f * sel);
        if (sel > 0.01f) {
            Draw.rect(x, y, w, CARD_H, R_CARD, Draw.alpha(accent, 0.12f * sel));
            Draw.outline(x, y, w, CARD_H, R_CARD, 0.9f, Draw.alpha(accent, 0.85f * sel), Draw.alpha(accent, 0.35f * sel));
        }
        if (hot > 0.01f) Draw.spot(x, y, w, CARD_H, R_CARD, kit.mx, kit.my, Math.max(80, w * 0.4f), Draw.alpha(0xffffffff, 0.07f * hot));

        float fx = x + 8, fy = y + (CARD_H - FACE) / 2;
        Faces.draw(fx, fy, FACE, 6, face(account));
        if (active) {
            Draw.circle(fx + FACE - 2, fy + FACE - 2, 4, 0xff15161c);
            Draw.circle(fx + FACE - 2, fy + FACE - 2, 2.8f, GlassTheme.OK);
        }

        float right = x + w - 10;
        String ban = ban(account);
        float banW = 0;
        if (ban != null) {
            banW = DesignKit.chipWidth(ban);
            DesignKit.chip(ban, right - banW, y + (CARD_H - 14) / 2, account.getUnban() < 0 ? GlassTheme.BAD : GlassTheme.WARN);
        } else if (active) {
            String using = "使用中";
            banW = DesignKit.chipWidth(using);
            DesignKit.chip(using, right - banW, y + (CARD_H - 14) / 2, GlassTheme.OK);
        }

        float tx = fx + FACE + 10, textW = right - banW - 10 - tx;
        SmoothFont name = SmoothFont.bold(9.5f), small = SmoothFont.regular(7.5f);
        name.draw(name.trim(displayName(account), textW), tx, y + 7.5f, active ? 0xffb9f5c9 : GlassTheme.TEXT);
        String second = type(account) + (account.getLastUsed() > 0 ? "  ·  " + DesignKit.ago(account.getLastUsed()) + "使用" : "");
        small.draw(small.trim(second, textW), tx, y + 23, GlassTheme.MUTED);

        Draw.popTransform();
        Draw.popAlpha();
        kit.hit(x, y, w, CARD_H, () -> pressCard(account));
    }

    private void pressCard(Account account) {
        long now = System.currentTimeMillis();
        boolean twice = lastClickAccount == account && now - lastClickTime < DOUBLE_CLICK_MS;
        lastClickAccount = account;
        lastClickTime = now;
        if (selected != account) {
            select(account);
            UiSound.SELECT.play();
        }
        if (twice) login(account);
    }

    private void select(Account account) {
        if (selected == account) return;
        selected = account;
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
        Account account = selected;
        if (account == null) {
            SmoothFont big = SmoothFont.bold(9.5f), small = SmoothFont.regular(8);
            big.drawCentered("没有选中帐号", x + w / 2, y + h / 2 - 12, GlassTheme.MUTED);
            small.drawCentered("在左侧点一下，双击直接登录", x + w / 2, y + h / 2 + 2, GlassTheme.FAINT);
        } else {
            float fade = detailFade.update(1, dt);
            Draw.pushAlpha(fade);
            Draw.pushTransform(x + w / 2, y + 40, 1, 0, (1 - fade) * 6);
            drawDetailBody(account, x, y, w, h);
            Draw.popTransform();
            Draw.popAlpha();
        }
        Draw.popTransform();
        Draw.popAlpha();
    }

    private void drawDetailBody(Account account, float x, float y, float w, float h) {
        float pad = 14, big = 52;
        boolean active = active(account);
        Faces.draw(x + pad, y + pad, big, 11, face(account));
        Draw.outline(x + pad, y + pad, big, big, 11, 0.6f, 0x30ffffff, 0x10ffffff);

        float tx = x + pad + big + 11, tw = x + w - pad - tx;
        SmoothFont title = SmoothFont.bold(11.5f), body = SmoothFont.regular(8.5f);
        boolean copied = System.currentTimeMillis() - copiedAt < 1300;
        String name = copied ? "§a已复制" : displayName(account);
        float nw = Math.min(tw, title.width(name));
        boolean overName = kit.over(tx, y + pad, nw + 4, 16);
        title.draw(title.trim(name, tw), tx, y + pad + 2, GlassTheme.TEXT);
        if (overName && !copied) Draw.rect(tx, y + pad + 16, nw, 0.6f, 0, 0x60ffffff);
        kit.hit(tx, y + pad, nw + 4, 16, () -> copyName(account));

        body.draw(active ? "§a正在使用这个帐号" : "点名字可以复制", tx, y + pad + 19, GlassTheme.MUTED);
        float cx = tx, cy = y + pad + 34;
        cx = DesignKit.chip(type(account), cx, cy, typeColor(account));
        String ban = ban(account);
        if (ban != null) DesignKit.chip(ban, cx, cy, account.getUnban() < 0 ? GlassTheme.BAD : GlassTheme.WARN);

        float sy = y + pad + big + 16;
        sy = infoRow("状态", ban == null ? "§a未封禁" : (account.getUnban() < 0 ? "§c" : "§e") + ban, x + pad, sy, w - pad * 2);
        long last = account.getLastUsed();
        sy = infoRow("上次使用", last > 0 ? DATE.format(new Date(last)) : "—", x + pad, sy, w - pad * 2);
        if (!account.getUuid().isEmpty()) sy = infoRow("UUID", account.getUuid(), x + pad, sy, w - pad * 2);

        // Order in the list
        int index = AccountManager.accounts.indexOf(account);
        float bx = x + pad, bw = w - pad * 2, small = 22;
        float orderY = sy + 6;
        float third = (bw - 12) / 3;
        kit.button("up", bx, orderY, third, small, "上移", DesignKit.PLAIN, index > 0, () -> move(account, -1));
        kit.button("down", bx + third + 6, orderY, third, small, "下移", DesignKit.PLAIN,
                index >= 0 && index < AccountManager.accounts.size() - 1, () -> move(account, 1));
        if (ban != null) {
            kit.button("unban", bx + (third + 6) * 2, orderY, third, small, "清除封禁", DesignKit.PLAIN, true, () -> {
                account.setUnban(0);
                AccountManager.save();
                showNotice("&a已清除封禁标记（" + displayName(account) + "）&r", 3000L);
            });
        } else {
            kit.button("copy", bx + (third + 6) * 2, orderY, third, small, "复制名称", DesignKit.PLAIN, true, () -> copyName(account));
        }

        float buttonsTop = y + h - pad - 26 - 6 - 22;
        boolean busy = loginTask != null;
        String loginLabel = busy ? "正在登录…" : active ? "重新登录" : "登录";
        kit.button("login", bx, buttonsTop, bw, 26, loginLabel, DesignKit.ACCENT, !busy, () -> login(account));
        boolean armed = deleteArmed == account && System.currentTimeMillis() - deleteArmedAt < DELETE_ARM_MS;
        String deleteLabel = armed ? "再点一次删除「" + SmoothFont.regular(9).trim(displayName(account), bw - 110) + "」" : "删除";
        kit.button("delete", bx, buttonsTop + 26 + 6, bw, 22, deleteLabel, armed ? DesignKit.ARMED : DesignKit.DANGER, true, () -> delete(account));
    }

    private float infoRow(String label, String value, float x, float y, float w) {
        SmoothFont key = SmoothFont.bold(7.5f), val = SmoothFont.regular(8);
        key.draw(label, x, y + 1, GlassTheme.FAINT);
        float kw = 52;
        val.draw(val.trim(value, w - kw), x + kw, y, GlassTheme.TEXT);
        return y + 14;
    }

    // ================================================================ bottom bar and messages

    private void drawBottomBar() {
        float y = py + ph - BOTTOM_H - 10;
        Draw.pushAlpha(Anim.outCubic((kit.openTime - 0.12f) / 0.3f));
        float left = px + PAD + 4;
        if (loginTask != null) {
            DesignKit.spinner(left + 5, y + BOTTOM_H / 2, 4.5f, 1.3f, GlassTheme.accent() | 0xff000000);
            SmoothFont font = SmoothFont.regular(8);
            font.draw("正在登录 " + loginName + "…", left + 16, font.centerY(y, BOTTOM_H) - 0.5f, GlassTheme.TEXT);
        } else {
            SmoothFont hint = SmoothFont.regular(7.5f);
            hint.draw("双击登录  ·  Ctrl+↑↓ 排序  ·  Ctrl+C 复制名称", left, hint.centerY(y, BOTTOM_H) - 0.5f, GlassTheme.FAINT);
        }
        float right = px + pw - PAD;
        boolean idle = loginTask == null;
        float back = 64, random = 78, session = 86, token = 82, add = 104;
        float bx = right - back;
        kit.button("back", bx, y, back, BOTTOM_H, I18n.format("gui.back"), DesignKit.PLAIN, true, this::goBack);
        bx -= 6 + random;
        kit.button("random", bx, y, random, BOTTOM_H, "随机离线", DesignKit.PLAIN, idle, this::addRandomOffline);
        bx -= 6 + session;
        kit.button("session", bx, y, session, BOTTOM_H, "Session 登录", DesignKit.PLAIN, true, () -> mc.displayGuiScreen(new GuiSessionLogin(this)));
        bx -= 6 + token;
        kit.button("token", bx, y, token, BOTTOM_H, "添加 Token", DesignKit.PLAIN, true, () -> mc.displayGuiScreen(new GuiAddToken(this)));
        bx -= 6 + add;
        kit.button("add", bx, y, add, BOTTOM_H, "+ 微软帐号", DesignKit.ACCENT, true, () -> mc.displayGuiScreen(new GuiMicrosoftAuth(previousScreen)));
        Draw.popAlpha();
    }

    private void drawNotice() {
        if (notification == null || notification.isExpired() || loginTask != null) return;
        GlassTheme.toast(notification.getMessage(), px + pw / 2, py + ph - BOTTOM_H - 46);
    }

    private void showNotice(String message, long duration) {
        notification = new Notification(TextFormatting.translate(message), duration);
    }

    // ================================================================ actions

    private void copyName(Account account) {
        setClipboardString(displayName(account));
        copiedAt = System.currentTimeMillis();
        UiSound.CLICK.play();
    }

    private void move(Account account, int direction) {
        List<Account> all = AccountManager.accounts;
        int from = all.indexOf(account), to = from + direction;
        if (from < 0 || to < 0 || to >= all.size()) return;
        Collections.swap(all, from, to);
        AccountManager.save();
        ensureVisible(account);
    }

    private void delete(Account account) {
        if (account == null) return;
        long now = System.currentTimeMillis();
        if (deleteArmed != account || now - deleteArmedAt >= DELETE_ARM_MS) {
            deleteArmed = account;
            deleteArmedAt = now;
            return;
        }
        if (now - deleteArmedAt < DELETE_GUARD_MS) return;
        deleteArmed = null;
        List<Account> before = shown();
        int index = before.indexOf(account);
        AccountManager.accounts.remove(account);
        AccountManager.save();
        cards.remove(account);
        UiSound.DELETE.play();
        showNotice("&7已删除 " + displayName(account) + "&r", 3000L);
        selected = null;
        List<Account> after = shown();
        if (!after.isEmpty()) select(after.get(Math.max(0, Math.min(after.size() - 1, index))));
    }

    private void goBack() {
        mc.displayGuiScreen(previousScreen);
    }

    private void login(Account account) {
        if (account == null || loginTask != null) return;
        if (account.isOffline()) switchToOffline(account, false);
        else loginOnline(account);
    }

    private void addRandomOffline() {
        if (loginTask != null) return;
        Account account;
        try {
            account = AccountManager.addRandomOfflineAccount(SessionManager.get().getUsername());
        } catch (IOException | IllegalStateException e) {
            LOGGER.error("Couldn't save random offline account", e);
            showNotice("&c离线帐号没能保存&r", 5000L);
            return;
        }
        card(account).appearDelay = kit.openTime;
        select(account);
        scrollTarget = 0;
        switchToOffline(account, true);
    }

    private void switchToOffline(Account account, boolean newlySaved) {
        Session session = SessionManager.createOfflineSession(account.getUsername());
        SessionManager.set(session);
        if (SessionManager.get() == session) {
            account.setLastUsed(System.currentTimeMillis());
            AccountManager.save();
            UiSound.SUCCESS.play();
            showNotice(String.format("&a已切换到离线帐号 %s&r", account.getUsername()), 5000L);
        } else {
            UiSound.ERROR.play();
            showNotice(newlySaved ? "&c帐号已保存，切换失败，请点登录重试&r" : "&c没能切换到这个离线帐号&r", 5000L);
        }
    }

    private void loginOnline(Account account) {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor();
        }
        ExecutorService loginExecutor = executor;
        String username = StringUtils.isBlank(account.getUsername()) ? "???" : account.getUsername();
        loginName = username;
        AtomicReference<String> refreshToken = new AtomicReference<>();
        AtomicReference<String> accessToken = new AtomicReference<>();
        showNotice(String.format("&7正在登录 %s…&r", username), -1L);
        MicrosoftAuth.CLIENT_ID = account.getClientId();
        MicrosoftAuth.SCOPE = account.getScope();
        CompletableFuture<Session> request = MicrosoftAuth.login(account.getAccessToken(), loginExecutor)
                .handle((session, error) -> session)
                .thenCompose(session -> {
                    if (session != null) return CompletableFuture.completedFuture(session);
                    return MicrosoftAuth.refreshMSAccessTokens(account.getRefreshToken(), loginExecutor)
                            .thenCompose(tokens -> {
                                refreshToken.set(tokens.get("refresh_token"));
                                return MicrosoftAuth.acquireXboxAccessToken(tokens.get("access_token"), loginExecutor);
                            })
                            .thenCompose(token -> MicrosoftAuth.acquireXboxXstsToken(token, loginExecutor))
                            .thenCompose(tokens -> MicrosoftAuth.acquireMCAccessToken(
                                    tokens.get("Token"), tokens.get("uhs"), loginExecutor))
                            .thenCompose(token -> {
                                accessToken.set(token);
                                return MicrosoftAuth.login(token, loginExecutor);
                            });
                });
        loginTask = request;
        request.whenComplete((session, error) -> mc.addScheduledTask(() -> {
            // A cancelled login may still finish its network request after the screen is reopened.
            if (loginTask != request || mc.currentScreen != this) return;
            loginTask = null;
            if (error != null) {
                UiSound.ERROR.play();
                showNotice(String.format("&c登录失败：%s（%s）&r", error.getMessage(), username), 6000L);
                return;
            }
            if (accessToken.get() != null) {
                account.setRefreshToken(refreshToken.get());
                account.setAccessToken(accessToken.get());
            }
            account.setUsername(session.getUsername());
            account.setUuid(session.getPlayerID());
            account.setLastUsed(System.currentTimeMillis());
            AccountManager.save();
            SessionManager.set(session);
            UiSound.SUCCESS.play();
            showNotice(String.format("&a登录成功：%s&r", account.getUsername()), 5000L);
        }));
    }

    // ================================================================ input

    @Override
    public void handleMouseInput() {
        if (Mouse.getEventButtonState()) press(kit.eventX(), kit.eventY(), Mouse.getEventButton());
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) kit.addWheel(wheel);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        press(kit.fromGui(mouseX), kit.fromGui(mouseY), button);
    }

    private void press(float x, float y, int button) {
        if (!kit.interactive() || button != 0) return;
        boolean inSearch = DesignKit.inside(x, y, searchX, fieldY, searchW, TOP_H);
        DesignKit.focusField(search, inSearch, x);
        if (inSearch) return;
        if (kit.click(x, y)) return;
        if (DesignKit.inside(x, y, listX, listY, listW, listH)) select(null);
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
                List<Account> shown = shown();
                if (selected == null && shown.size() == 1) select(shown.get(0));
                login(selected);
                return;
            }
            case Keyboard.KEY_DELETE:
                delete(selected);
                return;
            case Keyboard.KEY_UP:
            case Keyboard.KEY_DOWN: {
                int direction = key == Keyboard.KEY_UP ? -1 : 1;
                if (isCtrlKeyDown() && selected != null) {
                    move(selected, direction);
                } else {
                    arrow(direction);
                }
                return;
            }
            default:
                break;
        }
        if (isKeyComboCtrlC(key) && selected != null) {
            copyName(selected);
            return;
        }
        if (isCtrlKeyDown() && key == Keyboard.KEY_F) {
            search.setFocused(true);
            search.setCursorPositionEnd();
            return;
        }
        if (!isCtrlKeyDown() && ChatAllowedCharacters.isAllowedCharacter(typed) && typed != ' ') {
            search.setFocused(true);
            search.setCursorPositionEnd();
            search.textboxKeyTyped(typed, key);
        }
    }

    private void arrow(int direction) {
        List<Account> shown = shown();
        if (shown.isEmpty()) return;
        int index = selected == null ? (direction > 0 ? -1 : shown.size()) : shown.indexOf(selected);
        int next = Math.max(0, Math.min(shown.size() - 1, index + direction));
        select(shown.get(next));
        UiSound.SELECT.play();
        ensureVisible(shown.get(next));
    }

    private void ensureVisible(Account account) {
        int index = shown().indexOf(account);
        if (index < 0) return;
        float top = index * STEP;
        if (top < scrollTarget) scrollTarget = top - 4;
        else if (top + CARD_H > scrollTarget + listH) scrollTarget = top + CARD_H - listH + 4;
    }
}
