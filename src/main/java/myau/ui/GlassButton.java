package myau.ui;

import myau.ui.glass.Anim;
import myau.ui.glass.Draw;
import myau.ui.glass.SmoothFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import org.lwjgl.input.Mouse;

/**
 * Button for BetterMyau screens. By default it is an inner control meant to sit on a glass
 * panel; {@link #floating()} turns it into a small frosted panel of its own for vanilla screens.
 */
public class GlassButton extends GuiButton {
    private final Anim.Clock clock = new Anim.Clock();
    private final myau.ui.glass.Interaction touch = new myau.ui.glass.Interaction();
    private float age;
    private boolean floating;

    public GlassButton(int id, int x, int y, int width, int height, String label) {
        super(id, x, y, width, height, label);
    }

    public GlassButton floating() {
        this.floating = true;
        return this;
    }

    @Override
    public void playPressSound(net.minecraft.client.audio.SoundHandler soundHandler) {
        touch.pulse();
        myau.ui.glass.UiSound.CLICK.play();
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) return;
        hovered = mouseX >= xPosition && mouseY >= yPosition && mouseX < xPosition + width && mouseY < yPosition + height;
        float dt = clock.tick() * Anim.speed();
        age += dt;
        touch.update(hovered && enabled, hovered && enabled && Mouse.isButtonDown(0), dt);
        float hot = touch.hot, down = touch.down;
        float appear = Anim.reduced() ? 1 : Anim.outCubic(age / 0.32f);

        Draw.syncGuiScale();
        float x = xPosition, y = yPosition, w = width, h = height, r = Math.min(7, h / 2f);
        Draw.pushTransform(x + w / 2, y + h / 2, 1 - 0.035f * down, 0, (1 - appear) * 5);
        Draw.pushAlpha(appear * (enabled ? 1 : 0.45f));
        if (floating) GlassTheme.panel(x, y, w, h, r, 0.6f);
        else GlassTheme.card(x, y, w, h, r, 0.35f + 0.65f * hot);
        if (hot > 0.01f) {
            // Same hover as the menus: an accent wash and a short bar sliding in from the left.
            int accent = GlassTheme.vividAccent();
            Draw.gradientH(x, y, w * 0.75f, h, r, Draw.alpha(accent, 0.18f * hot), Draw.alpha(accent, 0));
            float bh = Math.max(2, (h - 9) * hot);
            Draw.rect(x + 4.5f, y + (h - bh) / 2, 2, bh, 1, Draw.alpha(accent, hot));
            Draw.outline(x, y, w, h, r, 0.75f, Draw.alpha(accent, 0.6f * hot), Draw.alpha(accent, 0.22f * hot));
            Draw.spot(x, y, w, h, r, mouseX, mouseY, Math.max(40, w * 0.45f), 0x16ffffff);
        }
        SmoothFont font = SmoothFont.regular(9.5f);
        int color = Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, enabled ? 0.55f + 0.45f * hot : 0);
        font.drawCentered(font.trim(displayString, w - 8), x + w / 2, font.centerY(y, h) - 0.5f, color);
        Draw.popAlpha();
        Draw.popTransform();
        mouseDragged(mc, mouseX, mouseY);
    }
}
