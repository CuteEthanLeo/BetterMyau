package myau.via;

import java.util.ArrayList;
import java.util.List;

/**
 * A server dialog (1.21.6+) as the client shows it: plain text and plain values, no ViaVersion
 * type. {@link ViaDialogs} builds it from the server's dialog and wires each control back to it;
 * the screen only reads and changes what is here. Values are changed on the client thread.
 */
public final class ViaDialogView {
    /** Shows and hides dialogs. The game sets this to its screen; the offline checks answer by themselves. */
    public interface Presenter {
        /** A dialog arrived (or a sub-dialog was opened). Called on any thread. */
        void show(ViaDialogView view);

        /** This dialog is gone: back to whatever was there before. Called on any thread. */
        void close(ViaDialogView view);

        void openUrl(String url);

        void copy(String text);
    }

    /** Until the game sets its own: nothing is shown, so the dialog stays unanswered. */
    public static volatile Presenter presenter = new Presenter() {
        @Override
        public void show(ViaDialogView view) {
            ViaClient.LOGGER.warn("A server dialog arrived but nothing can show it: {}", view.title);
        }

        @Override
        public void close(ViaDialogView view) {
        }

        @Override
        public void openUrl(String url) {
        }

        @Override
        public void copy(String text) {
        }
    };

    public final String title;
    /** Esc answers with the dialog's own cancel action; otherwise only "disconnect" leaves. */
    public final boolean escapeCloses;
    /** Text, inputs and the dialog's own list of buttons, top to bottom. */
    public final List<Item> items = new ArrayList<>();
    /** The dialog's main buttons (yes / no, the notice's button, the exit button). */
    public final List<Button> footer = new ArrayList<>();
    /** The dialog this one was opened from; closing this one returns there. */
    public final ViaDialogView parent;
    /** An answer went out and the server asked the client to wait for its response. */
    public volatile boolean waiting;

    interface Check {
        boolean ok();
    }

    Runnable onEscape, onDisconnect;
    Check connected;

    ViaDialogView(String title, boolean escapeCloses, ViaDialogView parent) {
        this.title = title;
        this.escapeCloses = escapeCloses;
        this.parent = parent;
    }

    /** False once the connection this dialog came over is closed. */
    public boolean connected() {
        return connected == null || connected.ok();
    }

    /** Esc was pressed (only does something when {@link #escapeCloses}). */
    public void escape() {
        if (escapeCloses && onEscape != null) onEscape.run();
    }

    /** The player leaves the server from the dialog. */
    public void disconnect() {
        if (onDisconnect != null) onDisconnect.run();
    }

    public abstract static class Item {
        public final String label;

        Item(String label) {
            this.label = label == null ? "" : label;
        }
    }

    /** A paragraph of the dialog's body; may contain line breaks. */
    public static final class Text extends Item {
        Text(String text) {
            super(text);
        }
    }

    interface TextSetter {
        void set(String value);
    }

    /** A text input. */
    public static final class Field extends Item {
        public final int maxLength;
        private final TextSetter setter;
        private String value;

        Field(String label, String value, int maxLength, TextSetter setter) {
            super(label);
            this.value = value == null ? "" : value;
            this.maxLength = Math.max(1, maxLength);
            this.setter = setter;
        }

        public String value() {
            return value;
        }

        public void set(String text) {
            if (text == null) text = "";
            if (text.length() > maxLength) text = text.substring(0, maxLength);
            value = text;
            setter.set(text);
        }
    }

    interface FlagSetter {
        void set(boolean value);
    }

    /** A checkbox. */
    public static final class Toggle extends Item {
        private final FlagSetter setter;
        private boolean value;

        Toggle(String label, boolean value, FlagSetter setter) {
            super(label);
            this.value = value;
            this.setter = setter;
        }

        public boolean value() {
            return value;
        }

        public void set(boolean on) {
            value = on;
            setter.set(on);
        }
    }

    interface IndexSetter {
        void set(int index);
    }

    /** One of several options. */
    public static final class Choice extends Item {
        public final String[] options;
        private final IndexSetter setter;
        private int index;

        Choice(String label, String[] options, int index, IndexSetter setter) {
            super(label);
            this.options = options;
            this.index = Math.max(0, Math.min(options.length - 1, index));
            this.setter = setter;
        }

        public int index() {
            return index;
        }

        public String option() {
            return options.length == 0 ? "" : options[index];
        }

        /** The next option, wrapping around. */
        public void next() {
            if (options.length == 0) return;
            index = (index + 1) % options.length;
            setter.set(index);
        }
    }

    interface NumberSetter {
        /** @return the value as the server's dialog words it */
        String set(float value);
    }

    /** A number on a slider. */
    public static final class Range extends Item {
        public final float min, max, step;
        private final NumberSetter setter;
        private float value;
        private String shown;

        Range(String label, float min, float max, float step, float value, String shown, NumberSetter setter) {
            super(label);
            this.min = Math.min(min, max);
            this.max = Math.max(min, max);
            this.step = step;
            this.value = value;
            this.shown = shown;
            this.setter = setter;
        }

        public float value() {
            return value;
        }

        public String shown() {
            return shown;
        }

        /** 0..1 along the slider. */
        public float fraction() {
            return max <= min ? 0 : Math.max(0, Math.min(1, (value - min) / (max - min)));
        }

        public void setFraction(float fraction) {
            float target = min + (max - min) * Math.max(0, Math.min(1, fraction));
            if (step > 0) target = min + Math.round((target - min) / step) * step;
            target = Math.max(min, Math.min(max, target));
            if (target == value) return;
            value = target;
            shown = setter.set(target);
        }
    }

    /** A button: one of the dialog's own, or one that opens a sub-dialog. */
    public static final class Button extends Item {
        public final String tooltip;
        private final Runnable action;

        Button(String label, String tooltip, Runnable action) {
            super(label);
            this.tooltip = tooltip;
            this.action = action;
        }

        public void click() {
            action.run();
        }
    }
}
