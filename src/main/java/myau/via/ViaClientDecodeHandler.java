package myau.via;

import com.viaversion.viabackwards.protocol.v1_20_2to1_20.storage.ConfigurationPacketStorage;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * ViaVersion's decoder, plus what a 1.8 client needs in a 1.20.2+ configuration phase.
 * <p>
 * After login such a server configures the player before Join Game; ViaBackwards stands in for
 * the client there. Via keeps one state per side and runs the client's packets through every
 * protocol with the client side's state; once the login is acknowledged that state becomes
 * "configuration". That suits a 1.20.0/1.20.1 client, whose packets reach ViaBackwards directly,
 * but a 1.8 client's play packets (keep-alive and transaction answers) first pass the 1.8 → 1.20
 * protocols, which know no configuration packets and drop them; a server that waits for a
 * keep-alive answer in configuration (anti-bot limbos do) never sends Join Game. So while
 * ViaBackwards configures, the client side stays in play, as the 1.8 client actually is:
 * ViaBackwards turns its keep-alive and the like into configuration packets itself, and Via
 * carries on from there as configuration (it takes the state from each packet's type). And as
 * soon as the login is through, the settings and brand a modern client sends then go out once.
 */
final class ViaClientDecodeHandler extends ViaDecodeHandler {
    private final ViaSession session;

    ViaClientDecodeHandler(UserConnection connection, ViaSession session) {
        super(connection);
        this.session = session;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf bytebuf, List<Object> out) throws Exception {
        if (session.configurationSince > 0 && session.configurationTrace.length() < 600) {
            // What the server sends while it configures (its own packet ids), for the log below.
            session.configurationTrace.append(String.format("%02x ", peekVarInt(bytebuf)));
        }
        try {
            super.decode(ctx, bytebuf, out);
        } finally {
            // Also after packets ViaVersion keeps to itself (the one that finishes configuration is
            // such a packet): it leaves decode with an exception for those.
            report();
        }
        configuring(connection, session, ctx.channel());
    }

    /** Logs how configuration went: when it finished, or what the server sent if it is still at it after 10 s. */
    private void report() {
        if (session.configurationSince == 0) return;
        ConfigurationPacketStorage storage = connection.get(ConfigurationPacketStorage.class);
        long elapsed = System.currentTimeMillis() - session.configurationSince;
        if (storage == null || storage.isFinished()) {
            ViaClient.LOGGER.info("Configuration finished after {} ms", elapsed);
            session.configurationSince = 0;
            // A dialog the server showed while configuring ends with it.
            ViaDialogs.dismiss(session);
        } else if (elapsed > 10_000 && !session.configurationReported) {
            session.configurationReported = true;
            ViaClient.LOGGER.warn("The {} server is still configuring after {} s; its packets so far (configuration ids): {}",
                    session.target.getName(), elapsed / 1000, session.configurationTrace.toString().trim());
        }
    }

    private static int peekVarInt(ByteBuf buf) {
        int value = 0, index = buf.readerIndex();
        for (int shift = 0; shift < 35 && index < buf.writerIndex(); shift += 7) {
            int b = buf.getByte(index++);
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
        }
        return value;
    }

    /** Called after the server's packets are translated and before the client's are. */
    static void configuring(UserConnection connection, ViaSession session, Channel channel) throws Exception {
        ConfigurationPacketStorage storage = connection.get(ConfigurationPacketStorage.class);
        if (storage == null || storage.isFinished()) return;
        if (session.configurationSince == 0 && !session.configurationReported) session.configurationSince = System.currentTimeMillis();
        if (!session.configurationHelloSent) {
            session.configurationHelloSent = true;
            // Scheduled after ViaBackwards' login acknowledgement, which it scheduled while decoding the login.
            ViaClient.sendConfigurationHello(channel);
        }
        ProtocolInfo info = connection.getProtocolInfo();
        if (info.getClientState() != State.PLAY) info.setClientState(State.PLAY);
    }
}
