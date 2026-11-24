package myau.via;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.multiplayer.ServerData;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline check of the bundled ViaVersion stack on the game's own Java 8 and libraries, without
 * starting the game: loads everything, makes sure every offered version has a path from 1.8 with
 * its mapping data loaded, and sends a 1.8 handshake and login through a translated channel for a
 * few versions. Run by the {@code viaSelfTest} Gradle task; exits non-zero on any failure.
 */
public final class ViaSelfTest {
    private ViaSelfTest() { }

    public static void main(String[] args) {
        File folder = new File(args.length > 0 ? args[0] : "via-selftest");
        List<String> failures = new ArrayList<>();
        long started = System.currentTimeMillis();

        ViaClient.load(folder, "selftest");
        if (!ViaClient.ready()) {
            System.out.println("[ViaSelfTest] FAIL: ViaVersion did not load: " + ViaClient.problem());
            System.exit(1);
        }
        System.out.println("[ViaSelfTest] loaded in " + (System.currentTimeMillis() - started) + " ms; sub-platforms: " + Via.getManager().getSubPlatforms());
        if (!ViaPingBridge.installed()) failures.add("1.17+ ping bridge not installed");

        List<ProtocolVersion> versions = ViaVersions.selectable();
        System.out.println("[ViaSelfTest] selectable versions: " + versions.size() + " (newest " + ViaVersions.newestRelease() + ")");
        if (versions.size() < 60) failures.add("only " + versions.size() + " versions selectable");

        // Every protocol on every path, with its mapping data.
        Set<Class<?>> protocols = new LinkedHashSet<>();
        for (ProtocolVersion version : versions) {
            if (version.equals(ViaVersions.nativeVersion())) continue;
            List<ProtocolPathEntry> path = Via.getManager().getProtocolManager().getProtocolPath(ViaVersions.nativeVersion(), version);
            if (path == null) {
                failures.add("no path to " + version);
                continue;
            }
            for (ProtocolPathEntry entry : path) protocols.add(entry.protocol().getClass());
        }
        for (Class<?> protocol : protocols) {
            try {
                @SuppressWarnings("unchecked")
                Class<? extends com.viaversion.viaversion.api.protocol.Protocol> type =
                        (Class<? extends com.viaversion.viaversion.api.protocol.Protocol>) protocol;
                Via.getManager().getProtocolManager().completeMappingDataLoading(type);
            } catch (Throwable t) {
                failures.add("mapping data of " + protocol.getSimpleName() + ": " + t);
                t.printStackTrace(System.out);
            }
        }
        System.out.println("[ViaSelfTest] protocols on paths: " + protocols.size());

        // Handshake + login start through a translated channel.
        ProtocolVersion[] probes = {
                ViaVersions.newestRelease(), ProtocolVersion.v1_21_4, ProtocolVersion.v1_20_5, ProtocolVersion.v1_17,
                ProtocolVersion.v1_13, ProtocolVersion.v1_12_2, ProtocolVersion.v1_9, ProtocolVersion.v1_7_6
        };
        for (ProtocolVersion probe : probes) {
            if (probe == null) continue;
            try {
                int protocol = handshake(probe);
                if (protocol != probe.getOriginalVersion()) {
                    failures.add("handshake to " + probe + " announced protocol " + protocol);
                } else {
                    System.out.println("[ViaSelfTest] handshake + login to " + probe.getName() + " OK (protocol " + protocol + ")");
                }
            } catch (Throwable t) {
                failures.add("handshake to " + probe + ": " + t);
                t.printStackTrace(System.out);
            }
        }

        // Server list ping (status) through a translated channel, then ViaStatusFix: a server on the
        // chosen version reads as compatible (47), one on another version keeps its number.
        for (ProtocolVersion probe : new ProtocolVersion[]{ViaVersions.newestRelease(), ProtocolVersion.v1_12_2}) {
            try {
                int reported = statusPing(probe);
                int shown = statusFix(probe, reported);
                int other = statusFix(probe, ProtocolVersion.v1_9.getOriginalVersion());
                if (reported != probe.getOriginalVersion()) failures.add("status ping to " + probe + " reported " + reported);
                else if (shown != 47) failures.add("status ping to " + probe + " shows protocol " + shown + " instead of 47");
                else if (other == 47) failures.add("a 1.9 server reads as compatible while " + probe + " is chosen");
                else System.out.println("[ViaSelfTest] status ping to " + probe.getName() + " OK (shown as compatible; other versions not)");
            } catch (Throwable t) {
                failures.add("status ping to " + probe + ": " + t);
                t.printStackTrace(System.out);
            }
        }

        // Regression (1.13.1): a 1.20.2+ server keeps the player in configuration after login and
        // sends keep-alives there; the 1.8 client's answers must reach it as configuration keep-alives.
        try {
            ViaLimboTest.run(failures);
        } catch (Throwable t) {
            failures.add("configuration limbo: " + t);
            t.printStackTrace(System.out);
        }

        // Regression (1.13.0): a server that refuses the connection is an ordinary failed ping or
        // connection; it must reach the caller and must not switch cross-version support off.
        try {
            refusedConnections(failures);
        } catch (Throwable t) {
            failures.add("refused-connection check: " + t);
            t.printStackTrace(System.out);
        }

        if (failures.isEmpty()) {
            System.out.println("[ViaSelfTest] ALL OK in " + (System.currentTimeMillis() - started) + " ms");
            System.exit(0);
        }
        for (String failure : failures) System.out.println("[ViaSelfTest] FAIL: " + failure);
        System.exit(1);
    }

    private static void refusedConnections(List<String> failures) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ProtocolVersion newest = ViaVersions.newestRelease();
        ServerData data = new ServerData("selftest", "127.0.0.1:" + port, false);
        ViaSettings.setOverride(data.serverIP, newest.getName());

        ViaHooks.pinging(data);
        boolean threw = false;
        try {
            ViaHooks.connectForPing(loopback, port, false);
        } catch (Throwable expected) {
            threw = true;
        }
        if (!threw) failures.add("ping to a closed port did not fail");
        if (!newest.getName().equals(ViaConnect.pingMode(data))) failures.add("ping did not use the server's version (mode " + ViaConnect.pingMode(data) + ")");

        threw = false;
        try {
            ViaHooks.connectFromScreen(loopback, port, false);
        } catch (Throwable expected) {
            threw = true;
        }
        if (!threw) failures.add("connection to a closed port did not fail");
        if (!newest.equals(ViaConnect.connecting)) failures.add("connection did not pick " + newest + " (picked " + ViaConnect.connecting + ")");

        if (ViaHooks.disabledReason() != null) failures.add("a refused connection switched cross-version off: " + ViaHooks.disabledReason());
        else if (ViaUi.status() != null) failures.add("status after a refused connection: " + ViaUi.status());
        else System.out.println("[ViaSelfTest] refused ping + connection: errors reach the caller, cross-version stays on");
        ViaSettings.setOverride(data.serverIP, null);
    }

    /** What the 1.8 client is shown after ViaStatusFix, for a server reporting {@code protocol}. */
    private static int statusFix(ProtocolVersion target, int protocol) {
        net.minecraft.network.ServerStatusResponse response = new net.minecraft.network.ServerStatusResponse();
        response.setProtocolVersionInfo(new net.minecraft.network.ServerStatusResponse.MinecraftProtocolVersionIdentifier("Paper", protocol));
        EmbeddedChannel channel = new EmbeddedChannel(new ViaStatusFix(target));
        channel.writeInbound(new net.minecraft.network.status.server.S00PacketServerInfo(response));
        Object out = channel.readInbound();
        channel.close();
        return ((net.minecraft.network.status.server.S00PacketServerInfo) out).getResponse().getProtocolVersionInfo().getProtocol();
    }

    /** A 1.8 status ping through ViaVersion; the server answers as {@code target}. Returns the protocol in the translated answer. */
    private static int statusPing(ProtocolVersion target) throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        UserConnection user = new UserConnectionImpl(channel, true);
        new ProtocolPipelineImpl(user);
        channel.attr(ViaSession.KEY).set(new ViaSession(target, user));
        channel.pipeline().addLast(ViaDecodeHandler.NAME, new ViaDecodeHandler(user));
        channel.pipeline().addLast(ViaEncodeHandler.NAME, new ViaEncodeHandler(user));

        ByteBuf handshake = Unpooled.buffer();
        writeVarInt(handshake, 0x00);
        writeVarInt(handshake, 47);
        writeString(handshake, "selftest.invalid");
        handshake.writeShort(25565);
        writeVarInt(handshake, 1);
        channel.writeOutbound(handshake);
        ByteBuf sent = (ByteBuf) channel.readOutbound();
        readVarInt(sent);
        if (readVarInt(sent) != target.getOriginalVersion()) throw new IllegalStateException("status handshake not translated");
        sent.release();
        ByteBuf request = Unpooled.buffer();
        writeVarInt(request, 0x00);
        channel.writeOutbound(request);
        Object requestOut = channel.readOutbound();
        if (requestOut instanceof ByteBuf) ((ByteBuf) requestOut).release();

        ByteBuf response = Unpooled.buffer();
        writeVarInt(response, 0x00);
        writeString(response, "{\"version\":{\"name\":\"Paper " + target.getName() + "\",\"protocol\":" + target.getOriginalVersion()
                + "},\"players\":{\"max\":20,\"online\":1},\"description\":{\"text\":\"selftest\"}}");
        channel.writeInbound(response);
        ByteBuf shown = (ByteBuf) channel.readInbound();
        if (shown == null) throw new IllegalStateException("status response was swallowed");
        if (readVarInt(shown) != 0x00) throw new IllegalStateException("status response changed packet id");
        byte[] json = new byte[readVarInt(shown)];
        shown.readBytes(json);
        shown.release();
        channel.close();
        com.google.gson.JsonObject root = new com.google.gson.JsonParser().parse(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        return root.getAsJsonObject("version").get("protocol").getAsInt();
    }

    /** Sends a 1.8 handshake (login intent) and login start; returns the protocol the server would see. */
    private static int handshake(ProtocolVersion target) throws Exception {
        // Netty 4.0's EmbeddedChannel wants at least one handler up front.
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        UserConnection user = new UserConnectionImpl(channel, true);
        new ProtocolPipelineImpl(user);
        channel.attr(ViaSession.KEY).set(new ViaSession(target, user));
        channel.pipeline().addLast(ViaEncodeHandler.NAME, new ViaEncodeHandler(user));

        ByteBuf handshake = Unpooled.buffer();
        writeVarInt(handshake, 0x00);
        writeVarInt(handshake, 47);
        writeString(handshake, "selftest.invalid");
        handshake.writeShort(25565);
        writeVarInt(handshake, 2);
        channel.writeOutbound(handshake);
        ByteBuf out = (ByteBuf) channel.readOutbound();
        if (out == null) throw new IllegalStateException("handshake was swallowed");
        if (readVarInt(out) != 0x00) throw new IllegalStateException("handshake changed packet id");
        int protocol = readVarInt(out);
        out.release();

        ByteBuf login = Unpooled.buffer();
        writeVarInt(login, 0x00);
        writeString(login, "SelfTest");
        channel.writeOutbound(login);
        Object hello = channel.readOutbound();
        if (hello == null) throw new IllegalStateException("login start was swallowed");
        if (hello instanceof ByteBuf) ((ByteBuf) hello).release();
        channel.close();
        return protocol;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = buf.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IllegalStateException("VarInt too long");
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }
}
