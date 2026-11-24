package myau.ui.glass;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.util.FileUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.File;

/**
 * What was played last, for the main menu's "继续" (resume) entry and the disconnect screen's
 * "重新连接" (reconnect). The world or server is remembered in {@code config/Myau/last-played.json};
 * the full {@link ServerData} of the last connection is kept in memory only, since vanilla clears
 * its own copy when a world is left.
 */
public final class LastPlayed {
    public static final int WORLD = 0, SERVER = 1;
    private static final File FILE = new File("./config/Myau/", "last-played.json");

    public static final class Entry {
        public final int kind;
        /** World folder name or server address. */
        public final String id;
        /** World or server display name. */
        public final String name;
        public final long at;

        Entry(int kind, String id, String name, long at) {
            this.kind = kind;
            this.id = id;
            this.name = name == null || name.isEmpty() ? id : name;
            this.at = at;
        }
    }

    private static Entry entry;
    private static boolean loaded;
    /** The last server connected to in this session (for reconnecting), or null. */
    private static volatile ServerData lastServer;

    private LastPlayed() {
    }

    public static synchronized Entry get() {
        if (!loaded) {
            loaded = true;
            entry = read();
        }
        return entry;
    }

    public static ServerData lastServer() {
        return lastServer;
    }

    /** A singleplayer world is being started (new or existing). */
    public static void world(String folder, String name) {
        if (folder == null || folder.isEmpty()) return;
        store(new Entry(WORLD, folder, name, System.currentTimeMillis()));
    }

    /** A connection to a server is starting. */
    public static void server(ServerData data) {
        if (data == null || data.serverIP == null) return;
        lastServer = data;
        store(new Entry(SERVER, data.serverIP, data.serverName, System.currentTimeMillis()));
    }

    /** A connection by address only (no server list entry). */
    public static void server(String host, int port) {
        if (host == null || host.isEmpty()) return;
        String address = port == 25565 ? host : host + ":" + port;
        server(new ServerData(address, address, false));
    }

    /** True when the remembered world still exists on disk. */
    public static boolean worldExists(String folder) {
        File saves = new File(Minecraft.getMinecraft().mcDataDir, "saves");
        return new File(new File(saves, folder), "level.dat").isFile();
    }

    private static synchronized void store(Entry next) {
        entry = next;
        loaded = true;
        JsonObject json = new JsonObject();
        json.addProperty("kind", next.kind == WORLD ? "world" : "server");
        json.addProperty("id", next.id);
        json.addProperty("name", next.name);
        json.addProperty("at", next.at);
        try {
            File dir = FILE.getParentFile();
            if (dir != null) dir.mkdirs();
            FileUtil.writeAtomic(FILE, json.toString());
        } catch (Exception e) {
            Myau.LOGGER.warn("[Glass] cannot save {}", FILE, e);
        }
    }

    private static Entry read() {
        if (!FILE.isFile()) return null;
        try {
            JsonObject json = new JsonParser().parse(FileUtil.readString(FILE)).getAsJsonObject();
            String kind = json.get("kind").getAsString();
            String id = json.get("id").getAsString();
            String name = json.has("name") ? json.get("name").getAsString() : id;
            long at = json.has("at") ? json.get("at").getAsLong() : 0;
            if (id.isEmpty()) return null;
            return new Entry("world".equals(kind) ? WORLD : SERVER, id, name, at);
        } catch (Exception e) {
            Myau.LOGGER.warn("[Glass] ignoring unreadable {}", FILE);
            return null;
        }
    }
}
