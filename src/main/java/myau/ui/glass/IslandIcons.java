package myau.ui.glass;

import net.minecraft.item.ItemStack;

import java.util.Arrays;

/**
 * The last few items a module handled, for the icon row of a live activity on the island
 * ({@link Island#live(String, int, String, int, float, ItemStack[], int)}). Keeps copies, so the
 * icons stay put after the real stacks move or disappear.
 */
public final class IslandIcons {
    private final ItemStack[] stacks = new ItemStack[Island.MAX_ICONS];
    private int count, serial;

    /** Append a copy of {@code stack}; the oldest drops off once the row is full. */
    public void push(ItemStack stack) {
        if (stack == null) return;
        if (count == stacks.length) {
            System.arraycopy(stacks, 1, stacks, 0, count - 1);
            count--;
        }
        stacks[count++] = stack.copy();
        serial++;
    }

    public void clear() {
        Arrays.fill(stacks, null);
        count = 0;
    }

    public ItemStack[] stacks() {
        return stacks;
    }

    /** Changes with every push, so the island pops the newest icon in. */
    public int serial() {
        return serial;
    }
}
