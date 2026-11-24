package myau.ui.glass;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Ordering;
import myau.mixin.IAccessorGuiPlayerTabOverlay;
import myau.ui.GlassTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldSettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The player list as the dynamic island draws it while the player list key is held: the
 * server's header, every player with face, name, score and ping, and the footer. The island
 * owns the capsule and its animation; this class keeps the snapshot, measures it and draws it
 * into whatever part of the capsule is open.
 */
final class IslandTab {
    private IslandTab() { }

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final float PAD_X = 13, PAD_TOP = 9, PAD_BOTTOM = 9;
    private static final float ROW = 13, HEAD = 9.5f, COL_GAP = 14, PING_W = 11, HEADER_LINE = 11.5f, FOOTER_LINE = 10;
    private static final float MIN_W = 220, NAME_MIN = 46, NAME_MAX = 118;
    private static final int MAX_PLAYERS = 80, MAX_ROWS = 20;
    private static final long REBUILD_NANOS = 100_000_000L;

    /** Vanilla's order: players before spectators, then by team, then by name. */
    private static final Ordering<NetworkPlayerInfo> ORDER = Ordering.from((a, b) -> {
        ScorePlayerTeam ta = a.getPlayerTeam(), tb = b.getPlayerTeam();
        return ComparisonChain.start()
                .compareTrueFirst(a.getGameType() != WorldSettings.GameType.SPECTATOR, b.getGameType() != WorldSettings.GameType.SPECTATOR)
                .compare(ta != null ? ta.getRegisteredName() : "", tb != null ? tb.getRegisteredName() : "")
                .compare(a.getGameProfile().getName(), b.getGameProfile().getName())
                .result();
    });

    private static final class Row {
        NetworkPlayerInfo info;
        String name, score;
        boolean spectator, self, hat;
    }

    private static final List<Row> rows = new ArrayList<>();
    private static final List<String> header = new ArrayList<>(), footer = new ArrayList<>();
    private static ScoreObjective objective;
    private static boolean hearts;
    private static int total;
    private static String countLabel = "";
    private static long builtAt;
    private static float builtMaxW = -1, builtMaxH = -1;

    // Layout, in the island's designed units.
    private static int columns = 1, perColumn = 1, shown;
    private static float nameW, scoreW, cellW, headerH, footerH, width = MIN_W, height = 60;

    // ---------------------------------------------------------------- state

    /** The same test GuiIngameForge makes before it draws the vanilla list. */
    static boolean wanted() {
        if (mc.thePlayer == null || mc.theWorld == null || mc.gameSettings == null) return false;
        if (!mc.gameSettings.keyBindPlayerList.isKeyDown()) return false;
        NetHandlerPlayClient handler = mc.thePlayer.sendQueue;
        if (handler == null) return false;
        ScoreObjective list = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(0);
        return !mc.isIntegratedServerRunning() || handler.getPlayerInfoMap().size() > 1 || list != null;
    }

    static float width() {
        return width;
    }

    static float height() {
        return height;
    }

    /** Refresh the snapshot (a few times a second) and fit it into maxW x maxH units. */
    static void update(float maxW, float maxH) {
        long now = System.nanoTime();
        boolean sizeChanged = Math.abs(maxW - builtMaxW) > 0.5f || Math.abs(maxH - builtMaxH) > 0.5f;
        if (!sizeChanged && now - builtAt < REBUILD_NANOS) return;
        builtAt = now;
        builtMaxW = maxW;
        builtMaxH = maxH;
        try {
            snapshot();
        } catch (RuntimeException e) {
            // A half-built list from a server that is still sending it; try again next time.
            rows.clear();
        }
        layout(maxW, maxH);
    }

    private static void snapshot() {
        rows.clear();
        header.clear();
        footer.clear();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        NetHandlerPlayClient handler = mc.thePlayer.sendQueue;
        if (handler == null) return;
        Collection<NetworkPlayerInfo> map = handler.getPlayerInfoMap();
        List<NetworkPlayerInfo> sorted = ORDER.sortedCopy(map);
        total = sorted.size();
        objective = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(0);
        hearts = objective != null && objective.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS;

        GuiPlayerTabOverlay overlay = mc.ingameGUI.getTabList();
        java.util.UUID self = mc.thePlayer.getUniqueID();
        for (NetworkPlayerInfo info : sorted) {
            if (rows.size() >= MAX_PLAYERS) break;
            Row row = new Row();
            row.info = info;
            row.name = overlay.getPlayerName(info);
            row.spectator = info.getGameType() == WorldSettings.GameType.SPECTATOR;
            row.self = info.getGameProfile().getId() != null && info.getGameProfile().getId().equals(self);
            EntityPlayer entity = info.getGameProfile().getId() == null ? null : mc.theWorld.getPlayerEntityByUUID(info.getGameProfile().getId());
            row.hat = entity == null || entity.isWearing(EnumPlayerModelParts.HAT);
            if (objective != null) {
                int points = objective.getScoreboard().getValueFromObjective(info.getGameProfile().getName(), objective).getScorePoints();
                row.score = hearts ? "♥ " + points : String.valueOf(points);
            }
            rows.add(row);
        }

        IAccessorGuiPlayerTabOverlay access = (IAccessorGuiPlayerTabOverlay) overlay;
        lines(access.getHeader(), header);
        lines(access.getFooter(), footer);
        if (header.isEmpty()) {
            // No header from the server: name the server ourselves.
            ServerData server = mc.getCurrentServerData();
            String title = server != null && server.serverName != null && !server.serverName.isEmpty() ? server.serverName
                    : mc.isIntegratedServerRunning() ? "Singleplayer" : "Players";
            header.add("§f" + title);
        }
        countLabel = total + (total == 1 ? " player" : " players");
    }

    /** Header / footer text split into lines, colour carried over line breaks, blank edges trimmed. */
    private static void lines(IChatComponent component, List<String> out) {
        if (component == null) return;
        String text = component.getFormattedText();
        if (text == null || text.isEmpty()) return;
        String carry = "";
        for (String line : text.split("\n")) {
            String full = carry + line;
            carry = FontRenderer.getFormatFromString(full);
            out.add(full);
        }
        while (!out.isEmpty() && blank(out.get(0))) out.remove(0);
        while (!out.isEmpty() && blank(out.get(out.size() - 1))) out.remove(out.size() - 1);
    }

    private static boolean blank(String line) {
        return line.replaceAll("§.", "").trim().isEmpty();
    }

    private static void layout(float maxW, float maxH) {
        SmoothFont name = nameFont(), score = scoreFont(), first = headerFont(true), rest = headerFont(false), foot = footerFont();
        int n = rows.size();

        float longest = 0, scoreLongest = 0;
        for (Row row : rows) {
            longest = Math.max(longest, name.width(row.name));
            if (row.score != null) scoreLongest = Math.max(scoreLongest, score.width(row.score));
        }
        nameW = Math.max(NAME_MIN, Math.min(NAME_MAX, longest));
        scoreW = objective != null ? scoreLongest + 4 : 0;

        // Vanilla: at most 20 rows per column.
        columns = 1;
        perColumn = Math.max(1, n);
        while (perColumn > MAX_ROWS) {
            columns++;
            perColumn = (n + columns - 1) / columns;
        }

        // Too wide: narrow the names first, then fall back to fewer, taller columns.
        float inner = maxW - PAD_X * 2;
        cellW = cell();
        if (columns * cellW + (columns - 1) * COL_GAP > inner) {
            float fit = (inner - (columns - 1) * COL_GAP) / columns - (cellW - nameW);
            nameW = Math.max(NAME_MIN, Math.min(nameW, fit));
            cellW = cell();
            int maxColumns = Math.max(1, (int) ((inner + COL_GAP) / (cellW + COL_GAP)));
            if (columns > maxColumns) {
                columns = maxColumns;
                perColumn = (n + columns - 1) / columns;
            }
        }

        headerH = 0;
        for (int i = 0; i < header.size(); i++) headerH += blank(header.get(i)) ? 5 : HEADER_LINE;
        headerH += 5;
        footerH = 0;
        for (String line : footer) footerH += blank(line) ? 4 : FOOTER_LINE;
        if (footerH > 0) footerH += 5;

        // Too tall: fewer rows per column, more columns if they fit, "+N" for the rest.
        int fitRows = Math.max(1, (int) ((maxH - PAD_TOP - PAD_BOTTOM - headerH - footerH) / ROW));
        if (perColumn > fitRows) {
            perColumn = fitRows;
            int maxColumns = Math.max(1, (int) ((inner + COL_GAP) / (cellW + COL_GAP)));
            columns = Math.min(maxColumns, (n + perColumn - 1) / perColumn);
        }
        shown = Math.min(n, columns * perColumn);
        if (shown < n && shown > 0) shown--; // the last cell says how many are left

        float grid = columns * cellW + (columns - 1) * COL_GAP;
        float pill = SmoothFont.bold(7).width(countLabel) + 12;
        float headerW = 0;
        for (int i = 0; i < header.size(); i++) headerW = Math.max(headerW, (i == 0 ? first : rest).width(header.get(i)));
        float footerW = 0;
        for (String line : footer) footerW = Math.max(footerW, foot.width(line));
        float want = Math.max(MIN_W, Math.max(grid + PAD_X * 2, Math.max(headerW + (pill + 10) * 2, footerW + PAD_X * 2)));
        width = Math.min(Math.max(MIN_W, maxW), want);
        float gridH = n == 0 ? ROW : Math.min(perColumn, Math.max(1, n)) * ROW;
        height = Math.min(Math.max(60, maxH), PAD_TOP + headerH + gridH + footerH + PAD_BOTTOM);
    }

    private static float cell() {
        return HEAD + 4 + nameW + (scoreW > 0 ? 5 + scoreW : 0) + 6 + PING_W;
    }

    private static SmoothFont nameFont() { return SmoothFont.regular(8.5f); }
    private static SmoothFont scoreFont() { return SmoothFont.regular(8f); }
    private static SmoothFont headerFont(boolean first) { return first ? SmoothFont.bold(9.5f) : SmoothFont.regular(8.5f); }
    private static SmoothFont footerFont() { return SmoothFont.regular(8f); }

    // ---------------------------------------------------------------- drawing

    /**
     * @param cx   centre of the capsule
     * @param y    top of the capsule
     * @param w    the width this face was laid out for
     * @param open seconds since the island turned into the list, for the staggered reveal
     */
    static void draw(float cx, float y, float w, float open) {
        float x = cx - w / 2, right = x + w - PAD_X;
        float base = ease((open - 0.03f) / 0.18f);
        if (base <= 0.002f) return;
        Draw.pushAlpha(base);

        // Header: centred lines, the first one bold; the player count rides on the right.
        float hy = y + PAD_TOP;
        SmoothFont first = headerFont(true), rest = headerFont(false), foot = footerFont();
        float pillRoom = SmoothFont.bold(7).width(countLabel) + 22;
        for (int i = 0; i < header.size(); i++) {
            String line = header.get(i);
            if (blank(line)) {
                hy += 5;
                continue;
            }
            SmoothFont font = i == 0 ? first : rest;
            font.drawCentered(font.trim(line, w - pillRoom * 2), cx, hy, i == 0 ? GlassTheme.TEXT : GlassTheme.MUTED);
            hy += HEADER_LINE;
        }
        drawCount(right, y + PAD_TOP - 1.5f);

        float gridTop = y + PAD_TOP + headerH;
        Draw.rect(x + PAD_X, gridTop - 3.5f, w - PAD_X * 2, 0.6f, 0, 0x22ffffff);

        float grid = columns * cellW + (columns - 1) * COL_GAP;
        float gx = cx - grid / 2;
        int accent = GlassTheme.accent();
        SmoothFont name = nameFont(), score = scoreFont();
        int n = rows.size();
        for (int i = 0; i < shown; i++) {
            int column = i / perColumn, line = i % perColumn;
            float rowAlpha = ease((open - 0.06f - line * 0.015f - column * 0.03f) / 0.16f);
            if (rowAlpha <= 0.002f) continue;
            float cx0 = gx + column * (cellW + COL_GAP), cy0 = gridTop + line * ROW;
            Draw.pushAlpha(rowAlpha);
            drawRow(rows.get(i), cx0, cy0 + (1 - rowAlpha) * 3, accent, name, score);
            Draw.popAlpha();
        }
        if (shown < n) {
            int column = shown / perColumn, line = shown % perColumn;
            float cx0 = gx + column * (cellW + COL_GAP), cy0 = gridTop + line * ROW;
            name.draw("+" + (n - shown + (total - n)) + " more", cx0 + HEAD + 4, name.centerY(cy0, ROW) - 0.5f, GlassTheme.MUTED);
        } else if (total > n && shown > 0) {
            // More players than the 80 vanilla shows: say so under the grid.
            float cy0 = gridTop + Math.min(perColumn, n) * ROW - 2;
            SmoothFont.regular(7.5f).drawRight("+" + (total - n), right, cy0, GlassTheme.FAINT);
        }
        if (n == 0) {
            name.drawCentered("No players", cx, name.centerY(gridTop, ROW), GlassTheme.MUTED);
        }

        if (!footer.isEmpty()) {
            float fy = gridTop + Math.min(perColumn, Math.max(1, n)) * ROW + 5;
            Draw.rect(x + PAD_X, fy - 3, w - PAD_X * 2, 0.6f, 0, 0x22ffffff);
            for (String line : footer) {
                if (blank(line)) {
                    fy += 4;
                    continue;
                }
                foot.drawCentered(foot.trim(line, w - PAD_X * 2), cx, fy, GlassTheme.MUTED);
                fy += FOOTER_LINE;
            }
        }
        Draw.popAlpha();
    }

    private static void drawCount(float right, float y) {
        SmoothFont font = SmoothFont.bold(7);
        float pw = font.width(countLabel) + 12, ph = 12;
        int accent = GlassTheme.accent();
        Draw.rect(right - pw, y, pw, ph, ph / 2, Draw.alpha(accent, 0.2f));
        Draw.outline(right - pw, y, pw, ph, ph / 2, 0.6f, Draw.alpha(accent, 0.55f), Draw.alpha(accent, 0.25f));
        font.drawCentered(countLabel, right - pw / 2, font.centerY(y, ph) - 0.3f, GlassTheme.TEXT);
    }

    private static void drawRow(Row row, float x, float y, int accent, SmoothFont name, SmoothFont score) {
        if (row.self) {
            Draw.rect(x - 3, y + 0.5f, cellW + 6, ROW - 1, 4, Draw.alpha(accent, 0.16f));
        }
        // Face, and the hat layer over it.
        float hy = y + (ROW - HEAD) / 2;
        int texture = Draw.textureId(row.info.getLocationSkin());
        int tint = row.spectator ? 0x99b0b0b0 : 0xffffffff;
        if (texture > 0) {
            Draw.image(x, hy, HEAD, HEAD, 2.2f, texture, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f, tint);
            if (row.hat) Draw.image(x, hy, HEAD, HEAD, 2.2f, texture, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f, tint);
        } else {
            Draw.rect(x, hy, HEAD, HEAD, 2.2f, 0x40ffffff);
        }

        float textY = name.centerY(y, ROW) - 0.5f;
        String shownName = name.trim(row.name, nameW);
        name.draw(shownName, x + HEAD + 4, textY, row.spectator ? Draw.fade(GlassTheme.TEXT, 0.5f) : GlassTheme.TEXT);

        float pingX = x + cellW - PING_W;
        if (row.score != null) {
            score.drawRight(row.score, pingX - 6, score.centerY(y, ROW) - 0.5f, hearts ? 0xffff5c6c : 0xffffd76a);
        }
        drawPing(row.info.getResponseTime(), pingX, y + ROW / 2 + 3.5f);
    }

    /** Four signal bars, lit by how good the ping is. */
    private static void drawPing(int ping, float x, float bottom) {
        int lit = ping < 0 ? 0 : ping < 150 ? 4 : ping < 300 ? 3 : ping < 600 ? 2 : ping < 1000 ? 1 : 0;
        int color = ping < 0 ? GlassTheme.BAD : Island.pingColor(ping == 0 ? 1 : ping);
        for (int i = 0; i < 4; i++) {
            float bh = 2.5f + i * 1.5f;
            Draw.rect(x + i * 3, bottom - bh, 2, bh, 0.8f, i < lit ? color : 0x30ffffff);
        }
    }

    private static float ease(float t) {
        return Anim.outCubic(Math.max(0, Math.min(1, t)));
    }
}
