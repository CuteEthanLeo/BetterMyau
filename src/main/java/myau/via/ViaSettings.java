package myau.via;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import myau.Myau;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which protocol to join with: one global choice plus an override per server address, kept in
 * {@code config/Myau/via.json}. A choice is {@link #AUTO}, {@link #NATIVE} or a version name as
 * ViaVersion spells it (for example {@code 1.21.4}); a server without an override follows the
 * global choice. This class only stores text, so it never loads ViaVersion.
 */
public final class ViaSettings {
    private ViaSettings() { }

    /** Ping the server first and use the version it reports (1.8 natively when it accepts 1.8). */
    public static final String AUTO = "auto";
    /** Plain 1.8.9: no translation at all. */
    public static final String NATIVE = "native";

    private static final File FILE = new File("./config/Myau/", "via.json");
    private static String global = AUTO;
    private static final Map<String, String> servers = new LinkedHashMap<>();
    private static boolean loaded;

    /** The address as a lookup key: trimmed, lower case, without the default port. */
    public static String key(String address) {
        if (address == null) return "";
        String key = address.trim().toLowerCase(Locale.ROOT);
        if (key.endsWith(":25565")) key = key.substring(0, key.length() - 6);
        if (key.endsWith(".")) key = key.substring(0, key.length() - 1);
        return key;
    }

    public static synchronized String global() {
        load();
        return global;
    }

    public static synchronized void setGlobal(String choice) {
        load();
        global = normalize(choice);
        save();
    }

    /** The server's own choice, or null when it follows the global one. */
    public static synchronized String override(String address) {
        load();
        return servers.get(key(address));
    }

    /** Sets the server's own choice; null makes it follow the global one again. */
    public static synchronized void setOverride(String address, String choice) {
        load();
        String key = key(address);
        if (key.isEmpty()) return;
        if (choice == null) servers.remove(key);
        else servers.put(key, normalize(choice));
        save();
    }

    /** The choice that applies to this address: its override, otherwise the global choice. */
    public static synchronized String choiceFor(String address) {
        String own = override(address);
        return own != null ? own : global();
    }

    private static String normalize(String choice) {
        if (choice == null) return AUTO;
        String trimmed = choice.trim();
        if (trimmed.equalsIgnoreCase(AUTO)) return AUTO;
        if (trimmed.equalsIgnoreCase(NATIVE)) return NATIVE;
        return trimmed;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (!FILE.isFile()) return;
        try (Reader reader = Files.newBufferedReader(FILE.toPath(), StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(reader);
            if (root == null || !root.isJsonObject()) return;
            JsonObject object = root.getAsJsonObject();
            if (object.has("global") && object.get("global").isJsonPrimitive()) {
                global = normalize(object.get("global").getAsString());
            }
            if (object.has("servers") && object.get("servers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("servers").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        servers.put(key(entry.getKey()), normalize(entry.getValue().getAsString()));
                    }
                }
            }
        } catch (Exception e) {
            Myau.LOGGER.warn("Failed to read Via settings", e);
        }
    }

    private static void save() {
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            JsonObject root = new JsonObject();
            root.add("global", new JsonPrimitive(global));
            JsonObject own = new JsonObject();
            for (Map.Entry<String, String> entry : servers.entrySet()) own.add(entry.getKey(), new JsonPrimitive(entry.getValue()));
            root.add("servers", own);
            try (Writer writer = Files.newBufferedWriter(FILE.toPath(), StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
        } catch (Exception e) {
            Myau.LOGGER.warn("Failed to save Via settings", e);
        }
    }
}
