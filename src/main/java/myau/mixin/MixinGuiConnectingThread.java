package myau.mixin;

import myau.via.ViaHooks;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Cross-version: the connecting screen's connector thread. Before it opens the connection the
 * version is decided there (it may wait for ViaVersion to load or ping the server first, which is
 * fine off the client thread).
 */
@SideOnly(Side.CLIENT)
@Mixin(targets = "net.minecraft.client.multiplayer.GuiConnecting$1")
public abstract class MixinGuiConnectingThread {
    @Redirect(method = "run", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkManager;createNetworkManagerAndConnect(Ljava/net/InetAddress;IZ)Lnet/minecraft/network/NetworkManager;"))
    private NetworkManager bettermyau$connectWithVia(InetAddress address, int port, boolean nativeTransport) throws IOException {
        return ViaHooks.connectFromScreen(address, port, nativeTransport);
    }
}
