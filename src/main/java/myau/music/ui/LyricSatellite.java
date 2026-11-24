package myau.music.ui;

import myau.module.modules.MusicHud;
import myau.music.MusicService;
import myau.music.model.Lyrics;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.Draw;
import myau.ui.glass.Island;
import myau.ui.glass.SmoothFont;

/**
 * The line being sung, in a capsule under the Dynamic Island. With NetEase's word-timed lyrics
 * each word lights up as it is sung (a dim copy of the line under a bright copy clipped to the
 * sung width); plain LRC lines simply show. A new line slides in while the old one drifts up, and
 * the capsule's width follows the text on springs. Long lines scroll to keep the sung part in
 * view. It slips back into the island during instrumental breaks and while paused.
 */
public final class LyricSatellite extends Island.Satellite {
    private static final float H = 22, H_TRANSLATED = 33, PAD = 12, MAX_TEXT = 240;
    /** Show a line a touch early, so it is there when the singing starts. */
    private static final long LEAD_MS = 120;
    /** Keep a finished line up this long when the next one is not far. */
    private static final long HOLD_MS = 1800;

    private final MusicHud hud;
    private Lyrics.Line line, previous;
    private Lyrics source;
    private final Anim.Smooth swap = new Anim.Smooth(1, 6.5f);
    private float[] wordEnds = new float[0];
    private Lyrics.Line measured;
    /** How far the current line has scrolled; the outgoing line keeps where it was. */
    private float scroll, previousScroll;
    private long timeMs;
    /** Characters of the song not drawn yet, rasterised a few per frame so a new line never waits on them. */
    private String warm = "";
    private int warmAt;
    private static final int WARM_PER_FRAME = 24;

    public LyricSatellite(MusicHud hud) {
        this.hud = hud;
    }

    private static SmoothFont font() {
        return SmoothFont.bold(8.5f);
    }

    private static SmoothFont small() {
        return SmoothFont.regular(6.5f);
    }

    @Override
    protected boolean active() {
        if (!this.hud.isEnabled() || this.hud.lyrics.getValue() == MusicHud.LYRICS_OFF || !MusicService.running()) return false;
        MusicService m = MusicService.get();
        if (m.current() == null || !m.isActive() || m.isPaused()) return false;
        this.update(m);
        return this.line != null;
    }

    /** Picks the line for this frame (active() is called once per frame, before drawing). */
    private void update(MusicService m) {
        Lyrics lyrics = m.lyrics();
        if (lyrics != this.source) {
            this.source = lyrics;
            this.line = this.previous = null;
            this.warm = glyphsOf(lyrics);
            this.warmAt = 0;
        }
        this.warmUp();
        this.timeMs = m.lyricTimeMs();
        Lyrics.Line now = null;
        int i = lyrics.indexAt(this.timeMs + LEAD_MS);
        if (i >= 0) {
            Lyrics.Line candidate = lyrics.lines.get(i);
            long end = candidate.startMs + Math.max(candidate.durationMs, 1);
            if (this.timeMs <= end + HOLD_MS) now = candidate;
        }
        if (now != this.line) {
            if (now != null && this.line != null) {
                this.previous = this.line;
                this.swap.snap(0);
                this.kick(0.6f);
            } else {
                this.previous = null;
                this.swap.snap(1);
            }
            this.line = now;
            this.previousScroll = this.scroll;
            this.scroll = 0;
        }
    }

    /** Every distinct character in the lyrics and translations, once. */
    private static String glyphsOf(Lyrics lyrics) {
        StringBuilder out = new StringBuilder();
        java.util.Set<Character> seen = new java.util.HashSet<>();
        for (Lyrics.Line l : lyrics.lines) {
            for (String text : new String[]{l.text, l.translation}) {
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c >= 127 && seen.add(c)) out.append(c); // ASCII is made with the font
                }
            }
        }
        return out.toString();
    }

    /** Measuring a string makes the font rasterise any glyph it lacks: do a few now. */
    private void warmUp() {
        if (this.warmAt >= this.warm.length()) return;
        int end = Math.min(this.warm.length(), this.warmAt + WARM_PER_FRAME);
        String part = this.warm.substring(this.warmAt, end);
        font().width(part);
        if (this.hud.translation.getValue()) small().width(part);
        this.warmAt = end;
    }

    private boolean translated() {
        return this.hud.translation.getValue() && this.line != null && !this.line.translation.isEmpty();
    }

    @Override
    protected int side() {
        return Island.Satellite.BELOW;
    }

    @Override
    protected float gap() {
        return 5;
    }

    @Override
    protected float width() {
        if (this.line == null) return 60;
        float w = font().width(this.line.text);
        if (this.translated()) w = Math.max(w, small().width(this.line.translation));
        return Math.max(60, Math.min(MAX_TEXT, w) + PAD * 2);
    }

    @Override
    protected float height() {
        return this.translated() ? H_TRANSLATED : H;
    }

    @Override
    protected void draw(float x, float y, float w, float h, float dt) {
        float s = this.swap.update(1, dt);
        if (this.previous != null && s < 0.999f) {
            Draw.pushAlpha(1 - s);
            this.drawLine(this.previous, x, y - 5 * s, w, h, false, dt);
            Draw.popAlpha();
        }
        if (this.line != null) {
            Draw.pushAlpha(Anim.outCubic(s));
            this.drawLine(this.line, x, y + 5 * (1 - s), w, h, true, dt);
            Draw.popAlpha();
        }
    }

    private void drawLine(Lyrics.Line l, float x, float y, float w, float h, boolean current, float dt) {
        SmoothFont font = font();
        float tw = font.width(l.text);
        float avail = w - PAD * 2;
        boolean translated = current && this.translated();
        float ty = translated ? y + 4.5f : font.centerY(y, h);
        float fill = l.wordTimed() ? (current ? this.fillWidth(l, font) : tw) : tw;

        float textX;
        if (tw <= avail) {
            textX = x + (w - tw) / 2;
        } else {
            if (current) {
                float target = Math.max(0, Math.min(tw - avail, fill - avail * 0.55f));
                this.scroll += (target - this.scroll) * (1 - (float) Math.exp(-dt * 6));
            }
            textX = x + PAD - (current ? this.scroll : this.previousScroll);
        }

        if (l.wordTimed() && fill < tw) {
            font.draw(l.text, textX, ty, 0x70ffffff);
            if (fill > 0.01f) {
                Draw.pushClip(textX, y, fill, h);
                font.draw(l.text, textX, ty, GlassTheme.TEXT);
                Draw.popClip();
            }
        } else {
            font.draw(l.text, textX, ty, GlassTheme.TEXT);
        }
        if (translated) {
            SmoothFont small = small();
            String tr = small.trim(l.translation, avail);
            small.drawCentered(tr, x + w / 2, y + 19.5f, GlassTheme.MUTED);
        }
    }

    /** Width of the sung part of a word-timed line. */
    private float fillWidth(Lyrics.Line l, SmoothFont font) {
        if (this.measured != l) {
            this.measured = l;
            this.wordEnds = new float[l.words.size()];
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < l.words.size(); i++) {
                prefix.append(l.words.get(i).text);
                this.wordEnds[i] = font.width(prefix.toString());
            }
        }
        float start = 0;
        for (int i = 0; i < l.words.size(); i++) {
            Lyrics.Word word = l.words.get(i);
            float p = word.progress(this.timeMs);
            if (p >= 1) {
                start = this.wordEnds[i];
                continue;
            }
            return start + (this.wordEnds[i] - start) * p;
        }
        return start;
    }
}
