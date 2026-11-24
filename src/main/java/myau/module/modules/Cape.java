package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * Gives the local player one of the capes bundled with the client. Local only: other players are
 * not touched and nothing is downloaded. {@code MixinAbstractClientPlayer.getLocationCape} asks
 * {@link #getCape()}.
 */
public class Cape extends Module {
    private static final String FOLDER = "bettermyau/cape/";
    private static final String[] NAMES = new String[]{"BILIBILI", "WHITE", "STYLES", "OPAL", "AUGUSTUS", "RAVEN_ANIME",
            "TRANS", "CHINA", "USA", "JAPAN", "KOREA", "UK", "FRANCE", "GERMANY", "RUSSIA", "ITALY", "SPAIN", "CANADA", "BRAZIL", "AUSTRALIA", "DUTCH"};
    private static final String[] FILES = new String[]{"bilibili", "white", "styles", "opal", "augustus", "ravenanime",
            "trans", "china", "usa", "japan", "korea", "uk", "france", "germany", "russia", "italy", "spain", "canada", "brazil", "australia", "dutch"};
    private static final int UNKNOWN = 0, READY = 1, MISSING = 2;

    public final ModeProperty cape = new ModeProperty("cape", 0, NAMES);

    private final ResourceLocation[] locations = new ResourceLocation[FILES.length];
    private final int[] states = new int[FILES.length];

    public Cape() {
        super("Cape", false);
    }

    /** The module, or null before the client has started or while it is not registered. */
    public static Cape instance() {
        return Myau.moduleManager == null ? null : (Cape) Myau.moduleManager.modules.get(Cape.class);
    }

    /** Texture of the selected cape, or null when it cannot be loaded. Render thread only. */
    public ResourceLocation getCape() {
        int index = this.cape.getValue();
        if (index < 0 || index >= FILES.length) {
            return null;
        }
        if (this.states[index] == UNKNOWN) {
            this.states[index] = this.prepare(index) ? READY : MISSING;
        }
        return this.states[index] == READY ? this.locations[index] : null;
    }

    /**
     * Normally the texture manager loads the file by itself on first bind. When the resource
     * manager does not serve the client's assets, the image is read from the classpath and
     * registered under the same location instead.
     */
    private boolean prepare(int index) {
        ResourceLocation location = new ResourceLocation(FOLDER + FILES[index] + ".png");
        this.locations[index] = location;
        try {
            IOUtils.closeQuietly(mc.getResourceManager().getResource(location).getInputStream());
            return true;
        } catch (IOException | RuntimeException ignored) {
            // Not in any resource pack: fall through to the classpath.
        }
        InputStream stream = Cape.class.getResourceAsStream("/assets/minecraft/" + FOLDER + FILES[index] + ".png");
        if (stream == null) {
            Myau.LOGGER.warn("Cape texture {} is missing", location);
            return false;
        }
        try {
            // readBufferedImage closes the stream.
            mc.getTextureManager().loadTexture(location, new DynamicTexture(TextureUtil.readBufferedImage(stream)));
            return true;
        } catch (IOException | RuntimeException e) {
            Myau.LOGGER.warn("Cape texture {} could not be read", location, e);
            return false;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.cape.getModeString())};
    }
}
