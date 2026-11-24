package myau.ui;

import myau.ui.glass.Draw;
import myau.ui.glass.SmoothFont;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;

/**
 * Text field whose text, caret and selection are drawn with the glass UI font. Editing is the
 * vanilla {@link GuiTextField}; the box itself is drawn by the screen with
 * {@link GlassTheme#field}. Like a vanilla field without background, (x, y) is where the text
 * starts.
 */
public class GlassTextField extends GuiTextField {
    private static final float LINE = 8;
    private final int boxWidth;
    private float scroll;

    public GlassTextField(int id, FontRenderer font, int x, int y, int width, int height) {
        super(id, font, x, y, width, height);
        this.boxWidth = width;
        setEnableBackgroundDrawing(false);
    }

    private static SmoothFont font() {
        return SmoothFont.regular(9.5f);
    }

    private float widthTo(SmoothFont font, String text, int index) {
        return font.width(text.substring(0, Math.max(0, Math.min(index, text.length()))));
    }

    @Override
    public void drawTextBox() {
        if (!getVisible()) return;
        Draw.syncGuiScale();
        SmoothFont font = font();
        String text = getText();
        int caret = Math.max(0, Math.min(getCursorPosition(), text.length()));
        int anchor = Math.max(0, Math.min(getSelectionEnd(), text.length()));
        float inner = boxWidth - 8, caretX = widthTo(font, text, caret);

        // Keep the caret inside the visible part of the line.
        if (caretX - scroll > inner) scroll = caretX - inner;
        if (caretX - scroll < 0) scroll = caretX;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, font.width(text) - inner)));

        float x = xPosition, top = yPosition - 2, lineH = LINE + 4;
        float textY = font.centerY(top, lineH) - 0.5f;
        Draw.pushClip(x - 1, top - 2, inner + 2, lineH + 4);
        if (anchor != caret) {
            float from = widthTo(font, text, Math.min(anchor, caret)), to = widthTo(font, text, Math.max(anchor, caret));
            Draw.rect(x + from - scroll - 1, top, to - from + 2, lineH, 2.5f, Draw.alpha(GlassTheme.accent(), 0.38f));
        }
        font.draw(text, x - scroll, textY, GlassTheme.TEXT);
        if (isFocused()) {
            if (System.currentTimeMillis() % 1000 < 560) Draw.rect(x + caretX - scroll, top + 1, 0.9f, lineH - 2, 0.45f, GlassTheme.TEXT);
            Draw.reportCaret(x + caretX - scroll, top + 1, lineH - 2);
        }
        Draw.popClip();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) {
        boolean inside = mouseX >= xPosition - 4 && mouseX < xPosition + boxWidth - 4
                && mouseY >= yPosition - 6 && mouseY < yPosition + 14;
        setFocused(inside);
        if (!inside || button != 0) return;
        Draw.syncGuiScale();
        SmoothFont font = font();
        String text = getText();
        float target = mouseX - xPosition + scroll;
        int index = 0;
        while (index < text.length()
                && (widthTo(font, text, index) + widthTo(font, text, index + 1)) / 2 < target) index++;
        setCursorPosition(index);
    }
}
