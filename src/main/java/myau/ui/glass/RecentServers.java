package myau.ui.glass;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import myau.Myau;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Addresses typed into the multiplayer screen's quick connect box, newest first. */
final class RecentServers {
    private RecentServers() { }

    private static final int LIMIT = 8;
    private static final File FILE = new File("./config/Myau/", "recent-servers.json");
    private static List<String> entries;

    static List<String> all() {
        load();
        return Collections.unmodifiableList(entries);
    }

    static void add(String address) {
        if (address == null) return;
        address = address.trim();
        if (address.isEmpty()) return;
        load();
        String key = address.toLowerCase(Locale.ROOT);
        entries.removeIf(entry -> entry.toLowerCase(Locale.ROOT).equals(key));
        entries.add(0, address);
        while (entries.size() > LIMIT) entries.remove(entries.size() - 1);
        save();
    }

    static void remove(String address) {
        load();
        if (entries.remove(address)) save();
    }

    private static void load() {
        if (entries != null) return;
        entries = new ArrayList<>();
        if (!FILE.isFile()) return;
        try (Reader reader = Files.newBufferedReader(FILE.toPath(), StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(reader);
            if (root != null && root.isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray()) {
                    if (element.isJsonPrimitive() && entries.size() < LIMIT) entries.add(element.getAsString());
                }
            }
        } catch (Exception e) {
            Myau.LOGGER.warn("Failed to read recent servers", e);
        }
    }

    private static void save() {
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            JsonArray array = new JsonArray();
            for (String entry : entries) array.add(new com.google.gson.JsonPrimitive(entry));
            try (Writer writer = Files.newBufferedWriter(FILE.toPath(), StandardCharsets.UTF_8)) {
                writer.write(array.toString());
            }
        } catch (Exception e) {
            Myau.LOGGER.warn("Failed to save recent servers", e);
        }
    }
}
