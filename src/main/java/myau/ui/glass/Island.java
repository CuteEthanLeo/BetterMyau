package myau.ui.glass;

import myau.ui.ClickGui;
import myau.ui.GlassTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.item.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The capsule at the top of the screen. At rest it shows FPS, time and ping; when a module
 * reports something (block count, break progress, a timer, a chest being looted) it grows to
 * show that, and one-off events (a module toggled, a notification) briefly take it over. With
 * several things going on, the island grows downwards: the first fills the top, every other
 * activity gets a compact row underneath. Module switches stack the same way: flipping several
 * modules at once shows one row with its own switch per module.
 * <p>
 * Modules call {@link #live} every frame while they have something to show and simply stop
 * calling when they are done; {@link #event} queues a one-off and {@link #toggle} reports a
 * module switch, which never waits in line. HUD draws it last in the frame, so everything
 * submitted during the frame is in.
 */
public final class Island {
    private Island() { }

    // Priorities for live activities: the highest one owns the island.
    public static final int P_INFO = 30, P_COMBAT = 40, P_COUNTER = 50, P_TIMER = 55, P_PROGRESS = 60, P_ALERT = 70;
    /** How many item icons a live activity can show. */
    public static final int MAX_ICONS = 7;
    /** How many module switches the island shows at once. */
    private static final int MAX_TOGGLES = 4;
    private static final float TOGGLE_ROW = 16, TOGGLE_PAD = 4.5f;
    private static final int STYLE_PLAIN = 0, STYLE_SCAFFOLD = 1;
    /** Activities shown as compact rows under the main face. */
    private static final int MAX_ROWS = 3;
    private static final float ROW_H = 15;

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long STALE_NANOS = 350_000_000L;
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm");
    private static final float TOP = 6, ICON = 16, ICON_STEP = 18;

    private static final class Live {
        final String key;
        final Face face;
        String text;
        int priority, accent;
        float progress;
        long seen, since;
        final ItemStack[] icons = new ItemStack[MAX_ICONS];
        int iconCount, iconSerial;
        final Anim.Smooth show = new Anim.Smooth(0, 14);
        final Anim.Spring iconPop = Anim.spring(1, 0.3f, 0.6f);

        // Scaffold face (style == STYLE_SCAFFOLD), see scaffold(...)
        int style = STYLE_PLAIN;
        int count, placeSerial;
        float bps, peak, flash;
        boolean towering, moving;
        /** Build direction relative to the camera: 0 = ahead, 90 = right, -90 = left, 180 = behind. */
        float direction;
        ItemStack held;
        final Anim.Spring angle = Anim.spring(0, 0.32f, 0.78f);
        final Anim.Smooth towerMix = new Anim.Smooth(0, 10);
        /** As a compact row under the main face. */
        final Anim.Smooth rowShow = new Anim.Smooth(0, 14);
        boolean wantRow;
        final Anim.Smooth rolled = new Anim.Smooth(0, 13);
        final Anim.Smooth bpsShown = new Anim.Smooth(0, 6);
        final Anim.Smooth arrowShow = new Anim.Smooth(0, 10);
        final Anim.Smooth fuel = new Anim.Smooth(0, 9);
        final Anim.Spring countPop = Anim.spring(0, 0.28f, 0.38f);
        final Anim.Spring heldPop = Anim.spring(1, 0.3f, 0.5f);
        final Anim.Spring shake = Anim.spring(0, 0.22f, 0.18f);

        Live(String key) {
            this.key = key;
            this.face = new Face(this, null);
        }
    }

    private static final class Event {
        final String title;
        String message;
        int accent;
        final float seconds;
        /** A module switch: shown with a sliding switch, and it never queues. */
        final boolean toggle;
        boolean on;
        final long created = System.currentTimeMillis();
        final Face face;
        final Anim.Spring knob;
        /** Stacked switches: how long the row has been up, whether it is on its way out, and its height. */
        float age;
        boolean leaving;
        final Anim.Smooth show = new Anim.Smooth(0, 16);

        Event(String title, String message, int accent, float seconds, boolean toggle, boolean on) {
            this.title = title;
            this.message = message == null ? "" : message;
            this.accent = accent;
            this.seconds = seconds;
            this.toggle = toggle;
            this.on = on;
            this.face = new Face(null, this);
            // Starts on the other side, so the switch is seen flipping.
            this.knob = Anim.spring(on ? 0 : 1, 0.24f, 0.7f);
        }
    }

    /** What the main island is showing: the idle readout, a live activity or an event. */
    private static final class Face {
        final Live live;
        final Event event;

        Face(Live live, Event event) {
            this.live = live;
            this.event = event;
        }
    }

    private static final LinkedHashMap<String, Live> lives = new LinkedHashMap<>();
    private static final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger queued = new AtomicInteger();
    private static final List<Live> active = new ArrayList<>();
    /** Module switches on the island, oldest first. */
    private static final List<Event> toggles = new ArrayList<>();
    /** The current notification; it waits (does not age) while switches are on the island. */
    private static Event current;
    private static float currentAge;

    private static final Face IDLE = new Face(null, null);
    /** The player list, while its key is held (see {@link IslandTab}). */
    private static final Face TAB = new Face(null, null);
    /** One or more module switches, one row each. */
    private static final Face TOGGLES = new Face(null, null);
    private static Face face = IDLE, previous;
    private static final Anim.Smooth swap = new Anim.Smooth(1, 18);
    private static final Anim.SpringRect body = new Anim.SpringRect(0.3f, 0.74f);
    private static final Anim.Spring jelly = Anim.spring(0, 0.3f, 0.42f);
    private static final Anim.Spring bossShift = Anim.spring(0, 0.4f, 0.85f);
    private static final Anim.Smooth lit = new Anim.Smooth(0, 9);
    private static float litR = 1, litG = 1, litB = 1;
    /** Activities shown as rows under the main face, in the order they joined. */
    private static final List<Live> rows = new ArrayList<>();
    private static float uiScale = 1f;

    // Player list: whether the island may take it over, whether it did this frame, and since when.
    private static boolean tabEnabled = true, tabOwned;
    private static long lastRenderNanos;
    private static float tabOpen;

    // Idle readout
    private static final float[] fpsHistory = new float[32];
    private static final float[] sparkPoints = new float[fpsHistory.length * 2];
    private static int fpsIndex, fpsCount;
    private static float fps = 60;
    private static long lastFrame, lastSample, lastIdleText, clockMinute = -1;
    private static String clockText = "", fpsLabel = "60 FPS", pingLabel = "0ms";
    private static int ping;

    // ---------------------------------------------------------------- API

    /**
     * Report an ongoing activity. Call every frame while it lasts; when the calls stop the
     * island lets go of it on its own.
     *
     * @param key      stable name of the activity ("scaffold", "bednuker", ...)
     * @param priority one of the P_ constants; the highest takes the island
     * @param text     what to show; section-sign colour codes are honoured
     * @param accent   colour of the dot and the progress bar
     * @param progress 0..1 draws a progress bar, negative draws none
     */
    public static void live(String key, int priority, String text, int accent, float progress) {
        live(key, priority, text, accent, progress, null, 0);
    }

    /**
     * {@link #live(String, int, String, int, float)} with a row of item icons under the text.
     *
     * @param icons  the items to show, oldest first; trailing nulls are ignored, at most
     *               {@link #MAX_ICONS} are drawn. The array is copied, the stacks are not.
     * @param serial changes whenever a new icon was appended, which makes the newest one pop in
     */
    public static void live(String key, int priority, String text, int accent, float progress, ItemStack[] icons, int serial) {
        Live live = lives.get(key);
        long now = System.nanoTime();
        if (live == null) {
            live = new Live(key);
            live.since = now;
            live.iconSerial = serial;
            lives.put(key, live);
        } else if (now - live.seen > STALE_NANOS) {
            live.since = now;
            live.iconSerial = serial;
        }
        live.text = text;
        live.priority = priority;
        live.accent = accent;
        live.progress = progress;
        live.seen = now;
        int count = 0;
        if (icons != null) {
            for (int i = 0; i < icons.length && count < MAX_ICONS; i++) {
                if (icons[i] == null) break;
                live.icons[count++] = icons[i];
            }
        }
        for (int i = count; i < live.iconCount; i++) live.icons[i] = null;
        live.iconCount = count;
        if (serial != live.iconSerial) {
            live.iconSerial = serial;
            live.iconPop.snap(0);
        }
    }

    /**
     * Scaffold's block counter, drawn as a face of its own (block in hand, rolling count, speed,
     * tower arrow, fuel gauge). Call every frame while scaffolding, like {@link #live}.
     *
     * @param count       blocks left in the hotbar
     * @param held        the block being placed, or null
     * @param bps         blocks placed per second
     * @param towering    whether scaffold is towering right now
     * @param placeSerial goes up by one for every block placed
     * @param direction   where the player is building towards, relative to the camera in degrees
     *                    (0 ahead, 90 right, -90 left, 180 behind); NaN while standing still
     */
    public static void scaffold(int count, ItemStack held, float bps, boolean towering, int placeSerial, float direction) {
        String text = String.format("%s%d §7block%s", count > 0 ? "§f" : "§c", count, count != 1 ? "s" : "");
        int accent = count <= 0 ? GlassTheme.BAD : count <= 16 ? GlassTheme.WARN : GlassTheme.accent();
        live("scaffold", P_COUNTER, text, accent, -1);
        Live l = lives.get("scaffold");
        boolean restart = l.style != STYLE_SCAFFOLD || l.since == l.seen;
        l.style = STYLE_SCAFFOLD;
        if (restart) {
            l.rolled.snap(count);
            l.bpsShown.snap(bps);
            boolean moving = !Float.isNaN(direction);
            l.arrowShow.snap(moving || towering ? 1 : 0);
            l.towerMix.snap(towering ? 1 : 0);
            l.angle.snap(moving ? direction : 0);
            l.peak = Math.max(64, count);
            l.fuel.snap(Math.min(1, count / l.peak));
            l.countPop.snap(0);
            l.shake.snap(0);
            l.heldPop.snap(1);
            l.flash = 0;
        } else {
            if (count != l.count) l.countPop.kick(count < l.count ? 9f : 12f);
            if (count <= 0 && l.count > 0) l.shake.kick(28f);
            if (placeSerial != l.placeSerial) {
                l.flash = 1;
                if (face == l.face) jelly.kick(3f);
            }
            if (held != null && (l.held == null || held.getItem() != l.held.getItem() || held.getMetadata() != l.held.getMetadata())) {
                l.heldPop.snap(0.3f);
            }
        }
        l.count = count;
        l.peak = Math.max(l.peak, count);
        l.held = held;
        l.bps = bps;
        l.towering = towering;
        l.moving = !Float.isNaN(direction);
        if (l.moving) l.direction = direction;
        l.placeSerial = placeSerial;
    }

    /** Queue a one-off event (a notification) to take over the island briefly. */
    public static void event(String title, String message, int accent, float seconds) {
        enqueue(new Event(title, message, accent, seconds, false, false));
    }

    /**
     * A module was switched on or off. Shown at once, as a row of its own under any switches
     * already on the island; flipping the same module again just flips the switch that is there.
     */
    public static void toggle(String module, boolean enabled) {
        enqueue(new Event(module, enabled ? "Enabled" : "Disabled", enabled ? GlassTheme.OK : GlassTheme.BAD, 1.1f, true, enabled));
    }

    private static void enqueue(Event event) {
        if (event.title == null) return;
        if (mc.currentScreen instanceof ClickGui) return; // the Click GUI already shows the change
        // Only the latest state of a title matters.
        for (Iterator<Event> it = queue.iterator(); it.hasNext(); ) {
            if (it.next().title.equals(event.title)) {
                it.remove();
                queued.decrementAndGet();
            }
        }
        while (queued.get() >= 6 && queue.poll() != null) queued.decrementAndGet();
        queue.add(event);
        queued.incrementAndGet();
    }

    /** Size of the island relative to its designed size (1 = 100%). */
    public static void scale(float factor) {
        uiScale = Math.max(0.5f, Math.min(2.5f, factor));
    }

    /** Whether holding the player list key turns the island into the player list. */
    public static void tabList(boolean enabled) {
        tabEnabled = enabled;
    }

    /**
     * True while the island is showing the player list, so the vanilla one (drawn later in the
     * same frame, right over the island) must not be drawn. Expires on its own when the island
     * stops rendering (HUD switched off), so the vanilla list can never get stuck hidden.
     */
    public static boolean ownsTab() {
        return tabOwned && System.nanoTime() - lastRenderNanos < 200_000_000L;
    }

    // ---------------------------------------------------------------- satellites

    /**
     * A small capsule that floats beside the island (left, right or below) and follows it on
     * springs. It grows out of the island's edge when it appears and slips back under it when
     * it goes. Register once with {@link #addSatellite}; the island draws it every frame.
     */
    public abstract static class Satellite {
        public static final int LEFT = 0, RIGHT = 1, BELOW = 2;
        final Anim.SpringRect body = new Anim.SpringRect(0.32f, 0.7f);
        final Anim.Smooth show = new Anim.Smooth(0, 11);
        final Anim.Spring jelly = Anim.spring(0, 0.3f, 0.42f);
        /** The liquid neck to the island: whether it is attached, and how much of it is left. */
        boolean bridged;
        final Anim.Smooth link = new Anim.Smooth(0, 20);

        /** Whether it wants to be on screen. */
        protected abstract boolean active();

        /** {@link #LEFT}, {@link #RIGHT} or {@link #BELOW}. */
        protected abstract int side();

        /** Space between the island and the capsule, in island units. */
        protected abstract float gap();

        protected abstract float width();

        protected abstract float height();

        /** Draws the content into the capsule at (x, y, w, h), island units. */
        protected abstract void draw(float x, float y, float w, float h, float dt);

        /**
         * Drawn just before the glass capsule at the same place, under it: a glow or a shadow.
         * {@code alpha} is how much of the capsule is out (it fades while melting in).
         */
        protected void drawBehind(float x, float y, float w, float h, float alpha, float dt) {
        }

        /**
         * The pointer (only while chat is open) is over the capsule at (ux, uy), in the island
         * units {@link #draw} uses, or has left it ({@code inside} false). Called every frame.
         */
        protected void hover(float ux, float uy, boolean inside) {
        }

        /** A click on the capsule at (ux, uy), island units. True when it was used. */
        protected boolean mouseClicked(float ux, float uy, int button) {
            return false;
        }

        /** The wheel over the capsule ({@code delta} > 0 is away from the user). True when used. */
        protected boolean mouseScrolled(float ux, float uy, int delta) {
            return false;
        }

        /** A little squash and stretch, like the island's own. */
        public void kick(float impulse) {
            jelly.kick(impulse);
        }

        /** How far it is out (0 gone or melted into the island, 1 fully out). */
        public float shown() {
            return this.clickable ? this.show.value : 0;
        }

        // Where it was last drawn: island units and screen (GUI) units, for the pointer.
        final float[] units = new float[4], screen = new float[4];
        boolean clickable;
        boolean hovered;

        boolean contains(float mx, float my) {
            return this.clickable && mx >= this.screen[0] && mx < this.screen[0] + this.screen[2]
                    && my >= this.screen[1] && my < this.screen[1] + this.screen[3];
        }

        float unitX(float mx) {
            return this.units[0] + (mx - this.screen[0]) / Math.max(0.001f, this.screen[2]) * this.units[2];
        }

        float unitY(float my) {
            return this.units[1] + (my - this.screen[1]) / Math.max(0.001f, this.screen[3]) * this.units[3];
        }
    }

    /** Pointer position in GUI units while chat is open (the only time the island is clickable). */
    private static boolean pointerOn;
    private static float pointerX, pointerY;

    /** A click while chat is open: true when a satellite took it. Main thread. */
    public static boolean click(float mx, float my, int button) {
        for (int i = satellites.size() - 1; i >= 0; i--) {
            Satellite s = satellites.get(i);
            if (!s.contains(mx, my)) continue;
            try {
                if (s.mouseClicked(s.unitX(mx), s.unitY(my), button)) return true;
            } catch (RuntimeException e) {
                return false;
            }
            return true; // swallowed anyway: chat must not get a click aimed at a capsule
        }
        return false;
    }

    /** The wheel while chat is open: true when a satellite took it. Main thread. */
    public static boolean scroll(float mx, float my, int delta) {
        for (int i = satellites.size() - 1; i >= 0; i--) {
            Satellite s = satellites.get(i);
            if (!s.contains(mx, my)) continue;
            try {
                return s.mouseScrolled(s.unitX(mx), s.unitY(my), delta);
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    /** True when the pointer is over a satellite (for the chat screen's cursor feedback). */
    public static boolean pointerOverSatellite(float mx, float my) {
        for (Satellite s : satellites) if (s.contains(mx, my)) return true;
        return false;
    }

    // Ambient light: something (the music player) tints the idle island's halo.
    private static int ambientColor;
    private static float ambientStrength;
    private static long ambientNanos;
    private static final Anim.Smooth ambientShown = new Anim.Smooth(0, 6);

    /**
     * Call every frame to light the idle island's halo with {@code color} at {@code strength}
     * (0..1); stops by itself a moment after the calls stop. Activities on the island win.
     */
    public static void ambient(int color, float strength) {
        ambientColor = color & 0xffffff;
        ambientStrength = Draw.clamp01(strength);
        ambientNanos = System.nanoTime();
    }

    private static final List<Satellite> satellites = new ArrayList<>();

    public static void addSatellite(Satellite satellite) {
        if (!satellites.contains(satellite)) satellites.add(satellite);
    }

    /**
     * Lays out and draws the satellites around the island body (bx, by, bw, bh, radius br),
     * island units. With {@code absorb} they melt back into the island (the island is busy with
     * something big); otherwise they split off again. While a satellite comes out, goes in, or
     * the island's change of shape pulls it close, a liquid neck joins the two; it stretches
     * and snaps once they are apart.
     */
    private static void drawSatellites(float bx, float by, float bw, float bh, float br, boolean absorb, float dt) {
        if (satellites.isEmpty()) return;
        float left = bx, right = bx + bw, below = by + bh, cx = bx + bw / 2;
        for (Satellite s : satellites) {
            boolean enabled;
            try {
                enabled = s.active();
            } catch (RuntimeException e) {
                enabled = false;
            }
            // Absorbed: still there, just inside the island; it stays visible while it merges.
            boolean on = enabled && !absorb;
            float before = s.show.value;
            float shown = s.show.update(enabled, dt);
            if (!enabled && shown < 0.01f) {
                s.bridged = false;
                s.link.snap(0);
                s.clickable = false;
                if (s.hovered) {
                    s.hovered = false;
                    try {
                        s.hover(0, 0, false);
                    } catch (RuntimeException ignored) {
                    }
                }
                continue;
            }
            float w = s.width(), h = s.height(), gap = s.gap();
            int side = s.side();
            // Where it sits, and where it hides: a small blob just inside the island's edge.
            float tx, ty, hx, hy, hw = Math.min(w, 24), hh = Math.min(h, Math.max(14, bh));
            if (side == Satellite.LEFT) {
                tx = left - gap - w; ty = by;
                hx = bx + 2; hy = by;
                left = tx;
            } else if (side == Satellite.RIGHT) {
                tx = right + gap; ty = by;
                hx = bx + bw - hw - 2; hy = by;
                right = tx + w;
            } else {
                tx = cx - w / 2; ty = below + gap;
                hx = cx - hw / 2; hy = by + bh - hh;
                below = ty + h;
            }
            // Coming back after being gone: start from inside the island, wherever it is now.
            if (before < 0.01f || !s.body.placed()) s.body.snap(hx, hy, hw, hh, hh / 2);
            if (on) s.body.to(tx, ty, w, h, Math.min(14, h / 2), dt);
            else s.body.to(hx, hy, hw, hh, hh / 2, dt);

            float x = s.body.x.value, y = s.body.y.value;
            float sw = Math.max(8, s.body.w.value), sh = Math.max(8, s.body.h.value);
            float r = Math.max(2, Math.min(s.body.r.value, sh / 2));
            // Under the island it is out of sight: fade it with how far it has sunk in.
            float edge = side == Satellite.LEFT ? bx - (x + sw) : side == Satellite.RIGHT ? x - (bx + bw) : y - (by + bh);
            float inside = bw > 0 ? Draw.clamp01(1 + edge / 12) : 1;
            if (bw > 0) updateNeck(s, side, gap, bx, by, bw, bh, br, x, y, sw, sh, r, shown * inside, dt);
            float j = s.jelly.update(0, dt);
            float out = Draw.clamp01(shown * 1.4f) * inside;
            // Remember where it is for the pointer (chat open), before the squash.
            s.units[0] = x;
            s.units[1] = y;
            s.units[2] = sw;
            s.units[3] = sh;
            Draw.toScreen(x, y, sw, sh, s.screen);
            s.clickable = on && shown > 0.6f && inside > 0.6f;
            boolean over = pointerOn && s.contains(pointerX, pointerY);
            if (over || s.hovered) {
                s.hovered = over;
                try {
                    s.hover(s.unitX(pointerX), s.unitY(pointerY), over);
                } catch (RuntimeException ignored) {
                }
            }
            Draw.pushAlpha(out);
            Draw.pushTransform(x + sw / 2, y + sh / 2, 1 + 0.05f * j, 1 - 0.05f * j, 0, 0);
            try {
                s.drawBehind(x, y, sw, sh, out, dt);
            } catch (RuntimeException ignored) {
            }
            GlassTheme.panel(x, y, sw, sh, r, 0.8f);
            Draw.pushClip(x + 1, y, sw - 2, sh);
            Draw.pushAlpha(shown * shown);
            try {
                s.draw(x, y, sw, sh, dt);
            } catch (RuntimeException ignored) {
                // A broken satellite must not take the island down with it.
            }
            Draw.popAlpha();
            Draw.popClip();
            Draw.popTransform();
            Draw.popAlpha();
        }
    }

    private static final float[] neckPoints = new float[2400];
    private static int neckCount;
    private static boolean neckSwap;

    private static void updateNeck(Satellite s, int side, float gap, float bx, float by, float bw, float bh, float br,
                                   float x, float y, float sw, float sh, float r, float alpha, float dt) {
        // Distance between the facing edges, and how fast the satellite is moving away.
        float d, away;
        if (side == Satellite.LEFT) {
            d = bx - (x + sw);
            away = -s.body.x.velocity;
        } else if (side == Satellite.RIGHT) {
            d = x - (bx + bw);
            away = s.body.x.velocity;
        } else {
            d = y - (by + bh);
            away = s.body.y.velocity;
        }
        float snapAt = gap + 3;
        if (s.bridged) {
            // Snaps when stretched past its resting gap, or once things have settled.
            boolean settled = Math.abs(away) < 20 && d >= gap * 0.9f;
            if (d > snapAt || settled || alpha < 0.05f) {
                s.bridged = false;
                if (alpha > 0.5f) {
                    s.jelly.kick(3.5f);
                    jelly.kick(1.5f);
                }
            }
        } else if (d < gap * 0.5f && alpha > 0.05f) {
            s.bridged = true;
            if (d > 0) s.jelly.kick(-2f);
        }
        float link = s.link.update(s.bridged ? 1 : 0, dt);
        if (link < 0.01f || d <= 0.3f) return;

        float stretch = Draw.clamp01(1 - d / snapAt);
        neckCount = 0;
        int color = Draw.alpha(Draw.mix(GuiPalette.GLASS.tintTop, GuiPalette.GLASS.tintBottom, 0.5f), 0.82f);
        if (side == Satellite.BELOW) {
            neckSwap = true;
            float waist = Math.min(Math.min(br, r), 10) * (0.15f + 0.6f * stretch) * link;
            neck(by + bh, br, bw / 2, bx + bw / 2, y, r, sw / 2, x + sw / 2, waist);
        } else {
            neckSwap = false;
            boolean right = side == Satellite.RIGHT;
            // P is the body on the left, Q the one on the right.
            float pEdge = right ? bx + bw : x + sw, pR = right ? br : r, pHalf = right ? bh / 2 : sh / 2, pC = right ? by + bh / 2 : y + sh / 2;
            float qEdge = right ? x : bx, qR = right ? r : br, qHalf = right ? sh / 2 : bh / 2, qC = right ? y + sh / 2 : by + bh / 2;
            float waist = Math.min(Math.min(pR, qR), 10) * (0.15f + 0.6f * stretch) * link;
            neck(pEdge, pR, pHalf, pC, qEdge, qR, qHalf, qC, waist);
        }
        Draw.pushAlpha(alpha);
        Draw.quads(color, neckPoints, neckCount);
        Draw.popAlpha();
    }

    /** How far across a rounded body reaches at {@code inside} units in from its edge; negative outside it. */
    private static float cover(float inside, float r, float half) {
        if (inside <= 0) return -1;
        float k = Math.min(inside, r);
        return (half - r) + (float) Math.sqrt(Math.max(0, r * r - (r - k) * (r - k)));
    }

    /**
     * The neck between body P (ending at pEdge, along the axis) and body Q (starting at qEdge):
     * thick where it leaves each body, thinnest in the middle, and only where neither body
     * already is, so it never doubles their tint.
     */
    private static void neck(float pEdge, float pR, float pHalf, float pC, float qEdge, float qR, float qHalf, float qC, float waist) {
        float start = pEdge - pR, end = qEdge + qR, len = end - start;
        if (len <= 0) return;
        int n = Math.max(8, Math.min(140, (int) (len / 0.5f)));
        float prevU = 0, prevTa = 0, prevTb = 0, prevBa = 0, prevBb = 0;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n, u = start + len * t;
            float c = pC + (qC - pC) * (t * t * (3 - 2 * t));
            float base = pR + (qR - pR) * t;
            float half = waist + (base - waist) * (float) Math.pow(Math.abs(2 * t - 1), 1.6);
            float top = c - half, bottom = c + half;
            float ta, tb, ba, bb;
            float cov = -1, bodyC = c;
            if (u < pEdge) {
                cov = cover(pEdge - u, pR, pHalf);
                bodyC = pC;
            } else if (u > qEdge) {
                cov = cover(u - qEdge, qR, qHalf);
                bodyC = qC;
            }
            if (cov < 0) {
                ta = top; tb = c; ba = c; bb = bottom;
            } else {
                ta = top; tb = Math.max(top, Math.min(bottom, bodyC - cov));
                bb = bottom; ba = Math.min(bottom, Math.max(top, bodyC + cov));
            }
            if (i > 0 && neckCount + 16 <= neckPoints.length) {
                neckQuad(prevU, u, prevTa, prevTb, ta, tb);
                neckQuad(prevU, u, prevBa, prevBb, ba, bb);
            }
            prevU = u; prevTa = ta; prevTb = tb; prevBa = ba; prevBb = bb;
        }
    }

    private static void neckQuad(float u0, float u1, float a0, float b0, float a1, float b1) {
        if (b0 - a0 < 0.01f && b1 - a1 < 0.01f) return;
        neckPut(u0, a0);
        neckPut(u1, a1);
        neckPut(u1, b1);
        neckPut(u0, b0);
    }

    private static void neckPut(float u, float v) {
        if (neckSwap) {
            neckPoints[neckCount++] = v;
            neckPoints[neckCount++] = u;
        } else {
            neckPoints[neckCount++] = u;
            neckPoints[neckCount++] = v;
        }
    }

    // ---------------------------------------------------------------- frame

    /**
     * @param islandOn       draw the island at the top of the screen
     * @param livesInIsland  the island shows live activities (otherwise only its idle readout and events)
     * @param crosshairStack stack the live activities under the crosshair
     */
    public static void render(float sw, float sh, boolean islandOn, boolean livesInIsland, boolean crosshairStack) {
        float dt = Anim.dt();
        long now = System.nanoTime();
        sampleFps(now);

        // Live activities: fade out the ones that stopped reporting, drop the ones that are gone.
        active.clear();
        for (Iterator<Live> it = lives.values().iterator(); it.hasNext(); ) {
            Live live = it.next();
            boolean alive = now - live.seen <= STALE_NANOS;
            live.show.update(alive, dt);
            if (alive) active.add(live);
            else if (live.show.value < 0.01f) it.remove();
        }
        if (active.size() > 1) {
            active.sort((a, b) -> a.priority != b.priority ? Integer.compare(b.priority, a.priority) : Long.compare(a.since, b.since));
        }

        boolean inWorld = mc.thePlayer != null && mc.theWorld != null && !mc.gameSettings.showDebugInfo;
        boolean tab = islandOn && inWorld && tabEnabled && IslandTab.wanted();
        // The capsules can be used with the mouse while chat is open.
        pointerOn = mc.currentScreen instanceof net.minecraft.client.gui.GuiChat && mc.displayWidth > 0 && mc.displayHeight > 0;
        if (pointerOn) {
            pointerX = org.lwjgl.input.Mouse.getX() * sw / mc.displayWidth;
            pointerY = sh - org.lwjgl.input.Mouse.getY() * sh / mc.displayHeight - 1;
        }
        tabOwned = tab;
        lastRenderNanos = now;

        // While the list is open, a notification on the island waits instead of running out unseen.
        advanceEvents(tab ? 0 : dt);

        Draw.useGuiScale();
        GlStateManager.disableDepth();
        if (islandOn && inWorld) drawIsland(sw, sh, livesInIsland ? active : Collections.<Live>emptyList(), tab, dt);
        if (!islandOn && inWorld && !satellites.isEmpty()) {
            // No island: the satellites gather around where it would be.
            Draw.pushTransform(sw / 2, TOP, uiScale, 0, 0);
            drawSatellites(sw / 2, TOP, 0, 0, 0, false, dt);
            Draw.popTransform();
        }
        if ((crosshairStack || !islandOn) && inWorld) drawStack(sw, sh);
        GlStateManager.enableDepth();
    }

    private static void advanceEvents(float dt) {
        // Pull every switch out of the queue, wherever it is: switches never wait behind a notification.
        for (Iterator<Event> it = queue.iterator(); it.hasNext(); ) {
            Event next = it.next();
            if (!next.toggle) continue;
            it.remove();
            queued.decrementAndGet();
            if (System.currentTimeMillis() - next.created < 4000) addToggle(next);
        }
        ageToggles(dt);

        boolean switches = hasToggles();
        if (current != null && !switches) {
            currentAge += dt;
            float limit = queued.get() > 2 ? Math.min(current.seconds, 0.9f) : current.seconds;
            if (currentAge >= limit) current = null;
        }
        Event next;
        while ((next = queue.peek()) != null) {
            // Notifications that waited too long (queued outside a world) are no longer news.
            if (System.currentTimeMillis() - next.created >= 4000) {
                take();
                continue;
            }
            if (current != null || switches) break; // a notification waits for its turn
            take();
            show(next);
        }
    }

    private static void addToggle(Event event) {
        for (Event row : toggles) {
            if (row.title.equals(event.title)) {
                // Same module flipped again: flip the switch that is already on screen.
                row.on = event.on;
                row.message = event.message;
                row.accent = event.accent;
                row.age = 0;
                row.leaving = false;
                jelly.kick(3f);
                return;
            }
        }
        if (!hasToggles() && face != TOGGLES) {
            // A fresh stack: rows left over from the last one are gone, and the first row comes
            // in with the island itself instead of growing on its own.
            toggles.clear();
            event.show.snap(1);
        } else {
            jelly.kick(2.5f);
        }
        toggles.add(event);
        int alive = 0;
        for (int i = toggles.size() - 1; i >= 0; i--) {
            Event row = toggles.get(i);
            if (!row.leaving && ++alive > MAX_TOGGLES) row.leaving = true;
        }
    }

    private static void ageToggles(float dt) {
        if (toggles.isEmpty()) return;
        // With several switches up each row stays a bit longer, so all of them can be read.
        float life = aliveToggles() >= 2 ? 1.6f : 1.1f;
        for (Event row : toggles) {
            if (!row.leaving && (row.age += dt) >= life) row.leaving = true;
        }
        if (hasToggles()) {
            // Rows leave one by one; the last one leaves with the island's face instead.
            for (Event row : toggles) row.show.update(!row.leaving, dt);
            toggles.removeIf(row -> row.leaving && row.show.value < 0.01f);
        } else if (face != TOGGLES && previous != TOGGLES) {
            toggles.clear();
        }
    }

    private static boolean hasToggles() {
        for (Event row : toggles) if (!row.leaving) return true;
        return false;
    }

    private static int aliveToggles() {
        int n = 0;
        for (Event row : toggles) if (!row.leaving) n++;
        return n;
    }

    private static void take() {
        if (queue.poll() != null) queued.decrementAndGet();
    }

    private static void show(Event event) {
        current = event;
        currentAge = 0;
    }

    private static void sampleFps(long now) {
        if (lastFrame != 0) {
            long delta = now - lastFrame;
            if (delta > 0 && delta < 1_000_000_000L) fps = fps * 0.9f + (1_000_000_000f / delta) * 0.1f;
        }
        lastFrame = now;
        if (now - lastSample >= 50_000_000L) {
            lastSample = now;
            fpsHistory[fpsIndex] = fps;
            fpsIndex = (fpsIndex + 1) % fpsHistory.length;
            if (fpsCount < fpsHistory.length) fpsCount++;
        }
        // The idle texts change a few times a second at most; no need to rebuild them per frame.
        if (now - lastIdleText >= 250_000_000L) {
            lastIdleText = now;
            fpsLabel = Math.round(fps) + " FPS";
            long minute = System.currentTimeMillis() / 60000L;
            if (minute != clockMinute) {
                clockMinute = minute;
                clockText = CLOCK.format(new Date());
            }
            int value = 0;
            try {
                if (mc.getNetHandler() != null && mc.thePlayer != null) {
                    NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
                    if (info != null) value = info.getResponseTime();
                }
            } catch (RuntimeException ignored) {
            }
            ping = value;
            pingLabel = value + "ms";
        }
    }

    // ---------------------------------------------------------------- island

    private static void drawIsland(float sw, float sh, List<Live> active, boolean tab, float dt) {
        float boss = bossShift.update(BossStatus.bossName != null && BossStatus.statusBarTime > 0 ? 22 : 0, dt);
        // Decide what the island shows; every other activity becomes a row underneath.
        Live first = active.isEmpty() ? null : active.get(0);
        Face wanted;
        if (tab) {
            wanted = TAB;
        } else if (hasToggles()) {
            wanted = TOGGLES;
        } else if (current != null) {
            wanted = current.face;
        } else if (first != null) {
            wanted = first.face;
        } else {
            wanted = IDLE;
        }
        if (wanted != face) {
            previous = face;
            face = wanted;
            swap.snap(0);
            if (wanted == TAB) tabOpen = 0;
            // The list is a big panel: a gentle wobble, not the capsule's bounce.
            jelly.kick(wanted == TAB || previous == TAB ? 2.5f : previous == IDLE && wanted != IDLE ? 7f : 4f);
        }
        if (face == TAB || previous == TAB) {
            // The list fits between the top of the screen and its bottom edge, at the island's scale.
            IslandTab.update((sw - 16) / uiScale, (sh - TOP - boss - 8) / uiScale);
            if (face == TAB) tabOpen += dt;
        }
        float mix = swap.update(1, dt);
        if (mix >= 0.999f) previous = null;
        float rowsH = updateRows(tab ? Collections.<Live>emptyList() : active, wanted.live, dt);
        float rowsK = Math.min(1, rowsH / ROW_H), rowsArea = rowsH + 4 * rowsK;

        float faceW = faceWidth(face), faceH = faceHeight(face);
        float w = Math.max(faceW, rowsWidth()), h = faceH + rowsArea;
        float radius = face == TAB ? 16 : h > 30 ? 14 : h / 2;
        body.to(sw / 2 - w / 2, TOP, w, h, radius, dt);

        float bx = body.x.value, by = body.y.value, bw = Math.max(20, body.w.value), bh = Math.max(14, body.h.value);
        float br = Math.max(2, Math.min(body.r.value, bh / 2));
        float j = jelly.update(0, dt);
        float cx = bx + bw / 2, cy = by + bh / 2;
        // The faces keep to the top of the island; the rows take the rest.
        float fh = Math.max(14, bh - rowsArea), fcy = by + fh / 2;

        // Everything below is laid out at the designed size and scaled around the top centre,
        // so a larger island grows downwards and stays centred.
        Draw.pushTransform(sw / 2, TOP, uiScale, 0, boss);

        // Satellites first, so one coming out of (or going back into) the island passes under it.
        // A notification, module switches or the player list take the whole stage: the
        // satellites melt into the island meanwhile and split off again afterwards.
        drawSatellites(bx, by, bw, bh, br, tab || face == TOGGLES || face.event != null, dt);

        // A soft halo in the colour of whatever the island is showing.
        int accent = faceAccent(face);
        float glow = lit.update(face != IDLE ? 1 : 0, dt);
        if (face != IDLE) {
            float k = Anim.reduced() ? 1 : 1 - (float) Math.exp(-10 * dt);
            litR += ((accent >> 16 & 255) / 255f - litR) * k;
            litG += ((accent >> 8 & 255) / 255f - litG) * k;
            litB += ((accent & 255) / 255f - litB) * k;
        }
        if (glow > 0.01f) {
            int halo = (Math.round(0.2f * glow * 255) << 24) | (Math.round(litR * 255) << 16) | (Math.round(litG * 255) << 8) | Math.round(litB * 255);
            Draw.glow(bx + 4, by + 3, bw - 8, bh - 2, br, 18, halo);
        }
        // Idle: whoever asked for it (the music player) may tint the halo; activities take over.
        boolean ambientFresh = System.nanoTime() - ambientNanos < 200_000_000L;
        float amb = ambientShown.update(face == IDLE && ambientFresh ? ambientStrength : 0, dt);
        if (amb > 0.01f) Draw.glow(bx + 4, by + 3, bw - 8, bh - 2, br, 18, Draw.alpha(ambientColor, 0.24f * amb));

        Draw.pushTransform(cx, cy, 1 + 0.045f * j, 1 - 0.045f * j, 0, 0);
        GlassTheme.panel(bx, by, bw, bh, br, 0.9f);
        Draw.pushClip(bx + 2, by, bw - 4, bh);
        if (previous != null) {
            // Between the capsule and the player list the two faces barely overlap in size, so
            // the old one gets out of the way quickly instead of showing through the new one.
            float out = 1 - mix;
            Draw.pushAlpha(previous == TAB || face == TAB ? out * out * out : out);
            Draw.pushTransform(cx, fcy, 1 - 0.04f * mix, 0, -5 * mix);
            drawFace(previous, cx, by, fh, faceWidth(previous));
            Draw.popTransform();
            Draw.popAlpha();
        }
        Draw.pushAlpha(previous == null ? 1 : mix);
        Draw.pushTransform(cx, fcy, 0.96f + 0.04f * mix, 0, 4 * (1 - mix));
        drawFace(face, cx, by, fh, faceW);
        Draw.popTransform();
        Draw.popAlpha();
        if (rowsH > 0.01f) drawRows(bx, by + bh - rowsArea, bw, rowsK);
        Draw.popClip();
        Draw.popTransform();

        Draw.popTransform();
    }

    /**
     * Decides which activities are rows (everything active except the one on the main face, at
     * most {@link #MAX_ROWS}), animates them in and out, and returns the rows' current height.
     */
    private static float updateRows(List<Live> active, Live main, float dt) {
        for (Live l : rows) l.wantRow = false;
        int n = 0;
        for (Live l : active) {
            if (l == main) continue;
            if (n++ >= MAX_ROWS) break;
            l.wantRow = true;
            if (!rows.contains(l)) {
                rows.add(l);
                l.rowShow.snap(0);
            }
        }
        float height = 0;
        for (Live l : rows) height += ROW_H * Draw.clamp01(l.rowShow.update(l.wantRow, dt));
        rows.removeIf(l -> !l.wantRow && l.rowShow.value < 0.01f);
        return height;
    }

    private static float rowsWidth() {
        float width = 0;
        for (Live l : rows) width = Math.max(width, rowWidth(l) * Draw.clamp01(l.rowShow.value));
        return width;
    }

    private static float rowWidth(Live l) {
        float text = SmoothFont.regular(8.5f).width(l.text);
        return Math.min(300, 28 + text + (l.progress >= 0 ? 8 + 34 : 0) + 18);
    }

    /** The compact rows: a coloured dot, the activity's text and, if it has one, a small progress bar. */
    private static void drawRows(float x, float y, float w, float k) {
        Draw.rect(x + 16, y + 0.2f, w - 32, 0.6f, 0, Draw.fade(0x38ffffff, k));
        float rowY = y + 1.5f;
        SmoothFont font = SmoothFont.regular(8.5f);
        for (Live l : rows) {
            float s = Draw.clamp01(l.rowShow.value);
            if (s < 0.01f) continue;
            float ry = rowY - 3 * (1 - s), cy = ry + ROW_H / 2;
            Draw.pushAlpha(s * s);
            Draw.glow(x + 20 - 2, cy - 2, 4, 4, 2, 5, Draw.alpha(l.accent, 0.55f));
            Draw.circle(x + 20, cy, 2, l.accent);
            boolean bar = l.progress >= 0;
            float textMax = w - 46 - (bar ? 42 : 0);
            font.draw(font.trim(l.text, textMax), x + 28, font.centerY(ry, ROW_H) - 0.5f, GlassTheme.TEXT);
            if (bar) {
                float bw = 34, bx = x + w - 18 - bw, thick = 1.8f, by = cy - thick / 2;
                Draw.rect(bx, by, bw, thick, thick / 2, 0x30ffffff);
                float fill = bw * Draw.clamp01(l.progress);
                if (fill > 0.5f) {
                    Draw.glow(bx, by, fill, thick, thick / 2, 4, Draw.alpha(l.accent, 0.45f));
                    Draw.rect(bx, by, fill, thick, thick / 2, Draw.mix(l.accent, 0xffffffff, 0.2f));
                }
            }
            Draw.popAlpha();
            rowY += ROW_H * s;
        }
    }

    private static int faceAccent(Face f) {
        if (f == TAB) return GlassTheme.accent();
        if (f == TOGGLES) {
            Event newest = null;
            for (Event row : toggles) if (!row.leaving || newest == null) newest = row;
            return newest == null ? GlassTheme.accent() : toggleColor(newest);
        }
        if (f.live != null && f.live.style == STYLE_SCAFFOLD) return scaffoldColor(f.live);
        if (f.event != null) return f.event.toggle ? toggleColor(f.event) : f.event.accent;
        return f.live != null ? f.live.accent : 0xffffffff;
    }

    private static float faceHeight(Face f) {
        if (f == TAB) return IslandTab.height();
        if (f == TOGGLES) {
            float rows = 0;
            for (Event row : toggles) rows += Draw.clamp01(row.show.value);
            return TOGGLE_PAD * 2 + TOGGLE_ROW * Math.max(rows, 0.01f);
        }
        if (f.event != null) return !f.event.toggle && twoLines(f.event) ? 36 : 25;
        if (f.live != null) return f.live.style == STYLE_SCAFFOLD ? 30 : f.live.iconCount > 0 ? 44 : 25;
        return 22;
    }

    private static float faceWidth(Face f) {
        if (f == TAB) return IslandTab.width();
        if (f == TOGGLES) {
            // Each row pulls the island as wide as it needs while it is in, and lets go as it leaves.
            float width = 150;
            for (Event row : toggles) width = Math.max(width, 150 + (toggleRowWidth(row) - 150) * Draw.clamp01(row.show.value));
            return width;
        }
        if (f.event != null) {
            SmoothFont title = SmoothFont.bold(9.5f), body = SmoothFont.regular(8.5f);
            if (f.event.toggle) return toggleRowWidth(f.event);
            if (twoLines(f.event)) {
                return Math.max(170, Math.min(300, Math.max(title.width(f.event.title), body.width(f.event.message)) + 50));
            }
            float message = f.event.message.isEmpty() ? 0 : body.width(f.event.message) + 8;
            return Math.max(150, title.width(f.event.title) + message + 50);
        }
        if (f.live != null) {
            if (f.live.style == STYLE_SCAFFOLD) return scaffoldWidth(f.live);
            float width = SmoothFont.regular(9.5f).width(f.live.text) + 52;
            width = Math.max(f.live.progress >= 0 ? 150 : 132, Math.min(300, width));
            if (f.live.iconCount > 0) width = Math.max(width, f.live.iconCount * ICON_STEP + 40);
            return width;
        }
        SmoothFont bold = SmoothFont.bold(9), regular = SmoothFont.regular(8.5f);
        return 18 + bold.width(fpsLabel) + 8 + 44 + 12 + regular.width(clockText) + 12 + regular.width(pingLabel) + 18;
    }

    private static float toggleRowWidth(Event e) {
        return Math.max(150, 19 + 18 + 9 + SmoothFont.bold(9.5f).width(e.title) + 8 + SmoothFont.regular(8.5f).width(e.message) + 21);
    }

    private static int toggleColor(Event e) {
        return Draw.mix(GlassTheme.BAD, GlassTheme.OK, Draw.clamp01(e.knob.value));
    }

    /** Short events fit on one line; anything longer gets a title and a message line. */
    private static boolean twoLines(Event event) {
        if (event.message.isEmpty()) return false;
        return SmoothFont.bold(9.5f).width(event.title) + SmoothFont.regular(8.5f).width(event.message) > 200;
    }

    /** Draws the content of a face horizontally centred on {@code cx} in a body of height {@code h}. */
    private static void drawFace(Face f, float cx, float y, float h, float w) {
        float x = cx - w / 2;
        if (f == TAB) {
            IslandTab.draw(cx, y, w, face == TAB ? tabOpen : 1);
        } else if (f == TOGGLES) {
            float rowY = y + TOGGLE_PAD;
            boolean first = true;
            for (Event row : toggles) {
                float s = Draw.clamp01(row.show.value);
                if (s < 0.01f) continue;
                if (!first) Draw.rect(x + 18, rowY - 0.3f, w - 36, 0.6f, 0, Draw.fade(0x38ffffff, s));
                // New rows drop in from just above their place.
                Draw.pushAlpha(s * s);
                drawToggleRow(row, x, rowY - 4 * (1 - s), TOGGLE_ROW);
                Draw.popAlpha();
                rowY += TOGGLE_ROW * s;
                first = false;
            }
        } else if (f.live != null && f.live.style == STYLE_SCAFFOLD) {
            drawScaffold(f.live, x, y, h, w);
        } else if (f.event != null) {
            Event e = f.event;
            SmoothFont title = SmoothFont.bold(9.5f), message = SmoothFont.regular(8.5f);
            float cy = y + h / 2;
            if (e.toggle) {
                drawToggleRow(e, x, y, h);
            } else if (twoLines(e)) {
                Draw.glow(x + 18, y + 8, 2.5f, h - 16, 1.25f, 6, Draw.alpha(e.accent, 0.55f));
                Draw.rect(x + 18, y + 8, 2.5f, h - 16, 1.25f, e.accent);
                title.draw(title.trim(e.title, w - 50), x + 28, y + 6, GlassTheme.TEXT);
                message.draw(message.trim(e.message, w - 50), x + 28, y + 20, GlassTheme.MUTED);
            } else {
                Draw.glow(x + 20 - 2.5f, cy - 2.5f, 5, 5, 2.5f, 6, Draw.alpha(e.accent, 0.6f));
                Draw.circle(x + 20, cy, 2.5f, e.accent);
                float end = title.draw(e.title, x + 29, title.centerY(y, h) - 0.5f, GlassTheme.TEXT);
                if (!e.message.isEmpty()) message.draw(e.message, end + 8, message.centerY(y, h) - 0.5f, GlassTheme.MUTED);
            }
        } else if (f.live != null) {
            Live l = f.live;
            SmoothFont font = SmoothFont.regular(9.5f);
            boolean bar = l.progress >= 0, icons = l.iconCount > 0;
            // With icons the text sits in a row of its own at the top.
            float rowH = icons ? 20 : h, lift = !icons && bar ? 1.5f : 0;
            float cy = y + rowH / 2 - lift;
            Draw.glow(x + 20 - 2.5f, cy - 2.5f, 5, 5, 2.5f, 6, Draw.alpha(l.accent, 0.6f));
            Draw.circle(x + 20, cy, 2.5f, l.accent);
            font.draw(font.trim(l.text, w - 50), x + 29, font.centerY(y, rowH) - 0.5f - lift, GlassTheme.TEXT);
            if (icons) drawIcons(l, x, y + 19.5f, w);
            if (bar) {
                float bx = x + 20, bw = w - 40, by = y + h - 4.8f, thick = 1.8f;
                Draw.rect(bx, by, bw, thick, thick / 2, 0x30ffffff);
                float fill = bw * Draw.clamp01(l.progress);
                if (fill > 0.5f) {
                    Draw.glow(bx, by, fill, thick, thick / 2, 5, Draw.alpha(l.accent, 0.5f));
                    Draw.gradientH(bx, by, fill, thick, thick / 2, Draw.mix(l.accent, 0xffffffff, 0.05f), Draw.mix(l.accent, 0xffffffff, 0.35f));
                }
            }
        } else {
            SmoothFont bold = SmoothFont.bold(9), regular = SmoothFont.regular(8.5f);
            int color = fpsColor(fps);
            float end = bold.draw(fpsLabel, x + 18, bold.centerY(y, h) - 0.5f, color);
            float sx = end + 8, sparkW = 44, sparkH = h - 12, sy = y + (h - sparkH) / 2;
            if (fpsCount >= 2) {
                float top = 30;
                for (int i = 0; i < fpsCount; i++) top = Math.max(top, fpsHistory[i]);
                for (int i = 0; i < fpsCount; i++) {
                    int index = (fpsIndex - fpsCount + i + fpsHistory.length) % fpsHistory.length;
                    sparkPoints[i * 2] = sx + i / (float) (fpsHistory.length - 1) * sparkW;
                    sparkPoints[i * 2 + 1] = sy + sparkH - Math.min(1, fpsHistory[index] / top) * sparkH;
                }
                // polyline takes the whole array; park the unused tail on the last point.
                for (int i = fpsCount; i < fpsHistory.length; i++) {
                    sparkPoints[i * 2] = sparkPoints[fpsCount * 2 - 2];
                    sparkPoints[i * 2 + 1] = sparkPoints[fpsCount * 2 - 1];
                }
                Draw.polyline(1.2f, Draw.alpha(color, 0.9f), sparkPoints);
            }
            Draw.rect(sx + sparkW + 6, y + 6, 0.6f, h - 12, 0, 0x38ffffff);
            float textY = regular.centerY(y, h) - 0.5f;
            float clockEnd = regular.draw(clockText, sx + sparkW + 12, textY, GlassTheme.TEXT);
            Draw.circle(clockEnd + 6, y + h / 2, 0.9f, 0x70ffffff);
            regular.draw(pingLabel, clockEnd + 12, textY, pingColor(ping));
        }
    }

    /** A module switch: a little switch that slides to the new state, then the name and the state. */
    private static void drawToggleRow(Event e, float x, float y, float h) {
        SmoothFont title = SmoothFont.bold(9.5f), message = SmoothFont.regular(8.5f);
        float cy = y + h / 2;
        float k = e.knob.update(e.on ? 1 : 0, Anim.dt()), on = Draw.clamp01(k);
        float tx = x + 19, tw = 18, th = 10, ty = cy - th / 2;
        int track = Draw.mix(GlassTheme.BAD, GlassTheme.OK, on);
        Draw.glow(tx, ty, tw, th, th / 2, 7, Draw.alpha(track, 0.5f));
        Draw.rect(tx, ty, tw, th, th / 2, track);
        float knobX = tx + th / 2 + (tw - th) * Math.max(-0.08f, Math.min(1.08f, k));
        Draw.shadow(knobX - 3.4f, cy - 3f, 6.8f, 6.8f, 3.4f, 2f, 0x50000000);
        Draw.circle(knobX, cy, 3.4f, 0xffffffff);
        float end = title.draw(e.title, tx + tw + 9, title.centerY(y, h) - 0.5f, GlassTheme.TEXT);
        message.draw(e.message, end + 8, message.centerY(y, h) - 0.5f, Draw.mix(GlassTheme.MUTED, track, 0.45f));
    }

    /**
     * The row of item icons of a live activity; the newest slides in from the right and pops.
     * Vanilla items follow the GL transform but not the glass fade, so they only appear once
     * the face is mostly opaque.
     */
    private static void drawIcons(Live l, float x, float y, float w) {
        float pop = l.iconPop.update(1, Anim.dt());
        if (Draw.alpha() < 0.55f) return;
        int n = l.iconCount;
        float startX = x + (w - (n * ICON_STEP - (ICON_STEP - ICON))) / 2;
        float slide = (1 - Math.min(1, Math.max(0, pop))) * ICON_STEP * 0.6f;
        beginItems();
        try {
            for (int i = 0; i < n; i++) {
                ItemStack stack = l.icons[i];
                if (stack == null) continue;
                float size = i == n - 1 ? Math.max(0.2f, Math.min(1.18f, pop)) : 1;
                drawItem(stack, startX + i * ICON_STEP + slide + ICON / 2, y + ICON / 2, size, true);
            }
        } catch (RuntimeException ignored) {
            // A broken item model must not take the HUD down with it.
        } finally {
            endItems();
        }
    }

    private static void beginItems() {
        GlStateManager.pushMatrix();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.clear(256);
        GlStateManager.enableRescaleNormal();
        RenderHelper.enableGUIStandardItemLighting();
    }

    /** One vanilla item icon centred on (cx, cy); call between {@link #beginItems()} and {@link #endItems()}. */
    private static void drawItem(ItemStack stack, float cx, float cy, float size, boolean overlay) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0);
        GlStateManager.scale(size, size, 1);
        GlStateManager.translate(-ICON / 2, -ICON / 2, 0);
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        if (overlay) mc.getRenderItem().renderItemOverlays(mc.fontRendererObj, stack, 0, 0);
        GlStateManager.popMatrix();
    }

    private static void endItems() {
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.popMatrix();
    }

    // ---------------------------------------------------------------- scaffold

    private static final float SCAFFOLD_NUMBER = 13f;

    private static int scaffoldColor(Live l) {
        return l.count <= 0 ? GlassTheme.BAD : l.count <= 16 ? GlassTheme.WARN : GlassTheme.accent();
    }

    private static String scaffoldNumber(Live l) {
        return Integer.toString(Math.max(0, Math.round(l.rolled.value)));
    }

    private static String scaffoldBps(Live l) {
        return String.format("%.1f", l.bpsShown.value);
    }

    private static float scaffoldWidth(Live l) {
        SmoothFont number = SmoothFont.bold(SCAFFOLD_NUMBER), small = SmoothFont.regular(7.5f), bold = SmoothFont.bold(9);
        // At least three digits wide, so the island does not twitch as the count crosses 100 or 10.
        float digits = Math.max(number.width("000"), number.width(scaffoldNumber(l)));
        float left = 38 + digits + 5 + small.width("blocks") + 17 * Draw.clamp01(l.arrowShow.value);
        float right = bold.width("00.0") + 2 + small.width("b/s");
        return Math.max(168, left + 18 + right + 16);
    }

    /**
     * Scaffold's own face: the block in hand, a big rolling count that pops on every change,
     * blocks per second, an arrow pointing where you build (relative to the camera; a bouncing
     * double arrow while towering) and a fuel gauge along the
     * bottom that turns yellow and breathes when blocks run low, red and shakes when they run out.
     */
    private static void drawScaffold(Live l, float x, float y, float h, float w) {
        float dt = Anim.dt();
        float pop = l.countPop.update(0, dt);
        float shake = l.shake.update(0, dt);
        float held = l.heldPop.update(1, dt);
        float arrow = l.arrowShow.update(l.moving || l.towering, dt);
        float tower = l.towerMix.update(l.towering, dt);
        if (l.moving) {
            // Turn the short way round.
            float delta = ((l.direction - l.angle.value) % 360 + 540) % 360 - 180;
            l.angle.update(l.angle.value + delta, dt);
        }
        l.rolled.update(l.count, dt);
        l.bpsShown.update(l.bps, dt);
        l.fuel.update(l.peak <= 0 ? 0 : Math.min(1, l.count / l.peak), dt);
        l.flash = Math.max(0, l.flash - dt * 4);

        int color = scaffoldColor(l);
        long ms = System.currentTimeMillis();
        float breathe = l.count > 0 && l.count <= 16 ? 0.55f + 0.45f * (float) Math.sin(ms * 0.006) : 1;
        float cy = y + h / 2 - 1.2f;
        float ox = shake * 2.2f;

        // Block in hand, with a flash of its colour behind it on every placement.
        float iconX = x + 20 + ox;
        if (l.flash > 0.01f) Draw.glow(iconX - 8, cy - 8, 16, 16, 6, 9, Draw.alpha(color, 0.55f * l.flash));
        Draw.rect(iconX - 9.5f, cy - 9.5f, 19, 19, 6, Draw.alpha(color, 0.12f + 0.12f * l.flash));
        if (l.held == null) {
            Draw.outline(iconX - 6, cy - 6, 12, 12, 3, 1, Draw.alpha(color, 0.9f), Draw.alpha(color, 0.4f));
        }

        // The count: rolls to the new value and pops; the label sits on its baseline.
        SmoothFont number = SmoothFont.bold(SCAFFOLD_NUMBER), small = SmoothFont.regular(7.5f), bold = SmoothFont.bold(9);
        String digits = scaffoldNumber(l);
        float numX = x + 38 + ox, numW = number.width(digits);
        float numY = number.centerY(cy - 10, 20);
        float scale = 1 + 0.16f * pop;
        Draw.pushTransform(numX + numW / 2, cy, scale, 0, 0);
        number.draw(digits, numX, numY, l.count <= 16 ? Draw.mix(GlassTheme.TEXT, color, 0.75f * breathe) : GlassTheme.TEXT);
        Draw.popTransform();
        float labelX = numX + Math.max(number.width("000"), numW) + 5;
        float labelEnd = small.draw("blocks", labelX, numY + number.height() - small.height() - 1.2f, GlassTheme.MUTED);

        // Where the blocks are going: an arrow in a ring that turns with the build direction
        // (up = ahead, down = backwards); while towering it becomes a bouncing double arrow.
        if (arrow > 0.01f) {
            int tint = GlassTheme.accent();
            float ax = labelEnd + 11, ay = cy;
            Draw.pushAlpha(arrow);
            Draw.outline(ax - 6, ay - 6, 12, 12, 6, 0.8f, Draw.alpha(tint, 0.6f), Draw.alpha(tint, 0.25f));
            if (tower < 0.99f) {
                double rad = Math.toRadians(l.angle.value);
                float nudge = 0.6f; // the chevron's weight sits behind its tip
                Draw.pushAlpha(1 - tower);
                Draw.chevron(ax - (float) Math.sin(rad) * nudge, ay + (float) Math.cos(rad) * nudge, 2.4f, l.angle.value - 90, 1.4f, tint);
                Draw.popAlpha();
            }
            if (tower > 0.01f) {
                float bounce = (float) Math.abs(Math.sin(ms * 0.012)) * 1.6f;
                Draw.pushAlpha(tower);
                Draw.chevron(ax, ay - 1 - bounce, 2.2f, -90, 1.3f, tint);
                Draw.chevron(ax, ay + 2.2f - bounce, 2.2f, -90, 1.3f, Draw.alpha(tint, 0.45f));
                Draw.popAlpha();
            }
            Draw.popAlpha();
        }

        // Speed on the right.
        float right = x + w - 16;
        float unitX = right - small.width("b/s");
        small.draw("b/s", unitX, numY + number.height() - small.height() - 1.2f, GlassTheme.MUTED);
        bold.drawRight(scaffoldBps(l), unitX - 2, bold.centerY(cy - 10, 20) + 0.6f, l.bpsShown.value >= 0.05f ? GlassTheme.TEXT : GlassTheme.MUTED);

        // Fuel gauge along the bottom.
        float gx = x + 16, gw = w - 32, gy = y + h - 4.6f, thick = 1.8f;
        Draw.rect(gx, gy, gw, thick, thick / 2, 0x30ffffff);
        float fill = gw * Draw.clamp01(l.fuel.value);
        if (fill > 0.5f) {
            Draw.glow(gx, gy, fill, thick, thick / 2, 5, Draw.alpha(color, 0.5f * breathe + 0.3f * l.flash));
            Draw.gradientH(gx, gy, fill, thick, thick / 2, Draw.fade(Draw.mix(color, 0xffffffff, 0.05f), breathe), Draw.fade(Draw.mix(color, 0xffffffff, 0.4f), breathe));
        }

        // The block itself is a vanilla item and does not follow the glass fade, so it only
        // appears once the face is mostly opaque (like the item row).
        if (l.held != null && Draw.alpha() >= 0.55f) {
            Draw.flush();
            beginItems();
            try {
                drawItem(l.held, iconX, cy, 0.82f * Math.max(0.2f, Math.min(1.25f, held)), false);
            } catch (RuntimeException ignored) {
            } finally {
                endItems();
            }
        }
    }

    // ---------------------------------------------------------------- crosshair stack

    /** Every live activity as a capsule under the crosshair, sliding in and out. */
    private static void drawStack(float sw, float sh) {
        float y = sh / 2 + 14;
        for (Live live : lives.values()) {
            float show = live.show.value;
            if (show < 0.01f) continue;
            Draw.pushAlpha(show);
            Draw.pushTransform(sw / 2, y + 8.5f, 0.86f + 0.14f * show, 0, 0);
            GlassTheme.pill(live.text, sw / 2, y, live.accent, live.progress);
            Draw.popTransform();
            Draw.popAlpha();
            y += 20 * show;
        }
    }

    // ---------------------------------------------------------------- idle readout

    private static int fpsColor(float value) {
        if (value >= 90) return 0xff2eff71;
        if (value >= 60) return 0xffb4ff2e;
        if (value >= 30) return 0xffffc82e;
        return 0xffff502e;
    }

    static int pingColor(int value) {
        if (value <= 0) return GlassTheme.MUTED;
        if (value < 80) return 0xff7dffa6;
        if (value < 160) return 0xffffd76a;
        return 0xffff7a66;
    }
}
