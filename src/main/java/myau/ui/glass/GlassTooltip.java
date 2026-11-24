package myau.ui.glass;

import myau.ui.GlassTheme;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;

import java.util.List;

/**
 * The hover text of items, buttons and chat components as a glass card: the first line in the
 * heavier weight (it keeps its rarity colour), the rest lighter. Stands in for vanilla's
 * {@code drawHoveringText} and leaves GL the way that method does.
 */
public final class GlassTooltip {
    private static final float PAD_X = 7, PAD_Y = 5.5f, LINE = 10.5f, TITLE_GAP = 2;
    private static final int BODY = 0xffc9ced8;

    private static final Anim.Spring pop = Anim.spring(1, 0.2f, 0.82f);
    private static String shownFor = "";
    private static long shownAt;

    private GlassTooltip() {
    }

    public static void draw(List<String> lines, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        Draw.syncGuiScale();
        SmoothFont title = SmoothFont.bold(8.5f), body = SmoothFont.regular(8.5f);
        float textWidth = 0;
        for (int i = 0; i < lines.size(); i++) textWidth = Math.max(textWidth, (i == 0 ? title : body).width(lines.get(i)));
        float w = textWidth + PAD_X * 2;
        float h = PAD_Y * 2 + LINE * lines.size() - 1.5f + (lines.size() > 1 ? TITLE_GAP : 0);

        // Right of the cursor; flips to the left near the edge, as vanilla does, then stays on screen.
        float x = mouseX + 12, y = mouseY - 12;
        if (x + w > screenWidth - 4) x = mouseX - 14 - w;
        x = Math.max(4, Math.min(x, screenWidth - 4 - w));
        y = Math.max(4, Math.min(y, screenHeight - 4 - h));

        // A different text, or the first frame after nothing was hovered: pop in.
        long now = System.nanoTime();
        String key = lines.get(0);
        if (now - shownAt > 120_000_000L || !key.equals(shownFor)) pop.snap(now - shownAt > 120_000_000L ? 0 : 0.55f);
        shownFor = key;
        shownAt = now;
        float show = pop.update(1, Anim.dt());

        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        Draw.pushTransform(x + w / 2, y + h / 2, 0.92f + 0.08f * Math.min(1.05f, show), 0, 0);
        Draw.pushAlpha(Math.min(1, show * 1.4f));
        GlassTheme.panel(x, y, w, h, GlassTheme.R_CARD, 1.1f);
        // Text sits on whatever is behind the card, including bright items: darken it a little more.
        // Better glass is clear and its text has a halo; a heavy layer would turn it back into a dark pane.
        Draw.rect(x, y, w, h, GlassTheme.R_CARD, GlassTheme.lastPanelBetter() ? 0x2012151b : 0x8c12151b);
        float ty = y + PAD_Y;
        for (int i = 0; i < lines.size(); i++) {
            if (i == 0) {
                title.draw(lines.get(i), x + PAD_X, ty, GlassTheme.TEXT);
                ty += LINE + (lines.size() > 1 ? TITLE_GAP : 0);
            } else {
                body.draw(lines.get(i), x + PAD_X, ty, BODY);
                ty += LINE;
            }
        }
        Draw.popAlpha();
        Draw.popTransform();

        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        RenderHelper.enableStandardItemLighting();
        GlStateManager.enableRescaleNormal();
    }
}
