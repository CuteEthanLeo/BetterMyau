package myau.ui.glass;

import net.minecraft.client.resources.I18n;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.storage.SaveFormatComparator;

import java.awt.Color;
import java.util.Locale;

/** One saved world on the glass world list: the save's summary plus its card's animation state. */
final class WorldSlot {
    final SaveFormatComparator save;
    final int index;
    final Interaction feel = new Interaction();
    final Anim.Smooth select = new Anim.Smooth(0, 14);
    final Anim.Spring y = Anim.spring(0, 0.32f, 0.82f);
    boolean placed;
    float appearDelay;
    /** Bytes on disk; -1 while it is being measured, -2 when it could not be. */
    volatile long size = -1;

    WorldSlot(SaveFormatComparator save, int index) {
        this.save = save;
        this.index = index;
    }

    String folder() {
        return save.getFileName();
    }

    String name() {
        String name = save.getDisplayName();
        if (name == null || name.trim().isEmpty()) name = I18n.format("selectWorld.world") + " " + (index + 1);
        return name;
    }

    boolean matches(String query) {
        return name().toLowerCase(Locale.ROOT).contains(query) || folder().toLowerCase(Locale.ROOT).contains(query);
    }

    boolean hardcore() {
        return save.isHardcoreModeEnabled();
    }

    String modeLabel() {
        if (hardcore()) return I18n.format("gameMode.hardcore");
        WorldSettings.GameType type = save.getEnumGameType();
        return type == null ? "" : I18n.format("gameMode." + type.getName());
    }

    int modeColor() {
        if (hardcore()) return 0xffff5a5a;
        WorldSettings.GameType type = save.getEnumGameType();
        if (type == WorldSettings.GameType.CREATIVE) return 0xff5aa9ff;
        if (type == WorldSettings.GameType.ADVENTURE) return 0xffffb340;
        if (type == WorldSettings.GameType.SPECTATOR) return 0xffa7adba;
        return 0xff5fd38d;
    }

    /** A colour of its own for the world's tile, from its folder name. */
    int tint() {
        float hue = (folder().hashCode() & 0xffff) / 65535f;
        return Color.HSBtoRGB(hue, 0.5f, 0.92f) | 0xff000000;
    }

    String initial() {
        String plain = name().trim();
        return plain.isEmpty() ? "?" : plain.substring(0, plain.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }

    static String bytes(long size) {
        if (size < 0) return size == -1 ? "计算中…" : "未知";
        if (size < 1024) return size + " B";
        if (size < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", size / 1024f);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", size / 1024f / 1024f);
        return String.format(Locale.ROOT, "%.2f GB", size / 1024f / 1024f / 1024f);
    }
}
