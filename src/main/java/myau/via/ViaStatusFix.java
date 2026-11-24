package myau.via;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.network.status.server.S00PacketServerInfo;

/**
 * Server list pings through ViaVersion: the server answers with its own protocol number, which
 * 1.8 shows as "outdated" for anything but 47. When the server speaks exactly the version this
 * connection is translated to, the answer is shown as 47 (compatible), like a native server. A
 * server on another version keeps its number, so a wrong pick still shows up in the list.
 * Sits right after the packet decoder, so it sees decoded packets.
 */
final class ViaStatusFix extends ChannelInboundHandlerAdapter {
    static final String NAME = "bettermyau_via_status";

    private final ProtocolVersion target;

    ViaStatusFix(ProtocolVersion target) {
        this.target = target;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof S00PacketServerInfo) {
            ServerStatusResponse response = ((S00PacketServerInfo) msg).getResponse();
            if (response != null && response.getProtocolVersionInfo() != null) {
                ServerStatusResponse.MinecraftProtocolVersionIdentifier info = response.getProtocolVersionInfo();
                if (speaks(target, info.getProtocol())) {
                    response.setProtocolVersionInfo(new ServerStatusResponse.MinecraftProtocolVersionIdentifier(
                            info.getName(), ViaVersions.nativeVersion().getVersion()));
                }
            }
        }
        super.channelRead(ctx, msg);
    }

    /** True when a server reporting {@code protocol} speaks {@code target}. */
    static boolean speaks(ProtocolVersion target, int protocol) {
        if (protocol == target.getOriginalVersion() || protocol == target.getVersion()) return true;
        ProtocolVersion reported = ProtocolVersion.getProtocol(protocol);
        return reported != null && reported.isKnown() && reported.equals(target);
    }
}
