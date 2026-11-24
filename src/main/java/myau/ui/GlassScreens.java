package myau.ui;

import myau.ui.glass.Draw;
import myau.ui.glass.SmoothFont;
import net.minecraft.client.gui.GuiButton;

import java.util.List;

/**
 * Glass styling shared by the mixins that restyle vanilla screens (buttons, sliders, the
 * panel behind a column of buttons). All of it is switched by HUD "glass-vanilla-screens".
 */
public final class GlassScreens {
    private GlassScreens() { }

    /** Frosted panel around every visible button of a screen. */
    public static void buttonPanel(List<GuiButton> buttons, int mouseX, int mouseY) {
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (GuiButton button : buttons) {
            if (!button.visible) continue;
            left = Math.min(left, button.xPosition);
            top = Math.min(top, button.yPosition);
            right = Math.max(right, button.xPosition + button.getButtonWidth());
            bottom = Math.max(bottom, button.yPosition + button.height);
        }
        if (left > right) return;
        Draw.syncGuiScale();
        float pad = 10, x = left - pad, y = top - pad, w = right - left + pad * 2, h = bottom - top + pad * 2;
        GlassTheme.panel(x, y, w, h, GlassTheme.R_PANEL, 1.2f);
        myau.ui.glass.ScreenTransition.focus(x, y, w, h, GlassTheme.R_PANEL);
        Draw.spot(x, y, w, h, GlassTheme.R_PANEL, mouseX, mouseY, 120, 0x0cffffff);
        Draw.spotOutline(x, y, w, h, GlassTheme.R_PANEL, 1, mouseX, mouseY, 150, 0x70ffffff);
    }

    /**
     * A vanilla button drawn the glass way.
     *
     * @param hot    0..1 hover amount
     * @param down   press amount (roughly 0..1, springs slightly negative on release)
     * @param slider the slider position 0..1, or negative for a plain button
     */
    public static void button(float x, float y, float w, float h, String label, boolean enabled, float hot,
                              float down, float slider, int mouseX, int mouseY) {
        Draw.syncGuiScale();
        float r = Math.min(7, h / 2f);
        int accent = GlassTheme.vividAccent();
        Draw.pushTransform(x + w / 2, y + h / 2, 1 - 0.035f * down, 0, 0);
        Draw.pushAlpha(enabled ? 1 : 0.45f);
        // Same language as the main menu: a dark frosted capsule with no bright rim at rest; on
        // hover an accent bar and a soft accent wash slide in from the left.
        boolean blur = Draw.blurred(x, y, w, h, r, 1);
        Draw.rect(x, y, w, h, r, blur ? 0x6612141b : 0xe612141b);
        Draw.rect(x, y, w, h, r, Draw.alpha(0xffffff, 0.035f + 0.05f * hot));
        Draw.outline(x, y, w, h, r, 0.6f, Draw.alpha(0xffffff, 0.13f + 0.07f * hot), Draw.alpha(0xffffff, 0.04f));
        if (slider >= 0) {
            float value = Draw.clamp01(slider), fill = Math.max(h, (w - 4) * value);
            Draw.rect(x + 2, y + 2, fill, h - 4, Math.max(1, r - 2), Draw.alpha(accent, 0.30f + 0.12f * hot));
            float knobX = x + 4 + (w - 8) * value;
            Draw.glow(knobX - 1.5f, y + 3, 3, h - 6, 1.5f, 5, Draw.alpha(accent, 0.55f));
            Draw.rect(knobX - 1.5f, y + 3, 3, h - 6, 1.5f, 0xffffffff);
        }
        if (hot > 0.01f) {
            if (slider < 0) {
                Draw.gradientH(x, y, w * 0.75f, h, r, Draw.alpha(accent, 0.20f * hot), Draw.alpha(accent, 0));
                float bh = Math.max(2, (h - 9) * hot);
                Draw.glow(x + 4.5f, y + (h - bh) / 2, 2, bh, 1, 4, Draw.alpha(accent, 0.5f * hot));
                Draw.rect(x + 4.5f, y + (h - bh) / 2, 2, bh, 1, Draw.alpha(accent, hot));
            }
            Draw.outline(x, y, w, h, r, 0.75f, Draw.alpha(accent, 0.55f * hot), Draw.alpha(accent, 0.2f * hot));
            Draw.spot(x, y, w, h, r, mouseX, mouseY, Math.max(40, w * 0.45f), Draw.alpha(0xffffff, 0.08f * hot));
        }
        SmoothFont font = SmoothFont.regular(9.5f);
        int color = Draw.mix(GlassTheme.MUTED, GlassTheme.TEXT, enabled ? 0.65f + 0.35f * hot : 0);
        font.drawCentered(font.trim(label, w - 8), x + w / 2 + (slider < 0 ? 1.5f * hot : 0), font.centerY(y, h) - 0.5f, color);
        Draw.popAlpha();
        Draw.popTransform();
    }
}
