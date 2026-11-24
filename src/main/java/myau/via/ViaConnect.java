package myau.via;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import myau.mixin.IAccessorGuiConnecting;
import myau.module.modules.HUD;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.NetworkManager;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Decides, per connection, which version to join with, and carries that decision to the Netty
 * pipeline. The connecting screen and the server list ping each build their connection on their
 * own thread through {@link NetworkManager#createNetworkManagerAndConnect}; the version travels
 * there in a thread local, is tied to the new NetworkManager the moment it exists, and is read
 * back when its channel is initialized. Nothing is stored on Minecraft's own classes.
 * <p>
 * Deciding ({@code decide*}) and opening ({@link #open}) are separate steps, so a server that is
 * down or refuses the connection is an ordinary connection error and never looks like a failure
 * of the translation itself (see ViaHooks).
 */
public final class ViaConnect {
    private ViaConnect() { }

    /** How long a connection waits for ViaVersion to finish loading at startup. */
    private static final long LOAD_WAIT_MS = 20_000L;
    private static final int DETECT_TIMEOUT_MS = 4_000;

    /** A connection the connecting screen is about to open. */
    private static final class Pending {
        final Object screen;
        final String host;
        final int port;
        final ServerData data;
        final String key;

        Pending(Object screen, String host, int port, ServerData data, String key) {
            this.screen = screen;
            this.host = host;
            this.port = port;
            this.data = data;
            this.key = key;
        }
    }

    private static volatile ServerData noted;
    private static volatile Pending pending;
    /** The connecting screen whose connection was decided last (for its cancel flag). */
    private static volatile Object deciding;
    private static volatile boolean forcedSet;
    private static volatile ProtocolVersion forced;
    private static final ThreadLocal<ProtocolVersion> NEXT = new ThreadLocal<>();
    private static final Map<NetworkManager, ProtocolVersion> BOUND = Collections.synchronizedMap(new WeakHashMap<>());
    /** How each server list entry was last pinged: AUTO, NATIVE or a version name. */
    private static final Map<ServerData, String> PING_MODE = Collections.synchronizedMap(new WeakHashMap<>());

    /** What the connecting screen shows under its title; null when there is nothing to say. */
    public static volatile String stage;
    /** The version the connection being opened uses (null: plain 1.8). */
    public static volatile ProtocolVersion connecting;

    // ------------------------------------------------------------------ connecting screen

    /** The connecting screen was built for this server entry (called before it starts connecting). */
    public static void noteServerData(ServerData data) {
        noted = data;
    }

    /** The connecting screen starts connecting to host:port (after SRV lookup), on the client thread. */
    public static void prepare(Object screen, String host, int port) {
        ServerData data = noted;
        noted = null;
        String key = ViaSettings.key(data != null ? data.serverIP : host + ":" + port);
        pending = new Pending(screen, host, port, data, key);
        stage = null;
        connecting = null;
    }

    /** The next connection uses this version whatever the settings say (a server transfer keeps its version). */
    public static void forceNextTarget(ProtocolVersion target) {
        forced = target;
        forcedSet = true;
    }

    /**
     * Decides the version for the connecting screen's connection, on its connector thread. May
     * wait for ViaVersion to finish loading or ping the server first.
     *
     * @return the version to translate to, or null for plain 1.8
     */
    static ProtocolVersion decideForScreen(InetAddress address, int port) {
        Pending p = pending;
        pending = null;
        deciding = p != null ? p.screen : null;
        ProtocolVersion target = targetForScreen(p, address, port);
        connecting = target;
        stage = null;
        if (target != null) ViaClient.LOGGER.info("Joining {}:{} as {}", p != null ? p.host : address.getHostAddress(), port, target.getName());
        return target;
    }

    /** True when the player cancelled the connecting screen while its version was being decided. */
    static boolean screenCancelled() {
        Object screen = deciding;
        deciding = null;
        // Always the GuiConnecting that prepared this; a plain cast, since the inject edition rewrites accessor calls but not instanceof.
        return screen != null && ((IAccessorGuiConnecting) screen).isConnectCancelled();
    }

    /**
     * Opens a connection that is translated to {@code target} (a ProtocolVersion). Whatever the
     * connection itself throws (refused, timed out, unknown host) goes to the caller untouched.
     */
    static NetworkManager open(Object target, InetAddress address, int port, boolean nativeTransport) {
        NEXT.set((ProtocolVersion) target);
        try {
            return NetworkManager.createNetworkManagerAndConnect(address, port, nativeTransport);
        } finally {
            NEXT.remove();
        }
    }

    private static ProtocolVersion targetForScreen(Pending p, InetAddress address, int port) {
        if (forcedSet) {
            forcedSet = false;
            ProtocolVersion target = forced;
            forced = null;
            return target != null && ViaClient.await(LOAD_WAIT_MS) ? target : null;
        }
        if (p != null && p.data != null && p.data.isOnLAN()) return null;
        if (ViaClient.state() == ViaClient.State.OFF || ViaClient.state() == ViaClient.State.DISABLED) return null;
        String key = p != null ? p.key : ViaSettings.key(address.getHostAddress() + ":" + port);
        String choice = ViaSettings.choiceFor(key);
        if (ViaSettings.NATIVE.equals(choice)) return null;

        if (!ViaClient.ready()) {
            stage = "正在加载 Via…";
            if (!ViaClient.await(LOAD_WAIT_MS)) {
                notifyLater("Via 不可用", "已按 1.8 原生连接");
                return null;
            }
        }

        ProtocolVersion target;
        if (ViaSettings.AUTO.equals(choice)) {
            Integer protocol = nativePing(p != null ? p.data : null);
            if (protocol == null) protocol = ViaDetect.cached(key);
            if (protocol == null) {
                stage = "正在检测服务器版本…";
                protocol = ViaDetect.ping(address, port, p != null ? p.host : null, DETECT_TIMEOUT_MS);
                if (protocol == null) {
                    notifyLater("版本检测失败", "已按 1.8 原生连接");
                    return null;
                }
            }
            ViaDetect.remember(key, protocol);
            target = ViaVersions.fromServerProtocol(protocol);
            if (target == null && protocol != ViaVersions.nativeVersion().getVersion()) {
                notifyLater("未知的服务器版本", "协议 " + protocol + "，已按 1.8 原生连接");
            }
        } else {
            target = ViaVersions.parse(choice);
            if (target == null) {
                notifyLater("未知版本 " + choice, "已按 1.8 原生连接");
                return null;
            }
        }
        return target == null || target.equals(ViaVersions.nativeVersion()) ? null : target;
    }

    /** The protocol a server list ping without translation saw for this entry, or null. */
    private static Integer nativePing(ServerData data) {
        if (data == null || data.pingToServer < 0) return null;
        String mode = PING_MODE.get(data);
        if (!ViaSettings.AUTO.equals(mode) && !ViaSettings.NATIVE.equals(mode)) return null;
        return data.version > 0 ? data.version : null;
    }

    // ------------------------------------------------------------------ server list ping

    private static final ThreadLocal<ServerData> PINGING = new ThreadLocal<>();

    /** A server list ping for this entry starts on this thread. */
    public static void pinging(ServerData server) {
        PINGING.set(server);
    }

    /**
     * Decides how a server list ping goes: translated when the entry has a fixed version (ViaVersion
     * then reports the server as compatible when it matches), plain otherwise (automatic pings see
     * the server's real protocol that way).
     *
     * @return the version to translate to, or null for plain 1.8
     */
    static ProtocolVersion decideForPing() {
        ServerData server = PINGING.get();
        PINGING.remove();
        ProtocolVersion target = null;
        String mode = ViaSettings.NATIVE;
        if (server != null && !server.isOnLAN() && ViaClient.state() != ViaClient.State.DISABLED
                && ViaClient.state() != ViaClient.State.OFF) {
            String choice = ViaSettings.choiceFor(server.serverIP);
            if (ViaSettings.AUTO.equals(choice)) {
                mode = ViaSettings.AUTO;
            } else if (!ViaSettings.NATIVE.equals(choice) && ViaClient.await(LOAD_WAIT_MS)) {
                target = ViaVersions.parse(choice);
                if (target != null && target.equals(ViaVersions.nativeVersion())) target = null;
                if (target != null) mode = target.getName();
            }
        }
        if (server != null) PING_MODE.put(server, mode);
        return target;
    }

    /** How this entry was last pinged: AUTO, NATIVE, a version name, or null when not yet. */
    public static String pingMode(ServerData data) {
        return data == null ? null : PING_MODE.get(data);
    }

    // ------------------------------------------------------------------ pipeline handoff

    /** A NetworkManager was just created on this thread: remember the version its connection uses. */
    public static NetworkManager bind(NetworkManager manager) {
        ProtocolVersion target = NEXT.get();
        if (target != null && manager != null) BOUND.put(manager, target);
        return manager;
    }

    /** For the offline end-to-end check (ViaServerTest), which builds its pipeline without the mixins. */
    static void bindForTest(NetworkManager manager, ProtocolVersion target) {
        BOUND.put(manager, target);
    }

    /** The version a NetworkManager's connection was opened for (taken once, when its pipeline is built). */
    static ProtocolVersion boundTarget(Object manager) {
        return manager == null ? null : BOUND.remove(manager);
    }

    static void notifyLater(String title, String message) {
        ViaClient.LOGGER.info("{}: {}", title, message);
        try {
            Minecraft.getMinecraft().addScheduledTask(() -> HUD.notify(title, message, false));
        } catch (RuntimeException ignored) {
        }
    }
}
