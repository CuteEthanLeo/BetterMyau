package myau.ui.glass;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Player faces for the glass screens: the signed-in account's real skin, and other accounts'
 * skins by UUID. Lookups run one at a time on a daemon thread, at most about one a second (the
 * session server rate-limits), and are remembered for the session. Until a skin has arrived, and
 * for accounts without a UUID, the default skin for that name is shown.
 */
public final class Faces {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Map<String, ResourceLocation> SKINS = new ConcurrentHashMap<>();
    private static final Set<String> REQUESTED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BetterMyau Skin Lookup");
        thread.setDaemon(true);
        return thread;
    });

    private Faces() {
    }

    /** The signed-in account's skin; the default skin until the real one has been fetched. */
    public static ResourceLocation session() {
        GameProfile profile = mc.getSession().getProfile();
        return of(profile.getName(), profile.getId());
    }

    /** A skin for {@code name}; {@code id} may be null (offline accounts), then the default skin. */
    public static ResourceLocation of(String name, UUID id) {
        if (name == null) name = "";
        String key = id != null ? id.toString() : "offline:" + name;
        ResourceLocation known = SKINS.get(key);
        if (known != null) return known;
        UUID shown = id != null ? id : EntityPlayer.getOfflineUUID(name);
        if (id != null && REQUESTED.add(key)) {
            GameProfile profile = new GameProfile(id, name);
            LOOKUP.execute(() -> {
                try {
                    GameProfile filled = mc.getSessionService().fillProfileProperties(profile, false);
                    // Texture loading has to happen on the client thread.
                    mc.addScheduledTask(() -> mc.getSkinManager().loadProfileTextures(filled, (type, location, texture) -> {
                        if (type == MinecraftProfileTexture.Type.SKIN) SKINS.put(key, location);
                    }, false));
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // Offline or rate limited: the default skin stays.
                }
            });
        }
        return DefaultPlayerSkin.getDefaultSkin(shown);
    }

    /** The face and hat layers of {@code skin}, rounded with radius {@code r}. */
    public static void draw(float x, float y, float size, float r, ResourceLocation skin) {
        int texture = Draw.textureId(skin);
        if (texture <= 0) {
            Draw.rect(x, y, size, size, r, 0x40ffffff);
            return;
        }
        Draw.image(x, y, size, size, r, texture, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f);
        Draw.image(x, y, size, size, r, texture, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f);
    }
}
