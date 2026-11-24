package myau.module.mode;

import myau.module.Module;

/** The modes of a module, in selector order. Index = value of the module's ModeProperty. */
public final class ModeList<M extends Module, T extends ModuleMode<M>> {
    private final T[] modes;

    @SafeVarargs
    public ModeList(T... modes) {
        this.modes = modes;
    }

    /** Mode names for the ModeProperty of the owning module. */
    public String[] names() {
        String[] names = new String[this.modes.length];
        for (int i = 0; i < this.modes.length; i++) {
            names[i] = this.modes[i].name;
        }
        return names;
    }

    public T get(int index) {
        return this.modes[Math.max(0, Math.min(index, this.modes.length - 1))];
    }

    public T[] all() {
        return this.modes;
    }

    public int size() {
        return this.modes.length;
    }

    public void bind(M parent) {
        for (T mode : this.modes) {
            mode.bind(parent);
        }
    }
}
