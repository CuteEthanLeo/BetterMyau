package myau.via;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * ViaVersion's encoder; while a 1.20.2+ server configures, the client side is put back into play
 * before each client packet is translated (see {@link ViaClientDecodeHandler}). Only the 1.8
 * client's own packets pass here: what ViaVersion sends by itself starts after this handler.
 */
final class ViaClientEncodeHandler extends ViaEncodeHandler {
    private final ViaSession session;

    ViaClientEncodeHandler(UserConnection connection, ViaSession session) {
        super(connection);
        this.session = session;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf bytebuf, List<Object> out) throws Exception {
        ViaClientDecodeHandler.configuring(connection, session, ctx.channel());
        super.encode(ctx, bytebuf, out);
    }
}
