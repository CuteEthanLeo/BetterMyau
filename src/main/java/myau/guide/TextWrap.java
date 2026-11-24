package myau.guide;

import myau.ui.glass.SmoothFont;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Inline markup and line breaking for the guide.
 *
 * <p>Markup: {@code **bold**}, {@code `code`} (a small chip), {@code [Key]} (a key cap) and
 * placeholders in braces ({@code {bind:ClickGui}}, {@code {version}}) that a resolver fills in;
 * a resolved {@code bind:} becomes a key cap.
 *
 * <p>Breaking: Chinese may break between any two characters, Latin text only at spaces; chips and
 * key caps stay whole. Closing punctuation never starts a line (it may hang past the edge by its
 * own width, which is narrow) and opening punctuation never ends one.
 */
final class TextWrap {
    enum Style { NORMAL, BOLD, CODE, KEY }

    static final class Run {
        final String text;
        final Style style;

        Run(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }

    /** A piece of one line: text in one style at {@code x} (left edge, chip padding included). */
    static final class Piece {
        final String text;
        final Style style;
        final float x, w;

        Piece(String text, Style style, float x, float w) {
            this.text = text;
            this.style = style;
            this.x = x;
            this.w = w;
        }
    }

    static final class Line {
        final List<Piece> pieces = new ArrayList<>();
        float width;
    }

    /** The fonts a block is set in. Chips get {@code pad} inside and {@code margin} outside, per side. */
    static final class Fonts {
        final SmoothFont normal, bold, code, key;
        final float pad, margin;

        Fonts(SmoothFont normal, SmoothFont bold, SmoothFont code, SmoothFont key, float pad, float margin) {
            this.normal = normal;
            this.bold = bold;
            this.code = code;
            this.key = key;
            this.pad = pad;
            this.margin = margin;
        }

        SmoothFont of(Style style) {
            switch (style) {
                case BOLD:
                    return this.bold;
                case CODE:
                    return this.code;
                case KEY:
                    return this.key;
                default:
                    return this.normal;
            }
        }

        boolean chip(Style style) {
            return style == Style.CODE || style == Style.KEY;
        }

        float width(String text, Style style) {
            float w = this.of(style).width(text);
            return this.chip(style) ? w + (this.pad + this.margin) * 2 : w;
        }
    }

    private static final String CLOSING = "，。、；：！？）」』》〉】〕”’…—～·%,.;:!?)]}'\"";
    private static final String OPENING = "（「『《〈【〔“‘([{";

    private TextWrap() {
    }

    // ------------------------------------------------------------ markup

    /**
     * @param resolve turns a placeholder name (without braces) into text; a name starting with
     *                {@code bind:} is shown as a key cap. Null means the placeholder is unknown and
     *                is shown as written.
     */
    static List<Run> parse(String text, Function<String, String> resolve) {
        List<Run> runs = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        boolean bold = false;
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '*' && i + 1 < n && text.charAt(i + 1) == '*') {
                flush(runs, plain, bold);
                bold = !bold;
                i += 2;
                continue;
            }
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    flush(runs, plain, bold);
                    runs.add(new Run(text.substring(i + 1, end), Style.CODE));
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int end = text.indexOf(']', i + 1);
                if (end > i + 1 && end - i <= 24) {
                    flush(runs, plain, bold);
                    runs.add(new Run(text.substring(i + 1, end), Style.KEY));
                    i = end + 1;
                    continue;
                }
            }
            if (c == '{') {
                int end = text.indexOf('}', i + 1);
                if (end > i + 1) {
                    String name = text.substring(i + 1, end);
                    String value = resolve == null ? null : resolve.apply(name);
                    if (value != null) {
                        if (name.startsWith("bind:")) {
                            flush(runs, plain, bold);
                            runs.add(new Run(value, Style.KEY));
                        } else {
                            plain.append(value);
                        }
                        i = end + 1;
                        continue;
                    }
                }
            }
            plain.append(c);
            i++;
        }
        flush(runs, plain, bold);
        return runs;
    }

    private static void flush(List<Run> runs, StringBuilder plain, boolean bold) {
        if (plain.length() == 0) return;
        runs.add(new Run(plain.toString(), bold ? Style.BOLD : Style.NORMAL));
        plain.setLength(0);
    }

    // ------------------------------------------------------------ breaking

    private static final class Token {
        final String text;
        final Style style;
        final boolean space, glueBefore;
        final float w;

        Token(String text, Style style, boolean space, boolean glueBefore, float w) {
            this.text = text;
            this.style = style;
            this.space = space;
            this.glueBefore = glueBefore;
            this.w = w;
        }
    }

    private static boolean wide(char c) {
        return c >= 0x2E80;
    }

    private static List<Token> tokens(List<Run> runs, Fonts fonts) {
        List<Token> out = new ArrayList<>();
        boolean glueNext = false;
        for (Run run : runs) {
            if (fonts.chip(run.style)) {
                out.add(new Token(run.text, run.style, false, glueNext, fonts.width(run.text, run.style)));
                glueNext = false;
                continue;
            }
            String s = run.text;
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                int j = i + 1;
                boolean space = c == ' ';
                if (space) {
                    while (j < s.length() && s.charAt(j) == ' ') j++;
                } else if (!wide(c)) {
                    while (j < s.length() && s.charAt(j) != ' ' && !wide(s.charAt(j))) j++;
                }
                String text = s.substring(i, j);
                boolean glue = glueNext || !space && CLOSING.indexOf(text.charAt(0)) >= 0;
                out.add(new Token(text, run.style, space, glue, fonts.width(text, run.style)));
                glueNext = !space && OPENING.indexOf(text.charAt(text.length() - 1)) >= 0;
                i = j;
            }
        }
        return out;
    }

    /** Breaks the runs into lines no wider than {@code maxWidth} (closing punctuation may hang). */
    static List<Line> wrap(List<Run> runs, Fonts fonts, float maxWidth) {
        List<Line> lines = new ArrayList<>();
        Line line = new Line();
        float x = 0;
        for (Token t : tokens(runs, fonts)) {
            if (t.space && line.pieces.isEmpty()) continue;
            if (x + t.w > maxWidth && !line.pieces.isEmpty() && !t.glueBefore) {
                finish(lines, line, fonts);
                line = new Line();
                x = 0;
                if (t.space) continue;
            }
            if (t.w > maxWidth && !t.space) {
                // A word or chip wider than the whole line: cut it by characters.
                String rest = t.text;
                while (!rest.isEmpty()) {
                    int fit = fitting(rest, t.style, fonts, maxWidth - x);
                    if (fit == 0) {
                        if (line.pieces.isEmpty()) fit = 1;
                        else {
                            finish(lines, line, fonts);
                            line = new Line();
                            x = 0;
                            continue;
                        }
                    }
                    String part = rest.substring(0, fit);
                    float w = fonts.width(part, t.style);
                    line.pieces.add(new Piece(part, t.style, x, w));
                    x += w;
                    rest = rest.substring(fit);
                    if (!rest.isEmpty()) {
                        finish(lines, line, fonts);
                        line = new Line();
                        x = 0;
                    }
                }
                continue;
            }
            line.pieces.add(new Piece(t.text, t.style, x, t.w));
            x += t.w;
        }
        finish(lines, line, fonts);
        return lines;
    }

    private static int fitting(String text, Style style, Fonts fonts, float room) {
        int fit = 0;
        for (int k = 1; k <= text.length(); k++) {
            if (fonts.width(text.substring(0, k), style) > room) break;
            fit = k;
        }
        return fit;
    }

    /** Merges neighbouring pieces of the same (non-chip) style and drops trailing spaces. */
    private static void finish(List<Line> lines, Line line, Fonts fonts) {
        while (!line.pieces.isEmpty() && line.pieces.get(line.pieces.size() - 1).text.trim().isEmpty()) {
            line.pieces.remove(line.pieces.size() - 1);
        }
        if (line.pieces.isEmpty()) {
            if (lines.isEmpty()) return;
            lines.add(line);
            return;
        }
        List<Piece> merged = new ArrayList<>();
        for (Piece p : line.pieces) {
            Piece last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.style == p.style && !fonts.chip(p.style)) {
                String text = last.text + p.text;
                merged.set(merged.size() - 1, new Piece(text, p.style, last.x, p.x + p.w - last.x));
            } else {
                merged.add(p);
            }
        }
        line.pieces.clear();
        line.pieces.addAll(merged);
        Piece end = merged.get(merged.size() - 1);
        line.width = end.x + end.w;
        lines.add(line);
    }
}
