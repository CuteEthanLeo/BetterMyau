package myau.mixin;

import io.netty.channel.Channel;
import myau.via.ViaHooks;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cross-version: the channel initializer of {@code NetworkManager.createNetworkManagerAndConnect}
 * (servers and server list pings; singleplayer uses a local channel and another initializer).
 * Once vanilla has built the pipeline, ViaVersion goes in when the connection needs it.
 */
@SideOnly(Side.CLIENT)
@Mixin(targets = "net.minecraft.network.NetworkManager$5")
public abstract class MixinNetworkManagerChannelInit {
    @Inject(method = "initChannel", at = @At("TAIL"), remap = false)
    private void bettermyau$injectVia(Channel channel, CallbackInfo ci) {
        ViaHooks.inject(channel, channel.pipeline().get("packet_handler"));
    }
}
