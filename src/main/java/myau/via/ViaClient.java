package myau.via;

import com.viaversion.viaaprilfools.ViaAprilFoolsPlatformImpl;
import com.viaversion.viabackwards.ViaBackwardsPlatformImpl;
import com.viaversion.viarewind.ViaRewindPlatformImpl;
import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.platform.NoopInjector;
import com.viaversion.viaversion.platform.ViaChannelInitializer;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import myau.via.platform.BetterMyauPlatformLoader;
import myau.via.platform.BetterMyauViaPlatform;
import net.minecraft.network.NettyEncryptingDecoder;
import net.raphimc.vialegacy.ViaLegacyPlatformImpl;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;
import net.raphimc.vialegacy.netty.PreNettyLengthPrepender;
import net.raphimc.vialegacy.netty.PreNettyLengthRemover;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Cipher;
import java.io.File;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Cross-version support: ViaVersion, ViaBackwards, ViaRewind, ViaLegacy and ViaAprilFools run
 * inside the client, so a 1.8.9 client can join anything from classic to the newest release.
 * Starts in the background at launch; a connection that needs it waits for it. Connections that
 * stay on 1.8 never get any of it in their pipeline.
 */
public final class ViaClient {
    private ViaClient() { }

    public enum State { OFF, LOADING, READY, FAILED, DISABLED }

    static final Logger LOGGER = LogManager.getLogger("BetterMyau/Via");
    private static final CountDownLatch DONE = new CountDownLatch(1);
    private static volatile State state = State.OFF;
    private static volatile String problem;

    public static State state() {
        return state;
    }

    /** Why it is not available (failed or disabled), for the UI. */
    public static String problem() {
        return problem;
    }

    /** True once translation can be used. */
    public static boolean ready() {
        return state == State.READY;
    }

    /** Starts loading in the background; does nothing the second time. */
    public static synchronized void start(File folder, String clientVersion, boolean otherViaPresent) {
        if (state != State.OFF) return;
        if (otherViaPresent) {
            state = State.DISABLED;
            problem = "检测到 ViaForge，已停用内置 Via";
            LOGGER.warn("ViaForge is installed: the built-in cross-version support stays off.");
            DONE.countDown();
            return;
        }
        state = State.LOADING;
        Thread thread = new Thread(() -> load(folder, clientVersion), "BetterMyau-Via-Init");
        thread.setDaemon(true);
        thread.start();
    }

    /** Loads on the calling thread (used by the offline self-test). */
    static void load(File folder, String clientVersion) {
        long started = System.currentTimeMillis();
        try {
            writeDefaults(folder);
            ViaManagerImpl.initAndLoad(
                    new BetterMyauViaPlatform(folder, clientVersion),
                    new NoopInjector(),
                    new ViaCommandHandler(false),
                    new BetterMyauPlatformLoader(),
                    () -> {
                        new ViaBackwardsPlatformImpl();
                        new ViaRewindPlatformImpl();
                        new ViaLegacyPlatformImpl();
                        new ViaAprilFoolsPlatformImpl();
                    });
            ViaPingBridge.install();
            state = State.READY;
            LOGGER.info("Cross-version support ready in {} ms ({} versions)", System.currentTimeMillis() - started, ViaVersions.selectable().size());
        } catch (Throwable t) {
            state = State.FAILED;
            problem = "Via 加载失败：" + t;
            LOGGER.error("Cross-version support failed to load; connections stay on 1.8", t);
        } finally {
            DONE.countDown();
        }
    }

    /** Waits until loading has finished (or failed); true when it is ready. */
    public static boolean await(long millis) {
        if (state == State.OFF) return false;
        try {
            DONE.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ready();
    }

    /**
     * ViaVersion reads its own defaults for anything missing, so only the values that differ for a
     * client are written, and only into files that do not exist yet (the player's edits stay).
     */
    private static void writeDefaults(File folder) {
        if (!folder.isDirectory() && !folder.mkdirs()) return;
        // No update check against viaversion.com from the player's client.
        writeIfMissing(new File(folder, "viaversion.yml"), "checkforupdates: false\n");
        // 1.17+ pings reach the client as transactions, in order (see ViaPingBridge).
        writeIfMissing(new File(folder, "viabackwards.yml"), "handle-pings-as-inv-acknowledgements: true\n");
    }

    private static void writeIfMissing(File file, String content) {
        if (file.exists()) return;
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write(content);
        } catch (Exception e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }

    // ------------------------------------------------------------------ pipeline

    /**
     * Puts ViaVersion into a freshly built client pipeline (called at the end of the connect
     * channel initializer). Does nothing for connections that stay on 1.8.
     */
    public static void inject(Channel channel, Object networkManager) {
        ProtocolVersion target = ViaConnect.boundTarget(networkManager);
        if (target == null) return;
        if (!ready()) {
            LOGGER.warn("Translation to {} was asked for but ViaVersion is not ready; this connection stays on 1.8", target.getName());
            ViaConnect.notifyLater("跨版本未就绪", "这次按 1.8 连接");
            return;
        }
        try {
            UserConnection user = new UserConnectionImpl(channel, true);
            new ProtocolPipelineImpl(user);
            ViaSession session = new ViaSession(target, user);
            channel.attr(ViaSession.KEY).set(session);

            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addBefore("decoder", ViaDecodeHandler.NAME, new ViaClientDecodeHandler(user, session));
            pipeline.addBefore("encoder", ViaEncodeHandler.NAME, new ViaClientEncodeHandler(user, session));
            // Server list pings: a server on exactly this version reads as compatible (see ViaStatusFix).
            pipeline.addAfter("decoder", ViaStatusFix.NAME, new ViaStatusFix(target));
            if (target.olderThanOrEqualTo(LegacyProtocolVersion.r1_6_4)) {
                // Before 1.7 packets had no length prefix on the wire.
                pipeline.addBefore("splitter", PreNettyLengthPrepender.NAME, new PreNettyLengthPrepender(user));
                pipeline.addBefore("prepender", PreNettyLengthRemover.NAME, new PreNettyLengthRemover(user));
            }
            LOGGER.info("Translating this connection to {}", target.getName());
        } catch (Throwable t) {
            channel.attr(ViaSession.KEY).remove();
            LOGGER.error("Could not set up translation to {}; the connection stays on 1.8", target.getName(), t);
            ViaConnect.notifyLater("跨版本转换没能启动", "这次按 1.8 连接（" + t.getClass().getSimpleName() + "）");
            if (t instanceof LinkageError) throw (LinkageError) t;
        }
    }

    /** After compression is turned on, ViaVersion has to sit between (de)compression and the codec again. */
    public static void afterCompression(Channel channel) {
        if (ViaSession.of(channel) == null) return;
        ViaChannelInitializer.reorderPipeline(channel.pipeline(), "compress", "decompress");
    }

    /** True when this connection's encryption has to be done the 1.6.4 way. */
    public static boolean legacyEncryption(Channel channel) {
        ViaSession session = ViaSession.of(channel);
        return session != null && session.target.olderThanOrEqualTo(LegacyProtocolVersion.r1_6_4);
    }

    /**
     * 1.6.4 turns encryption on one side at a time: encryption now (before the length remover,
     * since the wire has no length prefix), decryption once the server says so.
     */
    public static void enableLegacyEncryption(Channel channel, Cipher decrypt, io.netty.channel.ChannelHandler encoder) {
        ViaSession session = ViaSession.of(channel);
        if (session == null) return;
        session.pendingDecryption = decrypt;
        channel.pipeline().addBefore(PreNettyLengthRemover.NAME, "encrypt", encoder);
    }

    /** Called by ViaLegacy when the 1.6.4 server starts encrypting what it sends. */
    public static void enableLegacyDecryption(Channel channel) {
        ViaSession session = ViaSession.of(channel);
        if (session == null || session.pendingDecryption == null) return;
        Cipher cipher = session.pendingDecryption;
        session.pendingDecryption = null;
        // Raw bytes are decrypted before the pre-netty splitter puts length prefixes in.
        channel.pipeline().addBefore(PreNettyLengthPrepender.NAME, "decrypt", new NettyEncryptingDecoder(cipher));
    }

    /** True when the server needs no session check (offline 1.6.4 and older), so joinServer is skipped. */
    public static boolean skipLegacySessionJoin(Channel channel) {
        ViaSession session = ViaSession.of(channel);
        if (session == null || !session.target.olderThanOrEqualTo(LegacyProtocolVersion.r1_6_4)) return false;
        net.raphimc.vialegacy.protocol.release.r1_6_4tor1_7_2_5.storage.ProtocolMetadataStorage metadata =
                session.user.get(net.raphimc.vialegacy.protocol.release.r1_6_4tor1_7_2_5.storage.ProtocolMetadataStorage.class);
        return metadata != null && !metadata.isAuthenticate();
    }

    // ------------------------------------------------------------------ current connection

    /** The version the current server connection is translated to, or null when it is plain 1.8. */
    public static ProtocolVersion currentTarget() {
        try {
            net.minecraft.client.network.NetHandlerPlayClient handler = net.minecraft.client.Minecraft.getMinecraft().getNetHandler();
            if (handler == null) return null;
            ViaSession session = ViaSession.of(handler.getNetworkManager().channel());
            return session == null ? null : session.target;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when the current connection is translated to at least this version. */
    public static boolean atLeast(ProtocolVersion version) {
        ProtocolVersion target = currentTarget();
        return target != null && target.newerThanOrEqualTo(version);
    }

    // ------------------------------------------------------------------ configuration phase (1.20.2+)

    /** The client's settings and brand, as a modern client sends them in configuration. */
    public static final class Hello {
        final String language, brand;
        final int viewDistance, chatVisibility, skinParts;
        final boolean colors;

        /**
         * @param chatVisibility 0 full, 1 commands only, 2 hidden
         * @param skinParts      the displayed skin parts bit mask
         */
        public Hello(String language, int viewDistance, int chatVisibility, boolean colors, int skinParts, String brand) {
            this.language = language;
            this.viewDistance = viewDistance;
            this.chatVisibility = chatVisibility;
            this.colors = colors;
            this.skinParts = skinParts;
            this.brand = brand;
        }
    }

    /** Where the settings come from; the client points this at its own options (see ViaHooks.start). */
    public static volatile java.util.concurrent.Callable<Hello> helloSource = () -> new Hello("en_US", 8, 0, true, 127, "vanilla");

    /**
     * Sends what a modern client sends at the start of configuration: its settings and its brand.
     * They cannot come from the 1.8 client itself (it only sends them after Join Game), so they are
     * written here as 1.20.2 configuration packets and translated onward to the server's version.
     * Scheduled on the event loop, so they leave right after ViaBackwards' login acknowledgement.
     */
    static void sendConfigurationHello(Channel channel) throws Exception {
        ViaSession session = ViaSession.of(channel);
        if (session == null || !session.target.newerThanOrEqualTo(ProtocolVersion.v1_20_2)) return;
        Hello hello = helloSource.call();
        com.viaversion.viaversion.api.protocol.packet.PacketWrapper information = com.viaversion.viaversion.api.protocol.packet.PacketWrapper.create(
                com.viaversion.viaversion.protocols.v1_20to1_20_2.packet.ServerboundConfigurationPackets1_20_2.CLIENT_INFORMATION, session.user);
        String language = hello.language == null ? "en_US" : hello.language;
        information.write(com.viaversion.viaversion.api.type.Types.STRING, language.length() > 16 ? language.substring(0, 16) : language);
        information.write(com.viaversion.viaversion.api.type.Types.BYTE, (byte) Math.max(2, Math.min(32, hello.viewDistance)));
        information.write(com.viaversion.viaversion.api.type.Types.VAR_INT, hello.chatVisibility);
        information.write(com.viaversion.viaversion.api.type.Types.BOOLEAN, hello.colors);
        information.write(com.viaversion.viaversion.api.type.Types.UNSIGNED_BYTE, (short) (hello.skinParts & 0xFF));
        information.write(com.viaversion.viaversion.api.type.Types.VAR_INT, 1); // main hand: right, 1.8 has no choice
        information.write(com.viaversion.viaversion.api.type.Types.BOOLEAN, false); // text filtering
        information.write(com.viaversion.viaversion.api.type.Types.BOOLEAN, true); // allow server listings
        information.scheduleSendToServer(com.viaversion.viabackwards.protocol.v1_20_2to1_20.Protocol1_20_2To1_20.class);

        io.netty.buffer.ByteBuf text = io.netty.buffer.Unpooled.buffer();
        com.viaversion.viaversion.api.type.Types.STRING.write(text, hello.brand == null ? "vanilla" : hello.brand);
        byte[] data = new byte[text.readableBytes()];
        text.readBytes(data);
        text.release();
        com.viaversion.viaversion.api.protocol.packet.PacketWrapper payload = com.viaversion.viaversion.api.protocol.packet.PacketWrapper.create(
                com.viaversion.viaversion.protocols.v1_20to1_20_2.packet.ServerboundConfigurationPackets1_20_2.CUSTOM_PAYLOAD, session.user);
        payload.write(com.viaversion.viaversion.api.type.Types.STRING, "minecraft:brand");
        payload.write(com.viaversion.viaversion.api.type.Types.REMAINING_BYTES, data);
        payload.scheduleSendToServer(com.viaversion.viabackwards.protocol.v1_20_2to1_20.Protocol1_20_2To1_20.class);
        LOGGER.info("Configuration phase: sent client settings and brand to the {} server", session.target.getName());
    }

    /**
     * What a connection that is past login is doing, for the connecting screen: a 1.20.2+ server
     * configuring the player, or a dialog answer on its way. Null when there is nothing to add.
     */
    public static String loginStage(Channel channel) {
        ViaSession session = ViaSession.of(channel);
        if (session == null) return null;
        String dialog = ViaDialogs.stage(session);
        if (dialog != null) return dialog;
        return session.configurationSince > 0 ? "正在同步服务器配置…" : null;
    }

    /** True once the settings and brand went out for this connection (for the tests). */
    static boolean configurationHelloSent(Channel channel) {
        ViaSession session = ViaSession.of(channel);
        return session != null && session.configurationHelloSent;
    }

    /** 1.9+ servers leave a player's own placement sounds to the client. */
    public static boolean clientPlacesSounds() {
        return atLeast(ProtocolVersion.v1_9);
    }

    /** The chat length limit for the current connection (1.11+ allows 256). */
    public static int chatLimit(int vanilla) {
        return atLeast(ProtocolVersion.v1_11) ? 256 : vanilla;
    }

    /** The F3 line for a translated connection, or null. */
    public static String debugLine() {
        ProtocolVersion target = currentTarget();
        return target == null ? null : "BetterMyau Via: " + target.getName() + " (" + target.getOriginalVersion() + ")";
    }

    /** For the self-test: the platform's loaded state. */
    static boolean viaLoaded() {
        return Via.isLoaded();
    }
}
