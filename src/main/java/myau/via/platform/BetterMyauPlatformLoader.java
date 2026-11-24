package myau.via.platform;

import com.viaversion.viabackwards.protocol.v1_20_5to1_20_3.provider.TransferProvider;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.Protocol1_21_6To1_21_5;
import com.viaversion.viabackwards.protocol.v1_21_6to1_21_5.provider.DialogViewProvider;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.platform.providers.ViaProviders;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;
import com.viaversion.viaversion.protocol.version.BaseVersionProvider;
import com.viaversion.viaversion.protocols.v1_8to1_9.provider.MovementTransmitterProvider;
import myau.via.ViaClient;
import myau.via.ViaSession;
import net.raphimc.vialegacy.protocol.classic.c0_28_30toa1_0_15.provider.ClassicMpPassProvider;
import net.raphimc.vialegacy.protocol.release.r1_2_4_5tor1_3_1_2.provider.OldAuthProvider;
import net.raphimc.vialegacy.protocol.release.r1_6_4tor1_7_2_5.provider.EncryptionProvider;
import net.raphimc.vialegacy.protocol.release.r1_7_6_10tor1_8.provider.GameProfileFetcher;

/**
 * Registers what ViaVersion and its add-ons need from the client: which version each connection
 * talks to, and the pieces of old-version login (session join, profile lookups, 1.6.4 encryption,
 * BetaCraft classic passes) plus 1.20.5+ server transfers and 1.21.6+ dialogs. The providers that touch Minecraft live
 * in {@link ClientProviders}, which is only loaded when one of them is actually used.
 */
public final class BetterMyauPlatformLoader implements ViaPlatformLoader {
    @Override
    public void load() {
        ViaProviders providers = Via.getManager().getProviders();
        providers.use(VersionProvider.class, new TargetVersionProvider());
        providers.use(MovementTransmitterProvider.class, new MovementTransmitterProvider() {
            @Override
            public void sendPlayer(UserConnection connection) {
                // The 1.8 client sends its idle movement packet every tick by itself.
            }
        });
        providers.use(OldAuthProvider.class, new OldAuthProvider() {
            @Override
            public void sendAuthRequest(UserConnection user, String serverId) throws Throwable {
                ClientProviders.joinServer(serverId);
            }
        });
        providers.use(GameProfileFetcher.class, new GameProfileFetcher() {
            @Override
            public java.util.UUID loadMojangUuid(String playerName) throws Exception {
                return ClientProviders.loadUuid(playerName);
            }

            @Override
            public com.viaversion.viaversion.api.minecraft.GameProfile loadGameProfile(java.util.UUID uuid) throws Exception {
                return ClientProviders.loadProfile(uuid);
            }
        });
        providers.use(EncryptionProvider.class, new EncryptionProvider() {
            @Override
            public void enableDecryption(UserConnection user) {
                ViaClient.enableLegacyDecryption(user.getChannel());
            }
        });
        providers.use(ClassicMpPassProvider.class, new ClassicMpPassProvider() {
            @Override
            public String getMpPass(UserConnection user) {
                String pass = ClientProviders.betaCraftMpPass(user);
                return pass != null ? pass : super.getMpPass(user);
            }
        });
        providers.use(TransferProvider.class, (user, host, port) -> ClientProviders.transfer(user, host, port));
        // 1.21.6+ dialogs sent before Join Game: shown by the client, not as a chest (see ViaDialogs).
        Protocol1_21_6To1_21_5 dialogs = Via.getManager().getProtocolManager().getProtocol(Protocol1_21_6To1_21_5.class);
        if (dialogs != null) providers.use(DialogViewProvider.class, new NativeDialogProvider(dialogs));
    }

    @Override
    public void unload() {
    }

    /** Each connection talks to the version chosen for it when it was opened. */
    static final class TargetVersionProvider extends BaseVersionProvider {
        @Override
        public ProtocolVersion getClosestServerProtocol(UserConnection connection) throws Exception {
            if (connection.isClientSide()) {
                ViaSession session = ViaSession.of(connection.getChannel());
                if (session != null) return session.target;
            }
            return super.getClosestServerProtocol(connection);
        }
    }
}
