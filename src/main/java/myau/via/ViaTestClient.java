package myau.via;

import com.mojang.authlib.GameProfile;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NettyCompressionDecoder;
import net.minecraft.network.NettyCompressionEncoder;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.server.S00PacketDisconnect;
import net.minecraft.network.login.server.S02PacketLoginSuccess;
import net.minecraft.network.login.server.S03PacketEnableCompression;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C15PacketClientSettings;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.client.C19PacketResourcePackStatus;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.network.play.server.S48PacketResourcePackSend;
import net.minecraft.util.MessageDeserializer;
import net.minecraft.util.MessageDeserializer2;
import net.minecraft.util.MessageSerializer;
import net.minecraft.util.MessageSerializer2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A headless 1.8.9 client for the offline checks: Minecraft's own codec handlers, named as in
 * {@code NetworkManager$5}, plus the real {@link ViaClient#inject}. It does what the game does on
 * the wire: answers keep-alives and transactions, confirms the spawn teleport, ticks movement, and
 * sends its settings and brand after Join Game. Never used by the game itself.
 */
final class ViaTestClient extends SimpleChannelInboundHandler<Packet<?>> {
    final ProtocolVersion target;
    final CountDownLatch loggedIn = new CountDownLatch(1), joined = new CountDownLatch(1), done = new CountDownLatch(1);
    final Map<String, Integer> received = new TreeMap<>();
    /** Ids of what went to the server, right after ViaVersion's encoder (the server's own format). */
    final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
    final long started = System.currentTimeMillis();
    volatile Channel channel;
    volatile String failure;
    volatile int total;
    /** Answer a resource pack request only after this long. */
    long packDelayMs;
    private boolean spawned;

    private ViaTestClient(ProtocolVersion target) {
        this.target = target;
    }

    /** Connects, then sends the handshake and login start of a 1.8.9 client. */
    static ViaTestClient connect(EventLoopGroup group, String host, int port, ProtocolVersion target, String name) {
        final ViaTestClient client = new ViaTestClient(target);
        client.channel = new Bootstrap().group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.config().setOption(ChannelOption.TCP_NODELAY, true);
                ch.attr(NetworkManager.attrKeyConnectionState).set(EnumConnectionState.HANDSHAKING);
                ch.pipeline()
                        .addLast("timeout", new ReadTimeoutHandler(60))
                        .addLast("splitter", new MessageDeserializer2())
                        .addLast("decoder", new MessageDeserializer(EnumPacketDirection.CLIENTBOUND))
                        .addLast("prepender", new MessageSerializer2())
                        .addLast("encoder", new MessageSerializer(EnumPacketDirection.SERVERBOUND))
                        .addLast("packet_handler", client);
                NetworkManager marker = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
                ViaConnect.bindForTest(marker, target);
                ViaClient.inject(ch, marker);
                client.probe(ch);
            }
        }).connect(host, port).syncUninterruptibly().channel();
        client.send(new C00Handshake(47, host, port, EnumConnectionState.LOGIN));
        client.send(new C00PacketLoginStart(new GameProfile((UUID) null, name)));
        return client;
    }

    boolean configurationHelloSent() {
        return ViaClient.configurationHelloSent(channel);
    }

    void close() {
        if (channel != null) channel.close().syncUninterruptibly();
    }

    private void probe(Channel ch) {
        if (ch.pipeline().get("probe") != null) ch.pipeline().remove("probe");
        ch.pipeline().addBefore(com.viaversion.viaversion.platform.ViaEncodeHandler.NAME, "probe", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (msg instanceof ByteBuf && sent.size() < 24) {
                    ByteBuf buf = (ByteBuf) msg;
                    int id = 0, shift = 0, index = buf.readerIndex();
                    while (index < buf.writerIndex()) {
                        int b = buf.getByte(index++);
                        id |= (b & 0x7F) << shift;
                        shift += 7;
                        if ((b & 0x80) == 0) break;
                    }
                    sent.add(String.format("0x%02x(%dB)", id, buf.readableBytes()));
                }
                super.write(ctx, msg, promise);
            }
        });
    }

    void send(final Packet<?> packet) {
        // As NetworkManager.dispatchPacket: on the channel's own thread, the packet's state decides how it is encoded.
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(() -> send(packet));
            return;
        }
        channel.attr(NetworkManager.attrKeyConnectionState).set(EnumConnectionState.getFromPacket(packet));
        final String name = packet.getClass().getSimpleName();
        channel.writeAndFlush(packet).addListener(future -> {
            if (!future.isSuccess()) {
                if (future.cause() != null) future.cause().printStackTrace(System.out);
                fail("sending " + name + " failed: " + future.cause());
            }
        });
    }

    void fail(String why) {
        if (failure == null) failure = why;
        loggedIn.countDown();
        joined.countDown();
        done.countDown();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet<?> packet) {
        total++;
        String name = packet.getClass().getSimpleName();
        Integer count = received.get(name);
        received.put(name, count == null ? 1 : count + 1);

        if (packet instanceof S03PacketEnableCompression) {
            int threshold = ((S03PacketEnableCompression) packet).getCompressionTreshold();
            // As NetworkManager.setCompressionTreshold, then the real cross-version reorder.
            ctx.pipeline().addBefore("decoder", "decompress", new NettyCompressionDecoder(threshold));
            ctx.pipeline().addBefore("encoder", "compress", new NettyCompressionEncoder(threshold));
            ViaClient.afterCompression(ctx.channel());
            probe(ctx.channel());
        } else if (packet instanceof S02PacketLoginSuccess) {
            ctx.channel().attr(NetworkManager.attrKeyConnectionState).set(EnumConnectionState.PLAY);
            loggedIn.countDown();
        } else if (packet instanceof S00PacketDisconnect) {
            fail("kicked during login: " + ((S00PacketDisconnect) packet).func_149603_c().getUnformattedText());
        } else if (packet instanceof S40PacketDisconnect) {
            fail("kicked: " + ((S40PacketDisconnect) packet).getReason().getUnformattedText());
        } else if (packet instanceof S00PacketKeepAlive) {
            send(new C00PacketKeepAlive(((S00PacketKeepAlive) packet).func_149134_c()));
        } else if (packet instanceof S32PacketConfirmTransaction) {
            S32PacketConfirmTransaction transaction = (S32PacketConfirmTransaction) packet;
            if (!transaction.func_148888_e()) send(new C0FPacketConfirmTransaction(transaction.getWindowId(), transaction.getActionNumber(), true));
        } else if (packet instanceof S48PacketResourcePackSend) {
            final String hash = ((S48PacketResourcePackSend) packet).getHash();
            ctx.executor().schedule(() -> send(new C19PacketResourcePackStatus(hash, C19PacketResourcePackStatus.Action.DECLINED)),
                    packDelayMs, TimeUnit.MILLISECONDS);
        } else if (packet instanceof S01PacketJoinGame) {
            // What the client does after Join Game: settings and brand.
            send(new C15PacketClientSettings("en_US", 4, net.minecraft.entity.player.EntityPlayer.EnumChatVisibility.FULL, true, 127));
            send(new C17PacketCustomPayload("MC|Brand", new PacketBuffer(io.netty.buffer.Unpooled.buffer()).writeString("fml,forge")));
            joined.countDown();
        } else if (packet instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook look = (S08PacketPlayerPosLook) packet;
            send(new C03PacketPlayer.C06PacketPlayerPosLook(look.getX(), look.getY(), look.getZ(), look.getYaw(), look.getPitch(), false));
            if (!spawned) {
                spawned = true;
                // The client ticks 20 times a second from now on.
                ctx.executor().scheduleAtFixedRate(() -> send(new C03PacketPlayer(false)), 50, 50, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (failure == null && joined.getCount() > 0) fail("connection closed before Join Game");
        done.countDown();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace(System.out);
        fail("pipeline error: " + cause);
        ctx.close();
    }
}
