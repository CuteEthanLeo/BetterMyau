package myau.ui.glass;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import myau.Myau;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.LanServerDetector;
import net.minecraft.client.network.OldServerPinger;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.regex.Pattern;

/**
 * One row of the glass server list: a saved server or a LAN world, with its icon texture and
 * the animation state of its card. Pinging and icon handling follow vanilla's
 * {@code ServerListEntryNormal} exactly, so the list behaves the same, only drawn differently.
 */
final class ServerSlot {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Pattern CODES = Pattern.compile("§.");

    /** Same shape as vanilla's pool: a few daemon threads, pings run in parallel. */
    private static final ScheduledThreadPoolExecutor PINGER = new ScheduledThreadPoolExecutor(5,
            new ThreadFactoryBuilder().setNameFormat("BetterMyau Server Pinger #%d").setDaemon(true).build());

    /** Uploaded icons by address: reopening the screen does not decode and upload them again. */
    private static final Map<String, Icon> ICONS = new HashMap<>();

    private static final class Icon {
        final ResourceLocation location;
        DynamicTexture texture;
        String data;

        Icon(String address) {
            this.location = new ResourceLocation("servers/" + address.toLowerCase(Locale.ROOT) + "/icon");
        }
    }

    final ServerData data;
    final LanServerDetector.LanServer lan;

    // Card animation
    final Anim.Spring y = Anim.spring(0, 0.32f, 0.82f);
    final Interaction feel = new Interaction();
    final Anim.Smooth select = new Anim.Smooth(0, 16);
    final Anim.Smooth lift = new Anim.Smooth(0, 16);
    boolean placed;
    float appearDelay;

    ServerSlot(ServerData data) {
        this.data = data;
        this.lan = null;
    }

    ServerSlot(LanServerDetector.LanServer lan) {
        this.lan = lan;
        this.data = new ServerData(lan.getServerMotd(), lan.getServerIpPort(), true);
    }

    boolean isLan() {
        return lan != null;
    }

    String name() {
        return data.serverName == null ? "" : data.serverName;
    }

    String address() {
        return data.serverIP == null ? "" : data.serverIP;
    }

    // ---------------------------------------------------------------- ping

    boolean pinging() {
        return !isLan() && data.pingToServer == -2L;
    }

    boolean failed() {
        return !isLan() && data.pingToServer == -1L;
    }

    /** Vanilla pings a server the first time its row is drawn; so does this. */
    void pingIfNeeded(OldServerPinger pinger) {
        if (isLan() || data.field_78841_f) return;
        data.field_78841_f = true;
        data.pingToServer = -2L;
        data.serverMOTD = "";
        data.populationInfo = "";
        final ServerData server = data;
        PINGER.submit(() -> {
            try {
                pinger.ping(server);
            } catch (UnknownHostException e) {
                server.pingToServer = -1L;
                server.serverMOTD = EnumChatFormatting.DARK_RED + "Can't resolve hostname";
            } catch (Exception e) {
                server.pingToServer = -1L;
                server.serverMOTD = EnumChatFormatting.DARK_RED + "Can't connect to server.";
            }
        });
    }

    /** Ping again: on refresh, after an edit, or when a ping was cut off by leaving the screen. */
    void reping() {
        if (!isLan()) data.field_78841_f = false;
    }

    // ---------------------------------------------------------------- text

    /** The MOTD with its colours; LAN worlds show their address instead. */
    String motd() {
        if (isLan()) return "§7" + address();
        return data.serverMOTD == null ? "" : data.serverMOTD;
    }

    String population() {
        return isLan() || data.populationInfo == null ? "" : data.populationInfo;
    }

    /** Online / max from "§78§8/§7200", or null when the server did not say. */
    int[] players() {
        String plain = strip(population()).trim();
        int slash = plain.indexOf('/');
        if (slash <= 0) return null;
        try {
            return new int[]{Integer.parseInt(plain.substring(0, slash).trim()), Integer.parseInt(plain.substring(slash + 1).trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 47 is 1.8; anything else vanilla prints in red. With cross-version support a version the
     * server's setting translates to counts as compatible (see ViaUi).
     */
    boolean incompatible() {
        return !isLan() && data.pingToServer >= 0 && !myau.via.ViaUi.compatible(data);
    }

    String version() {
        return data.gameVersion == null ? "" : data.gameVersion;
    }

    boolean matches(String query) {
        if (query.isEmpty()) return true;
        return strip(name()).toLowerCase(Locale.ROOT).contains(query)
                || address().toLowerCase(Locale.ROOT).contains(query)
                || strip(motd()).toLowerCase(Locale.ROOT).contains(query);
    }

    static String strip(String text) {
        return text == null ? "" : CODES.matcher(text).replaceAll("");
    }

    // ---------------------------------------------------------------- icon

    /**
     * The icon texture's GL name, or 0 when the server has none. Uploads it when the data
     * changed, like vanilla; an icon that is not a 64x64 PNG is dropped from the server entry.
     *
     * @return true in {@code changed[0]} when the saved list should be written
     */
    int icon(boolean[] changed) {
        if (isLan()) return 0;
        String encoded = data.getBase64EncodedIconData();
        if (encoded == null) return 0;
        String key = address();
        Icon icon = ICONS.get(key);
        if (icon == null) {
            icon = new Icon(key);
            ICONS.put(key, icon);
        }
        if (!encoded.equals(icon.data)) {
            icon.data = encoded;
            if (!upload(icon, encoded)) {
                data.setBase64EncodedIconData(null);
                icon.data = null;
                if (icon.texture != null) {
                    mc.getTextureManager().deleteTexture(icon.location);
                    icon.texture = null;
                }
            }
            if (changed != null) changed[0] = true;
        }
        return icon.texture == null ? 0 : icon.texture.getGlTextureId();
    }

    private static boolean upload(Icon icon, String encoded) {
        BufferedImage image;
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8));
            image = TextureUtil.readBufferedImage(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
                throw new IllegalStateException("must be 64 pixels wide and high");
            }
        } catch (Throwable t) {
            Myau.LOGGER.error("Invalid icon for server {}", icon.location, t);
            return false;
        }
        if (icon.texture == null) {
            icon.texture = new DynamicTexture(64, 64);
            mc.getTextureManager().loadTexture(icon.location, icon.texture);
        }
        image.getRGB(0, 0, 64, 64, icon.texture.getTextureData(), 0, 64);
        icon.texture.updateDynamicTexture();
        return true;
    }
}
