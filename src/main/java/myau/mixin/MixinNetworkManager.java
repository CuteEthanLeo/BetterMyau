package myau.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.GenericFutureListener;
import myau.Myau;
import myau.event.EventManager;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.module.modules.Disabler;
import myau.via.ViaHooks;
import net.minecraft.network.NettyEncryptingEncoder;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.util.CryptManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.crypto.SecretKey;
import java.util.concurrent.Future;

@SideOnly(Side.CLIENT)
@Mixin(value = {NetworkManager.class}, priority = 9999)
public abstract class MixinNetworkManager {
    @Shadow private Channel channel;
    @Shadow private boolean isEncrypted;

    /**
     * Cross-version: ties the version chosen for this connection (carried on the calling thread)
     * to the new NetworkManager before its channel is initialized on the Netty thread.
     */
    @ModifyVariable(method = "createNetworkManagerAndConnect", at = @At("STORE"), ordinal = 0)
    private static NetworkManager bettermyau$bindViaTarget(NetworkManager manager) {
        return ViaHooks.bind(manager);
    }

    /** Cross-version: compression handlers go in after ViaVersion's, which must sit inside them. */
    @Inject(method = "setCompressionTreshold", at = @At("RETURN"))
    private void bettermyau$reorderVia(int threshold, CallbackInfo ci) {
        if (this.channel != null) ViaHooks.afterCompression(this.channel);
    }

    /**
     * Cross-version, 1.6.4 and older: no length prefix on the wire and decryption only once the
     * server asks for it, so encryption is wired up differently (see ViaClient).
     */
    @Inject(method = "enableEncryption", at = @At("HEAD"), cancellable = true)
    private void bettermyau$legacyEncryption(SecretKey key, CallbackInfo ci) {
        if (this.channel == null || !ViaHooks.legacyEncryption(this.channel)) return;
        ci.cancel();
        this.isEncrypted = true;
        ViaHooks.enableLegacyEncryption(this.channel, CryptManager.createNetCipherInstance(2, key),
                new NettyEncryptingEncoder(CryptManager.createNetCipherInstance(1, key)));
    }

    @Inject(
            method = {"channelRead0*"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void channelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo callbackInfo) {
        if (!packet.getClass().getName().startsWith("net.minecraft.network.play.client")) {
            if (Myau.delayManager != null && Myau.delayManager.shouldDelay((Packet<INetHandlerPlayClient>) packet)) {
                callbackInfo.cancel();
            } else if (Disabler.holdPostPacket(packet)) {
                callbackInfo.cancel();
            } else {
                PacketEvent event = new PacketEvent(EventType.RECEIVE, packet);
                EventManager.call(event);
                if (event.isCancelled()) {
                    callbackInfo.cancel();
                }
            }
        }
    }

    @Inject(
            method = {"sendPacket(Lnet/minecraft/network/Packet;)V"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void sendPacket(Packet<?> packet, CallbackInfo callbackInfo) {
        if (!packet.getClass().getName().startsWith("net.minecraft.network.play.server")) {
            PacketEvent event = new PacketEvent(EventType.SEND, packet);
            EventManager.call(event);
            if (event.isCancelled()) {
                callbackInfo.cancel();
            } else if (Myau.playerStateManager != null && Myau.blinkManager != null && Myau.lagManager != null) {
                if (!Myau.lagManager.isFlushing()) {
                    Myau.playerStateManager.handlePacket(packet);
                    if (Myau.blinkManager.isBlinking()) {
                        if (Myau.blinkManager.offerPacket(packet)) {
                            callbackInfo.cancel();
                            return;
                        }
                    }
                    if (Myau.lagManager.handlePacket(packet)) {
                        callbackInfo.cancel();
                    }
                }
            }
        }
    }

    @Inject(
            method = {"sendPacket(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;[Lio/netty/util/concurrent/GenericFutureListener;)V"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void sendPacket2(
            Packet<?> packet,
            GenericFutureListener<? extends Future<? super Void>> genericFutureListener,
            GenericFutureListener<? extends Future<? super Void>>[] arr,
            CallbackInfo callbackInfo
    ) {
        if (!packet.getClass().getName().startsWith("net.minecraft.network.play.server")) {
            if (Myau.playerStateManager != null && Myau.blinkManager != null && Myau.lagManager != null) {
                if (!Myau.lagManager.isFlushing()) {
                    Myau.playerStateManager.handlePacket(packet);
                    if (Myau.blinkManager.isBlinking()) {
                        if (Myau.blinkManager.offerPacket(packet)) {
                            callbackInfo.cancel();
                            return;
                        }
                    }
                    if (Myau.lagManager.handlePacket(packet)) {
                        callbackInfo.cancel();
                    }
                }
            }
        }
    }
}
