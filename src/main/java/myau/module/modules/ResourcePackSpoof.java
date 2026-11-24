package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.util.PacketUtil;
import net.minecraft.network.play.client.C19PacketResourcePackStatus;
import net.minecraft.network.play.server.S48PacketResourcePackSend;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Tells the server its resource pack was accepted and loaded without downloading it.
 */
public class ResourcePackSpoof extends Module {
    public ResourcePackSpoof() {
        super("ResourcePackSpoof", false);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) return;
        if (!(event.getPacket() instanceof S48PacketResourcePackSend)) return;
        // Already rejected (and answered) by ClientFixes
        if (event.isCancelled() || mc.getNetHandler() == null) return;
        S48PacketResourcePackSend packet = (S48PacketResourcePackSend) event.getPacket();
        String url = packet.getURL();
        String hash = packet.getHash();

        // The client never sees the request: no prompt, no download, and no second status from vanilla.
        event.setCancelled(true);

        try {
            if (url == null) {
                throw new URISyntaxException("null", "Missing url");
            }
            String scheme = new URI(url).getScheme();
            boolean isLevelProtocol = "level".equals(scheme);
            if (!"http".equals(scheme) && !"https".equals(scheme) && !isLevelProtocol) {
                throw new URISyntaxException(url, "Wrong protocol");
            }
            if (isLevelProtocol && (url.contains("..") || !url.endsWith("/resources.zip"))) {
                throw new URISyntaxException(url, "Invalid levelstorage resourcepack path");
            }
            PacketUtil.sendPacket(new C19PacketResourcePackStatus(hash, C19PacketResourcePackStatus.Action.ACCEPTED));
            PacketUtil.sendPacket(new C19PacketResourcePackStatus(hash, C19PacketResourcePackStatus.Action.SUCCESSFULLY_LOADED));
        } catch (URISyntaxException e) {
            Myau.LOGGER.error("Failed to handle resource pack", e);
            PacketUtil.sendPacket(new C19PacketResourcePackStatus(hash, C19PacketResourcePackStatus.Action.FAILED_DOWNLOAD));
        }
    }
}
