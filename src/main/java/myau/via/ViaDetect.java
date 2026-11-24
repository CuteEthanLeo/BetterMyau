package myau.via;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a server which protocol it speaks, the way the server list does: a 1.7+ status ping that
 * announces 1.8 (47). Servers that accept 1.8 (for example through their own ViaVersion) answer
 * 47; others answer with their own version. Results are kept for a few minutes per address.
 */
public final class ViaDetect {
    private ViaDetect() { }

    private static final long KEEP_MS = 5 * 60 * 1000L;
    private static final Map<String, long[]> CACHE = new ConcurrentHashMap<>();

    /** A recent answer for this address, or null. */
    public static Integer cached(String key) {
        long[] entry = CACHE.get(key);
        if (entry == null || System.currentTimeMillis() - entry[1] > KEEP_MS) return null;
        return (int) entry[0];
    }

    public static void remember(String key, int protocol) {
        CACHE.put(key, new long[]{protocol, System.currentTimeMillis()});
    }

    public static void forget(String key) {
        CACHE.remove(key);
    }

    /**
     * Pings the server; blocks for at most about twice the timeout.
     *
     * @param host the host name to put in the handshake (what the player typed, after SRV lookup)
     * @return the protocol number the server reported, or null when it did not answer properly
     */
    public static Integer ping(InetAddress address, int port, String host, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, 47);
            writeString(handshake, host == null ? address.getHostAddress() : host);
            handshake.write((port >> 8) & 0xFF);
            handshake.write(port & 0xFF);
            writeVarInt(handshake, 1);
            writeFrame(out, handshake.toByteArray());
            writeFrame(out, new byte[]{0x00});
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = readVarInt(in);
            if (length <= 0 || length > 0x200000) return null;
            byte[] frame = new byte[length];
            in.readFully(frame);
            DataInputStream body = new DataInputStream(new java.io.ByteArrayInputStream(frame));
            if (readVarInt(body) != 0x00) return null;
            int jsonLength = readVarInt(body);
            if (jsonLength <= 0 || jsonLength > length) return null;
            byte[] json = new byte[jsonLength];
            body.readFully(json);
            JsonElement root = new JsonParser().parse(new String(json, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) return null;
            JsonObject object = root.getAsJsonObject();
            if (!object.has("version") || !object.get("version").isJsonObject()) return null;
            JsonObject version = object.getAsJsonObject("version");
            if (!version.has("protocol")) return null;
            return version.get("protocol").getAsInt();
        } catch (Exception e) {
            ViaClient.LOGGER.info("Version check of {}:{} failed: {}", host, port, e.toString());
            return null;
        }
    }

    private static void writeFrame(OutputStream out, byte[] data) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, data.length);
        frame.write(data);
        out.write(frame.toByteArray());
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) throw new IOException("end of stream");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }
}
