package myau.mixin;

import myau.via.ViaHooks;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.OldServerPinger;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

/**
 * Cross-version: server list pings follow each server's version setting. A fixed version pings
 * through ViaVersion (which reports the server as compatible when it matches); automatic pings
 * plainly so the server's real protocol is seen.
 */
@SideOnly(Side.CLIENT)
@Mixin({OldServerPinger.class})
public abstract class MixinOldServerPinger {
    @Inject(method = "ping", at = @At("HEAD"))
    private void bettermyau$rememberServer(ServerData server, CallbackInfo ci) {
        ViaHooks.pinging(server);
    }

    @Redirect(method = "ping", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkManager;createNetworkManagerAndConnect(Ljava/net/InetAddress;IZ)Lnet/minecraft/network/NetworkManager;"))
    private NetworkManager bettermyau$pingWithVia(InetAddress address, int port, boolean nativeTransport) {
        return ViaHooks.connectForPing(address, port, nativeTransport);
    }
}
