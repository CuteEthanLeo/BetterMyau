package myau.music.ui;

import myau.module.modules.MusicHud;
import myau.music.MusicService;
import myau.music.audio.SpectrumAnalyzer;
import myau.music.cover.CoverCache;
import myau.music.cover.Palette;
import myau.music.model.Track;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.Draw;
import myau.ui.glass.Island;
import myau.ui.glass.SmoothFont;
import myau.ui.glass.UiSound;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Mouse;

/**
 * The music capsule beside the Dynamic Island. It never takes room from the island: it floats
 * next to it on the island's springs, grows out of its edge when music starts and slips back in
 * when it stops.
 *
 * <ul>
 *   <li>Compact: round cover + five bars driven by the live spectrum + a hairline of progress.</li>
 *   <li>Peek: on a new song (and while loading) it widens to show title and artist, then shrinks.</li>
 *   <li>Open: with chat open and the pointer on it, it grows into a small player (like, previous,
 *       play/pause, next, a seek bar; wheel = volume; title or right click = music screen).</li>
 * </ul>
 * Behind the glass a glow in the cover's colour breathes with the bass; beats give it a nudge.
 * The content is laid out against the capsule's animated size, so it always fits the liquid shape.
 */
public final class MusicSatellite extends Island.Satellite {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float H = 32, H_OPEN = 48, W_OPEN = 220;
    private static final float COVER = 22, COVER_OPEN = 30;
    private static final int BARS = 5;
    private static final float BAR_W = 2.2f, BAR_GAP = 2f, BARS_W = BARS * BAR_W + (BARS - 1) * BAR_GAP;
    private static final float COMPACT_W = 5 + COVER + 8 + BARS_W + 11;
    private static final float CONTROL = 13;
    private static final int[][] BAND_GROUPS = {{0, 4}, {5, 10}, {11, 17}, {18, 24}, {25, 31}};
    private static final int LIKE_COLOR = 0xffff4d6d;

    private final MusicHud hud;
    private final Anim.Smooth playing = new Anim.Smooth(0, 6);
    private final float[] bars = new float[BARS];
    private float glowR = (Palette.FALLBACK >> 16 & 255) / 255f, glowG = (Palette.FALLBACK >> 8 & 255) / 255f, glowB = (Palette.FALLBACK & 255) / 255f;
    private int lastBeat;
    private long lastKick;

    private boolean hovering, dragging;
    private long hoverLeft, volumeShown;
    private float pointerX, pointerY, dragFraction;
    // Hit areas from the last frame, island units: x, y, w, h.
    private final float[] hitCover = new float[4], hitTitle = new float[4], hitLike = new float[4], hitPrev = new float[4],
            hitPlay = new float[4], hitNext = new float[4], hitBar = new float[4];

    public MusicSatellite(MusicHud hud) {
        this.hud = hud;
    }

    // ------------------------------------------------------------ satellite contract

    @Override
    protected boolean active() {
        if (!this.hud.isEnabled() || !MusicService.running()) return false;
        // Stays while there is a song, playing or not (a restored one waits paused for a click).
        return MusicService.get().current() != null;
    }

    @Override
    protected int side() {
        return this.hud.side.getValue();
    }

    @Override
    protected float gap() {
        return this.hud.gap.getValue();
    }

    @Override
    protected float width() {
        if (this.openWanted()) return W_OPEN;
        Track t = MusicService.get().current();
        if (t == null || !this.peekWanted()) return COMPACT_W;
        float text = Math.max(SmoothFont.bold(7.5f).width(t.name), SmoothFont.regular(6.5f).width(this.subtitle(t)));
        return COMPACT_W + Math.max(40, Math.min(124, text)) + 7;
    }

    @Override
    protected float height() {
        return this.openWanted() ? H_OPEN : H;
    }

    private boolean openWanted() {
        return this.hovering || this.dragging || System.nanoTime() - this.hoverLeft < 350_000_000L;
    }

    private boolean peekWanted() {
        MusicService m = MusicService.get();
        int seconds = this.hud.peekSeconds.getValue();
        boolean recent = seconds > 0 && System.nanoTime() - m.trackChangedNanos() < seconds * 1_000_000_000L;
        boolean volume = System.nanoTime() - this.volumeShown < 1_200_000_000L;
        return recent || volume || m.isWaiting() || !m.status().isEmpty() && m.isWaiting();
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void drawBehind(float x, float y, float w, float h, float alpha, float dt) {
        MusicService m = MusicService.get();
        Track t = m.current();
        CoverCache.Cover c = m.cover(t, 128);
        int target = c != null && c.ready() ? c.color : Palette.FALLBACK;
        float k = Anim.reduced() ? 1 : 1 - (float) Math.exp(-4 * dt);
        this.glowR += ((target >> 16 & 255) / 255f - this.glowR) * k;
        this.glowG += ((target >> 8 & 255) / 255f - this.glowG) * k;
        this.glowB += ((target & 255) / 255f - this.glowB) * k;
        if (!this.hud.glow.getValue()) return;
        SpectrumAnalyzer sp = m.spectrum();
        float live = this.playing.value;
        float energy = 0.3f + 0.7f * sp.bass() + 0.35f * sp.beat();
        float strength = this.hud.glowStrength.getValue() / 100f * (0.25f + 0.75f * live) * energy;
        if (strength < 0.01f) return;
        float r = Math.min(14, h / 2);
        Draw.glow(x + 2, y + 2, w - 4, h - 2, r, 13 + 9 * sp.bass() * live, Draw.alpha(this.glowColor(), Math.min(1, 0.55f * strength)));
    }

    @Override
    protected void draw(float x, float y, float w, float h, float dt) {
        MusicService m = MusicService.get();
        Track t = m.current();
        if (t == null) return;
        SpectrumAnalyzer sp = m.spectrum();
        boolean isPlaying = m.isPlaying();
        float live = this.playing.update(isPlaying ? 1 : 0, dt);
        long now = System.nanoTime();

        // A seek drag ends when the button is let go, wherever the pointer is by then.
        if (this.dragging && !Mouse.isButtonDown(0)) {
            this.dragging = false;
            m.seek((long) (this.dragFraction * m.durationMs()));
        }
        // Beats: a small squash, at most every 180 ms.
        if (this.hud.beatBounce.getValue() && isPlaying && sp.beatSerial() != this.lastBeat) {
            this.lastBeat = sp.beatSerial();
            if (now - this.lastKick > 180_000_000L) {
                this.lastKick = now;
                this.kick(0.7f + 1.2f * sp.bass());
            }
        }
        if (this.hud.islandAmbient.getValue() && live > 0.01f) {
            Island.ambient(this.glowColor(), live * (0.4f + 0.6f * sp.bass()));
        }

        float openK = Draw.clamp01((h - H) / (H_OPEN - H));
        float textK = Draw.clamp01((w - COMPACT_W - 14) / 34f);

        // ---- cover: a circle concentric with the capsule's round end, a rounded square when open
        float d = COVER + (COVER_OPEN - COVER) * openK;
        float cx = x + 5 + 4 * openK, cy = y + (h - d) / 2;
        float cr = d / 2 * (1 - openK) + 8 * openK;
        CoverCache.Cover cover = m.cover(t, 128);
        Draw.rect(cx, cy, d, d, cr, Draw.alpha(this.glowColor(), 0.28f));
        if (cover != null && cover.ready()) {
            Draw.pushAlpha(cover.appear());
            int tint = Draw.mix(0xff8a8f99, 0xffffffff, 0.35f + 0.65f * live);
            Draw.image(cx, cy, d, d, cr, cover.texture, 0, 0, 1, 1, tint);
            Draw.popAlpha();
        } else {
            MusicIcons.spinner(cx + d / 2, cy + d / 2, d * 0.22f, now / 1e9f, 0x88ffffff);
        }
        set(this.hitCover, cx, cy, d, d);

        // ---- right side: spectrum bars (compact / peek) give way to controls (open)
        float barsX = x + w - 11 - BARS_W;
        if (openK < 0.99f) {
            Draw.pushAlpha(1 - openK);
            if (m.isWaiting()) {
                MusicIcons.spinner(barsX + BARS_W / 2, y + h / 2, 4.2f, now / 1e9f, 0xccffffff);
            } else {
                this.drawBars(sp, barsX, y + h / 2, live, now, dt);
            }
            Draw.popAlpha();
        }
        float controlsX = x + w - 10 - CONTROL * 4;
        if (openK > 0.01f) this.drawControls(m, t, controlsX, y + 16, openK);

        // ---- text: title and artist (or volume / status)
        float textX = cx + d + 7 + openK;
        float right = openK > 0.5f ? controlsX - 5 : barsX - 8;
        if (textK > 0.01f && right - textX > 12) {
            SmoothFont title = SmoothFont.bold(7.5f), sub = SmoothFont.regular(6.5f);
            float ty = y + 6.5f + 3f * openK, sy = ty + 10.5f;
            Draw.pushAlpha(textK);
            title.draw(title.trim(t.name, right - textX), textX, ty, GlassTheme.TEXT);
            if (now - this.volumeShown < 1_200_000_000L) {
                this.drawVolume(m, textX, sy, Math.min(70, right - textX));
            } else {
                String s = this.subtitle(t);
                sub.draw(sub.trim(s, right - textX), textX, sy, m.isWaiting() ? GlassTheme.FAINT : GlassTheme.MUTED);
            }
            Draw.popAlpha();
            set(this.hitTitle, textX, y + 4, right - textX, 22);
        } else {
            set(this.hitTitle, 0, 0, 0, 0);
        }

        // ---- progress: a hairline when compact, a seek bar when open
        long duration = m.durationMs();
        float fraction = duration > 0 ? Draw.clamp01(m.positionMs() / (float) duration) : 0;
        if (this.dragging) fraction = this.dragFraction;
        if (openK < 0.99f && duration > 0) {
            float lx = x + 13, lw = w - 26;
            Draw.pushAlpha(1 - openK);
            Draw.rect(lx, y + h - 2.6f, lw, 1.1f, 0.55f, 0x16ffffff);
            Draw.rect(lx, y + h - 2.6f, Math.max(1.1f, lw * fraction), 1.1f, 0.55f, Draw.alpha(this.glowColor(), 0.9f));
            Draw.popAlpha();
        }
        if (openK > 0.01f) {
            this.drawSeekBar(m, textX, x + w - 12, y + h - 11.5f, fraction, duration, openK);
        } else {
            set(this.hitBar, 0, 0, 0, 0);
        }
    }

    private void drawBars(SpectrumAnalyzer sp, float x, float cy, float live, long now, float dt) {
        boolean real = this.hud.spectrum.getValue();
        float k = 1 - (float) Math.exp(-dt * 22);
        for (int i = 0; i < BARS; i++) {
            float v;
            if (real) {
                int[] g = BAND_GROUPS[i];
                v = sp.range(g[0], g[1]);
                v = (float) Math.pow(v, 1.4);
            } else {
                // No spectrum wanted: a calm idle wave.
                v = 0.35f + 0.3f * (float) Math.sin(now / 2.6e8 + i * 1.3);
            }
            float target = BAR_W + (14 - BAR_W) * Draw.clamp01(v) * live;
            this.bars[i] += (target - this.bars[i]) * k;
            float bh = Math.max(BAR_W, this.bars[i]);
            float bx = x + i * (BAR_W + BAR_GAP);
            Draw.gradientV(bx, cy - bh / 2, BAR_W, bh, BAR_W / 2, 0xf2ffffff, Draw.alpha(Draw.mix(this.glowColor(), 0xffffff, 0.35f), 0.95f));
        }
    }

    private void drawControls(MusicService m, Track t, float x, float cy, float alpha) {
        Draw.pushAlpha(alpha);
        boolean liked = m.isLiked(t);
        float[][] hits = {this.hitLike, this.hitPrev, this.hitPlay, this.hitNext};
        for (int i = 0; i < 4; i++) {
            float ccx = x + CONTROL * i + CONTROL / 2;
            set(hits[i], ccx - CONTROL / 2, cy - CONTROL / 2, CONTROL, CONTROL);
            boolean over = this.hovering && inside(hits[i], this.pointerX, this.pointerY);
            if (over) Draw.circle(ccx, cy, 6.3f, 0x24ffffff);
            int color = over ? 0xffffffff : 0xdcffffff;
            switch (i) {
                case 0:
                    if (t.isLocal()) break;
                    MusicIcons.heart(ccx, cy, 8.2f, liked, liked ? LIKE_COLOR : color);
                    break;
                case 1:
                    MusicIcons.skip(ccx, cy, 6.6f, false, color);
                    break;
                case 2:
                    if (m.isWaiting()) MusicIcons.spinner(ccx, cy, 3.6f, System.nanoTime() / 1e9f, color);
                    else if (m.isPaused()) MusicIcons.play(ccx + 0.3f, cy, 7.6f, color);
                    else MusicIcons.pause(ccx, cy, 7.2f, color);
                    break;
                default:
                    MusicIcons.skip(ccx, cy, 6.6f, true, color);
                    break;
            }
        }
        Draw.popAlpha();
    }

    private void drawSeekBar(MusicService m, float x0, float x1, float y, float fraction, long duration, float alpha) {
        SmoothFont small = SmoothFont.regular(5.5f);
        long shownMs = this.dragging ? (long) (this.dragFraction * duration) : m.positionMs();
        String time = MusicText.time(shownMs) + " / " + MusicText.time(duration);
        float tw = small.width(time);
        float barX1 = x1 - tw - 5;
        float len = Math.max(10, barX1 - x0);
        set(this.hitBar, x0 - 2, y - 4, len + 4, 9);
        boolean over = this.hovering && inside(this.hitBar, this.pointerX, this.pointerY) || this.dragging;
        float bh = over ? 3 : 2.2f;
        Draw.pushAlpha(alpha);
        Draw.rect(x0, y - bh / 2, len, bh, bh / 2, 0x2effffff);
        Draw.rect(x0, y - bh / 2, Math.max(bh, len * fraction), bh, bh / 2, Draw.mix(0xffffffff, 0xff000000 | this.glowColor(), 0.45f));
        if (over) Draw.circle(x0 + len * fraction, y, 3.1f, 0xffffffff);
        small.draw(time, barX1 + 5, small.centerY(y - 4, 8), GlassTheme.FAINT);
        Draw.popAlpha();
    }

    private void drawVolume(MusicService m, float x, float y, float maxW) {
        SmoothFont sub = SmoothFont.regular(6.5f);
        int v = m.settings().volume();
        String label = "音量 " + v + "%";
        float lw = sub.draw(label, x, y, GlassTheme.TEXT) - x;
        float bx = x + lw + 5, bw = Math.max(10, maxW - lw - 5);
        float by = y + sub.height() / 2 - 1;
        Draw.rect(bx, by, bw, 2, 1, 0x2effffff);
        Draw.rect(bx, by, Math.max(2, bw * v / 100f), 2, 1, 0xffffffff);
    }

    private String subtitle(Track t) {
        MusicService m = MusicService.get();
        if (!m.status().isEmpty() && (m.isWaiting() || !m.isPlaying())) return m.status();
        if (m.isWaiting()) return "缓冲中…";
        String s = t.artistText();
        if (m.isTrial()) s += " · 试听";
        return s;
    }

    private int glowColor() {
        return Math.round(this.glowR * 255) << 16 | Math.round(this.glowG * 255) << 8 | Math.round(this.glowB * 255);
    }

    // ------------------------------------------------------------ pointer

    @Override
    protected void hover(float ux, float uy, boolean inside) {
        if (this.hovering && !inside) this.hoverLeft = System.nanoTime();
        this.hovering = inside;
        this.pointerX = ux;
        this.pointerY = uy;
        if (this.dragging && this.hitBar[2] > 0) {
            this.dragFraction = Draw.clamp01((ux - this.hitBar[0] - 2) / Math.max(1, this.hitBar[2] - 4));
        }
    }

    @Override
    protected boolean mouseClicked(float ux, float uy, int button) {
        MusicService m = MusicService.get();
        Track t = m.current();
        if (button == 1) {
            this.openScreen();
            return true;
        }
        if (button != 0 || t == null) return true;
        if (inside(this.hitBar, ux, uy) && m.durationMs() > 0) {
            this.dragging = true;
            this.dragFraction = Draw.clamp01((ux - this.hitBar[0] - 2) / Math.max(1, this.hitBar[2] - 4));
        } else if (inside(this.hitPlay, ux, uy) || inside(this.hitCover, ux, uy)) {
            m.togglePause();
            this.kick(2f);
        } else if (inside(this.hitPrev, ux, uy)) {
            m.previous();
        } else if (inside(this.hitNext, ux, uy)) {
            m.next();
        } else if (inside(this.hitLike, ux, uy)) {
            m.toggleLike(t);
            this.kick(1.5f);
        } else if (inside(this.hitTitle, ux, uy)) {
            this.openScreen();
            return true;
        } else {
            return true;
        }
        UiSound.CLICK.play();
        return true;
    }

    @Override
    protected boolean mouseScrolled(float ux, float uy, int delta) {
        MusicService m = MusicService.get();
        m.changeVolume(delta > 0 ? 5 : -5);
        this.volumeShown = System.nanoTime();
        return true;
    }

    private void openScreen() {
        mc.displayGuiScreen(new MusicScreen());
    }

    private static void set(float[] r, float x, float y, float w, float h) {
        r[0] = x;
        r[1] = y;
        r[2] = w;
        r[3] = h;
    }

    private static boolean inside(float[] r, float x, float y) {
        return r[2] > 0 && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }
}
