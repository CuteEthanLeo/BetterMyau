package myau.via.platform;

import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import myau.via.ViaConnect;
import myau.via.ViaSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.Session;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.raphimc.vialegacy.protocol.release.r1_2_4_5tor1_3_1_2.provider.OldAuthProvider;
import net.raphimc.vialegacy.protocol.release.r1_6_4tor1_7_2_5.storage.HandshakeStorage;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.logging.Level;

/** The provider work that needs Minecraft: kept apart so ViaVersion can start without touching it. */
final class ClientProviders {
    private ClientProviders() { }

    /** Old (1.2.5 and earlier) servers check the session the same way 1.7+ ones do. */
    static void joinServer(String serverId) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        Session session = mc.getSession();
        mc.getSessionService().joinServer(session.getProfile(), session.getToken(), serverId);
    }

    /** 1.7 servers send names only; 1.8 needs the player's UUID. */
    static UUID loadUuid(String playerName) {
        final UUID[] found = new UUID[1];
        new YggdrasilAuthenticationService(Proxy.NO_PROXY, UUID.randomUUID().toString())
                .createProfileRepository()
                .findProfilesByNames(new String[]{playerName}, Agent.MINECRAFT, new ProfileLookupCallback() {
                    @Override
                    public void onProfileLookupSucceeded(GameProfile profile) {
                        found[0] = profile.getId();
                    }

                    @Override
                    public void onProfileLookupFailed(GameProfile profile, Exception exception) {
                    }
                });
        return found[0];
    }

    /** The player's name and skin, for players a 1.7 server lists by name only. */
    static com.viaversion.viaversion.api.minecraft.GameProfile loadProfile(UUID uuid) {
        GameProfile filled = Minecraft.getMinecraft().getSessionService().fillProfileProperties(new GameProfile(uuid, null), true);
        com.viaversion.viaversion.api.minecraft.GameProfile.Property[] properties =
                new com.viaversion.viaversion.api.minecraft.GameProfile.Property[filled.getProperties().size()];
        int i = 0;
        for (Map.Entry<String, Property> entry : filled.getProperties().entries()) {
            Property property = entry.getValue();
            properties[i++] = new com.viaversion.viaversion.api.minecraft.GameProfile.Property(
                    property.getName(), property.getValue(), property.getSignature());
        }
        return new com.viaversion.viaversion.api.minecraft.GameProfile(filled.getName(), filled.getId(), properties);
    }

    /**
     * Classic servers listed on BetaCraft check players with a per-server pass from BetaCraft.
     * Adapted from ViaForge (GPL-3.0), which took it from Classic4J.
     *
     * @return the pass, or null to use none
     */
    static String betaCraftMpPass(UserConnection user) {
        HandshakeStorage handshake = user.get(HandshakeStorage.class);
        if (handshake == null) return null;
        try {
            String server = InetAddress.getByName(handshake.hostname()).getHostAddress() + ":" + handshake.port();
            Via.getManager().getProviders().get(OldAuthProvider.class).sendAuthRequest(user, sha1(server));
            String url = "https://api.betacraft.uk/getmppass.jsp?user="
                    + URLEncoder.encode(user.getProtocolInfo().getUsername(), "UTF-8") + "&server=" + URLEncoder.encode(server, "UTF-8");
            StringBuilder response = new StringBuilder();
            try (InputStream in = new URL(url).openStream(); Scanner scanner = new Scanner(in, "UTF-8")) {
                while (scanner.hasNext()) response.append(scanner.next());
            }
            String pass = response.toString();
            if (pass.contains("FAILED") || pass.contains("SERVER NOT FOUND")) return "0";
            return pass;
        } catch (Throwable t) {
            Via.getPlatform().getLogger().log(Level.WARNING, "BetaCraft authentication failed", t);
            return "0";
        }
    }

    private static String sha1(byte[] input) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(input)) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static String sha1(String input) throws Exception {
        return sha1(input.getBytes(StandardCharsets.UTF_8));
    }

    /** 1.20.5+ servers can move the player to another server: reconnect there with the same version. */
    static void transfer(UserConnection user, String host, int port) {
        ViaSession session = ViaSession.of(user.getChannel());
        final ProtocolVersion target = session != null ? session.target : null;
        final String address = port == 25565 ? host : host + ":" + port;
        final Minecraft mc = Minecraft.getMinecraft();
        final Object handler = user.getChannel().pipeline().get("packet_handler");
        mc.addScheduledTask(() -> {
            ViaConnect.forceNextTarget(target);
            if (mc.theWorld != null) {
                mc.theWorld.sendQuittingDisconnectingPacket();
                mc.loadWorld(null);
            } else if (handler instanceof NetworkManager) {
                ((NetworkManager) handler).closeChannel(new ChatComponentText("Transferring to " + address));
            }
            FMLClientHandler.instance().connectToServer(new GuiMultiplayer(new GuiMainMenu()), new ServerData("Transfer", address, false));
        });
    }
}
