package myau.via;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.NetworkManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Cipher;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;

/**
 * What the mixins call. Nothing here mentions a ViaVersion type, and only the cross-version code
 * itself is guarded:
 * <ul>
 * <li>A connection's own errors (refused, timed out, unknown host) always reach the caller
 * untouched, exactly as without cross-version support. They never count as a Via failure.</li>
 * <li>If the bundled ViaVersion cannot be linked at all ({@link LinkageError}), cross-version
 * support switches off for the session; the UI says so and connections go out as plain 1.8.</li>
 * <li>Any other error in the cross-version code affects that one call only: it is logged, the
 * player is told, and the next connection tries again.</li>
 * </ul>
 */
public final class ViaHooks {
    private ViaHooks() { }

    private static final Logger LOGGER = LogManager.getLogger("BetterMyau/Via");
    private static volatile String disabled;

    /** Why cross-version support was switched off for this session, or null while it is on. */
    public static String disabledReason() {
        return disabled;
    }

    /**
     * An error in the cross-version code (never one of the connection's own).
     *
     * @param what what was being done, shown to the player
     */
    private static void failed(String what, Throwable t) {
        if (t instanceof LinkageError) {
            if (disabled == null) {
                disabled = "Via 无法加载（" + t.getClass().getSimpleName() + "）";
                LOGGER.error("Cross-version support disabled for this session: the bundled ViaVersion cannot be linked", t);
                notifyPlayer("跨版本已停用", "Via 无法加载，本次游戏按 1.8 连接");
            }
            return;
        }
        LOGGER.error("Cross-version: {} failed", what, t);
        notifyPlayer("跨版本出错", what + "失败，这次按 1.8 连接");
    }

    private static void notifyPlayer(String title, String message) {
        try {
            ViaConnect.notifyLater(title, message);
        } catch (Throwable ignored) {
            // The notification itself must never get in the way.
        }
    }

    // ------------------------------------------------------------------ startup

    /** Starts loading in the background and adds the vanilla server list button. */
    public static void start(java.io.File folder, String clientVersion) {
        try {
            boolean viaForge = net.minecraftforge.fml.common.Loader.isModLoaded("viaforge");
            ViaClient.helloSource = () -> {
                // The client's own options, as it would send them after Join Game.
                net.minecraft.client.settings.GameSettings settings = net.minecraft.client.Minecraft.getMinecraft().gameSettings;
                int parts = 0;
                for (net.minecraft.entity.player.EnumPlayerModelParts part : settings.getModelParts()) parts |= part.getPartMask();
                return new ViaClient.Hello(settings.language, settings.renderDistanceChunks, settings.chatVisibility.getChatVisibility(),
                        settings.chatColours, parts, net.minecraft.client.ClientBrandRetriever.getClientModName());
            };
            // Dialogs a 1.21.6+ server shows before Join Game are drawn by the client itself.
            ViaDialogView.presenter = myau.ui.glass.GlassServerDialog.presenter();
            ViaClient.start(folder, clientVersion, viaForge);
            me.ksyz.accountmanager.utils.Nan0EventRegister.register(net.minecraftforge.common.MinecraftForge.EVENT_BUS, new ViaGuiEvents());
        } catch (Throwable t) {
            failed("启动跨版本", t);
        }
    }

    // ------------------------------------------------------------------ connecting

    public static void noteServerData(ServerData data) {
        if (disabled != null) return;
        try {
            ViaConnect.noteServerData(data);
        } catch (Throwable t) {
            failed("记录服务器", t);
        }
    }

    public static void prepare(Object screen, String host, int port) {
        if (disabled != null) return;
        try {
            ViaConnect.prepare(screen, host, port);
        } catch (Throwable t) {
            failed("准备连接", t);
        }
    }

    /** The connecting screen's connection: the version is decided first, then the connection is opened. */
    public static NetworkManager connectFromScreen(InetAddress address, int port, boolean nativeTransport) throws IOException {
        Object target = null;
        boolean cancelled = false;
        if (disabled == null) {
            try {
                target = ViaConnect.decideForScreen(address, port);
                cancelled = ViaConnect.screenCancelled();
            } catch (Throwable t) {
                target = null;
                failed("选择版本", t);
            }
        }
        // The player gave up while the version was being checked; vanilla returns quietly on this.
        if (cancelled) throw new ConnectException("Connection cancelled");
        return target == null
                ? NetworkManager.createNetworkManagerAndConnect(address, port, nativeTransport)
                : ViaConnect.open(target, address, port, nativeTransport);
    }

    public static void pinging(ServerData server) {
        if (disabled != null) return;
        try {
            ViaConnect.pinging(server);
        } catch (Throwable t) {
            failed("服务器列表 ping", t);
        }
    }

    /** A server list ping's connection: decided first, then opened; a server that is down just fails its ping. */
    public static NetworkManager connectForPing(InetAddress address, int port, boolean nativeTransport) {
        Object target = null;
        if (disabled == null) {
            try {
                target = ViaConnect.decideForPing();
            } catch (Throwable t) {
                target = null;
                failed("服务器列表 ping", t);
            }
        }
        return target == null
                ? NetworkManager.createNetworkManagerAndConnect(address, port, nativeTransport)
                : ViaConnect.open(target, address, port, nativeTransport);
    }

    /** The connecting screen's extra line: the check in progress, "Via 1.21.4", or "". */
    public static String connectingLine(boolean connected) {
        if (disabled != null) return "";
        try {
            if (!connected && ViaConnect.stage != null) return ViaConnect.stage;
            return ViaConnect.connecting == null ? "" : "Via " + ViaVersions.name(ViaConnect.connecting);
        } catch (Throwable t) {
            return "";
        }
    }

    /** The step the connecting screen shows before the connection exists, or null for its own text. */
    public static String connectingStage() {
        if (disabled != null) return null;
        try {
            return ViaConnect.stage;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The step the connecting screen shows once the connection exists and is past login, or null for its own text. */
    public static String loginStage(NetworkManager manager) {
        if (disabled != null || manager == null) return null;
        try {
            return ViaClient.loginStage(manager.channel());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ pipeline

    public static NetworkManager bind(NetworkManager manager) {
        if (disabled != null) return manager;
        try {
            return ViaConnect.bind(manager);
        } catch (Throwable t) {
            failed("记录连接版本", t);
            return manager;
        }
    }

    public static void inject(Channel channel, Object networkManager) {
        if (disabled != null) return;
        try {
            ViaClient.inject(channel, networkManager);
        } catch (Throwable t) {
            failed("启动转换", t);
        }
    }

    public static void afterCompression(Channel channel) {
        if (disabled != null) return;
        try {
            ViaClient.afterCompression(channel);
        } catch (Throwable t) {
            failed("调整压缩顺序", t);
        }
    }

    public static boolean legacyEncryption(Channel channel) {
        if (disabled != null) return false;
        try {
            return ViaClient.legacyEncryption(channel);
        } catch (Throwable t) {
            failed("1.6.4 加密", t);
            return false;
        }
    }

    public static void enableLegacyEncryption(Channel channel, Cipher decrypt, ChannelHandler encoder) {
        ViaClient.enableLegacyEncryption(channel, decrypt, encoder);
    }

    public static boolean skipLegacySessionJoin(Channel channel) {
        if (disabled != null) return false;
        try {
            return ViaClient.skipLegacySessionJoin(channel);
        } catch (Throwable t) {
            failed("旧版本登录", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ in game

    public static boolean clientPlacesSounds() {
        if (disabled != null) return false;
        try {
            return ViaClient.clientPlacesSounds();
        } catch (Throwable t) {
            if (t instanceof LinkageError) failed("放置音效", t);
            return false;
        }
    }

    /** A placement sound: played locally on a translated 1.9+ connection (the server no longer sends it), else as vanilla. */
    public static void placeSound(net.minecraft.world.World world, double x, double y, double z, String sound, float volume, float pitch) {
        if (world.isRemote && clientPlacesSounds()) world.playSound(x, y, z, sound, volume, pitch, false);
        else world.playSoundEffect(x, y, z, sound, volume, pitch);
    }

    public static int chatLimit(int vanilla) {
        if (disabled != null) return vanilla;
        try {
            return ViaClient.chatLimit(vanilla);
        } catch (Throwable t) {
            if (t instanceof LinkageError) failed("聊天长度", t);
            return vanilla;
        }
    }

    public static String debugLine() {
        if (disabled != null) return null;
        try {
            return ViaClient.debugLine();
        } catch (Throwable t) {
            if (t instanceof LinkageError) failed("F3 信息", t);
            return null;
        }
    }
}
