package myau.via;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import javax.crypto.Cipher;

/**
 * One translated connection: the version it talks to the server with and its ViaVersion user,
 * kept on the Netty channel. Connections without translation have no session.
 */
public final class ViaSession {
    static final AttributeKey<ViaSession> KEY = AttributeKey.valueOf("bettermyau_via_session");

    public final ProtocolVersion target;
    public final UserConnection user;
    /** 1.6.4 and older: the server turns on decryption later than encryption, so the cipher waits here. */
    volatile Cipher pendingDecryption;
    /** 1.20.2+: the client's settings and brand went out at the start of configuration. */
    volatile boolean configurationHelloSent;
    /** 1.20.2+: when configuration started (0 = not configuring), and what the server sent meanwhile (for the log). */
    volatile long configurationSince;
    boolean configurationReported;
    final StringBuilder configurationTrace = new StringBuilder();
    /** 1.21.6+: the dialog the server is showing while it configures (see ViaDialogs), or null. */
    volatile ViaDialogView dialog;

    ViaSession(ProtocolVersion target, UserConnection user) {
        this.target = target;
        this.user = user;
    }

    /** The session on this channel, or null when the connection is not translated. */
    public static ViaSession of(Channel channel) {
        return channel == null ? null : channel.attr(KEY).get();
    }
}
