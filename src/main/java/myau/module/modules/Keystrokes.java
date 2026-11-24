package myau.module.modules;

import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.Draw;
import myau.ui.glass.Island;
import myau.ui.glass.SmoothFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

import java.util.ArrayDeque;

/**
 * Key display as a satellite of the Dynamic Island: a small capsule beside it with the movement
 * keys (W / A S D and the space bar), a mouse whose buttons light up, and clicks per second.
 * It grows out of the island when switched on and follows it as the island changes shape.
 */
public class Keystrokes extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final float H = 32, PAD = 10, KEY = 10, KGAP = 1.5f, SPACE = 3;
    private static final float CLUSTER = KEY * 3 + KGAP * 2;
    private static final float MOUSE_W = 14, MOUSE_H = 21;

    public final ModeProperty position = new ModeProperty("position", 1, new String[]{"LEFT", "RIGHT", "BELOW"});
    public final IntProperty gap = new IntProperty("gap", 6, 2, 30);
    public final BooleanProperty showCps = new BooleanProperty("show-cps", true);
    public final BooleanProperty showSpace = new BooleanProperty("show-space", true);

    // W, A, S, D, LMB, RMB, SPACE
    private final Anim.Smooth[] press = new Anim.Smooth[7];
    private final boolean[] wasDown = new boolean[7];
    private final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
    private final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
    private final Anim.Spring cpsPop = Anim.spring(0, 0.3f, 0.5f);
    private int leftCps, rightCps;

    private final Island.Satellite satellite = new Island.Satellite() {
        @Override
        protected boolean active() {
            return Keystrokes.this.isEnabled();
        }

        @Override
        protected int side() {
            return Keystrokes.this.position.getValue();
        }

        @Override
        protected float gap() {
            return Keystrokes.this.gap.getValue();
        }

        @Override
        protected float width() {
            return Keystrokes.this.width();
        }

        @Override
        protected float height() {
            return H;
        }

        @Override
        protected void draw(float x, float y, float w, float h, float dt) {
            Keystrokes.this.drawContent(x, y, w, h, dt);
        }
    };

    public Keystrokes() {
        super("Keystrokes", false, true);
        for (int i = 0; i < this.press.length; i++) this.press[i] = new Anim.Smooth(0.0F, 22.0F);
        Island.addSatellite(this.satellite);
    }

    @Override
    public void onDisabled() {
        this.leftClicks.clear();
        this.rightClicks.clear();
        this.leftCps = this.rightCps = 0;
    }

    private String cpsText() {
        return String.valueOf(this.leftCps);
    }

    private float textWidth() {
        SmoothFont big = SmoothFont.bold(11), small = SmoothFont.regular(7);
        float line1 = big.width(this.cpsText()) + 2 + small.width("CPS");
        float line2 = small.width("R " + this.rightCps);
        return Math.max(line1, line2);
    }

    private float width() {
        float w = PAD + CLUSTER + 8 + 0.6f + 8 + MOUSE_W + PAD;
        if (this.showCps.getValue()) w += 7 + this.textWidth() - 2;
        return w;
    }

    private static int cps(ArrayDeque<Long> clicks, long now) {
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000L) clicks.pollFirst();
        return clicks.size();
    }

    /** Key states, click counting and the little bounces; once per frame, from the island. */
    private void sample(float dt) {
        KeyBinding[] binds = {
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindLeft,
                mc.gameSettings.keyBindBack, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindAttack, mc.gameSettings.keyBindUseItem,
                mc.gameSettings.keyBindJump
        };
        // Only what happens in game: clicking around in a screen is not combat.
        boolean inGame = mc.currentScreen == null;
        long now = System.currentTimeMillis();
        for (int i = 0; i < binds.length; i++) {
            boolean down = inGame && binds[i].isKeyDown();
            if (down && !this.wasDown[i]) {
                if (i == 4) {
                    this.leftClicks.addLast(now);
                    this.cpsPop.kick(7);
                    this.satellite.kick(1.2f);
                } else if (i == 5) {
                    this.rightClicks.addLast(now);
                    this.satellite.kick(1.2f);
                } else if (i == 6) {
                    this.satellite.kick(3.0f);
                }
            }
            this.wasDown[i] = down;
            this.press[i].update(down, dt);
        }
        this.leftCps = cps(this.leftClicks, now);
        this.rightCps = cps(this.rightClicks, now);
    }

    private static String keyName(KeyBinding bind, String fallback) {
        try {
            int code = bind.getKeyCode();
            if (code <= 0) return fallback;
            String name = Keyboard.getKeyName(code);
            if (name == null || name.isEmpty()) return fallback;
            return name.length() > 1 ? name.substring(0, 1) : name;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void drawContent(float x, float y, float w, float h, float dt) {
        this.sample(dt);
        // Monochrome, like the island itself: a pressed key turns white, nothing glows.
        SmoothFont keyFont = SmoothFont.bold(7);

        // Keys: W over A S D, the space bar under them.
        boolean space = this.showSpace.getValue();
        float clusterH = KEY * 2 + KGAP + (space ? KGAP + SPACE : 0);
        float kx = x + PAD, ky = y + (h - clusterH) / 2;
        this.key(kx + KEY + KGAP, ky, keyName(mc.gameSettings.keyBindForward, "W"), this.press[0].value, keyFont);
        float row2 = ky + KEY + KGAP;
        this.key(kx, row2, keyName(mc.gameSettings.keyBindLeft, "A"), this.press[1].value, keyFont);
        this.key(kx + KEY + KGAP, row2, keyName(mc.gameSettings.keyBindBack, "S"), this.press[2].value, keyFont);
        this.key(kx + (KEY + KGAP) * 2, row2, keyName(mc.gameSettings.keyBindRight, "D"), this.press[3].value, keyFont);
        if (space) {
            float p = this.press[6].value, sy = row2 + KEY + KGAP;
            Draw.rect(kx, sy, CLUSTER, SPACE, SPACE / 2, Draw.mix(0x1affffff, 0xf2ffffff, p));
        }

        float dx = kx + CLUSTER + 8;
        Draw.rect(dx, y + 9, 0.6f, h - 18, 0, 0x30ffffff);

        // The mouse: an outline, the button pressed fills in white.
        float mx = dx + 0.6f + 8, my = y + (h - MOUSE_H) / 2, half = MOUSE_W / 2, top = MOUSE_H * 0.45f;
        float r = MOUSE_W / 2;
        float pl = this.press[4].value, pr = this.press[5].value;
        Draw.rect(mx, my, MOUSE_W, MOUSE_H, r, 0x10ffffff);
        if (pl > 0.01f) Draw.rect(mx, my, half - 0.3f, top, r, 0, 0, 0, Draw.alpha(0xffffff, 0.95f * pl));
        if (pr > 0.01f) Draw.rect(mx + half + 0.3f, my, half - 0.3f, top, 0, r, 0, 0, Draw.alpha(0xffffff, 0.95f * pr));
        Draw.rect(mx + 1, my + top, MOUSE_W - 2, 0.5f, 0, 0x2cffffff);
        Draw.rect(mx + half - 0.25f, my + 1, 0.5f, top - 1, 0, 0x2cffffff);
        Draw.outline(mx, my, MOUSE_W, MOUSE_H, r, 0.6f, 0x50ffffff, 0x20ffffff);

        // Clicks per second: left big, right underneath.
        if (this.showCps.getValue()) {
            SmoothFont big = SmoothFont.bold(11), small = SmoothFont.regular(7);
            float tx = mx + MOUSE_W + 7;
            float blockH = big.height() + small.height();
            float ty = y + (h - blockH) / 2;
            String number = this.cpsText();
            float nw = big.width(number);
            float pop = this.cpsPop.update(0, dt);
            Draw.pushTransform(tx + nw / 2, ty + big.height() / 2, 1 + 0.03f * pop, 0, 0);
            big.draw(number, tx, ty, GlassTheme.TEXT);
            Draw.popTransform();
            small.draw("CPS", tx + nw + 2, ty + big.height() - small.height() - 0.5f, GlassTheme.FAINT);
            small.draw("R " + this.rightCps, tx, ty + big.height(), GlassTheme.MUTED);
        }
    }

    private void key(float x, float y, String label, float p, SmoothFont font) {
        Draw.pushTransform(x + KEY / 2, y + KEY / 2, 1 - 0.08f * p, 0, 0);
        Draw.rect(x, y, KEY, KEY, 2.5f, Draw.mix(0x1affffff, 0xf2ffffff, p));
        font.drawCentered(label, x + KEY / 2, font.centerY(y, KEY), Draw.mix(GlassTheme.MUTED, 0xff111214, p));
        Draw.popTransform();
    }
}
