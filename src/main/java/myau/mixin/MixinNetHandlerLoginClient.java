package myau.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import myau.via.ViaHooks;
import net.minecraft.client.network.NetHandlerLoginClient;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cross-version, 1.6.4 and older: those servers send an encryption request in offline mode too,
 * but then expect no session join (as ViaForge does it).
 */
@SideOnly(Side.CLIENT)
@Mixin({NetHandlerLoginClient.class})
public abstract class MixinNetHandlerLoginClient {
    @Shadow @Final private NetworkManager networkManager;

    @Redirect(method = "handleEncryptionRequest", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;joinServer(Lcom/mojang/authlib/GameProfile;Ljava/lang/String;Ljava/lang/String;)V"))
    private void bettermyau$joinUnlessOfflineLegacy(MinecraftSessionService service, GameProfile profile, String token, String serverId) throws AuthenticationException {
        if (this.networkManager != null && ViaHooks.skipLegacySessionJoin(this.networkManager.channel())) return;
        service.joinServer(profile, token, serverId);
    }
}
