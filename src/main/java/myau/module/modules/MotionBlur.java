package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.ui.glass.MotionBlurRenderer;

/**
 * Real camera motion blur: a per-pixel velocity buffer reprojected from depth, gathered with a
 * tile-based reconstruction filter (see {@link MotionBlurRenderer}). Only the world is blurred;
 * the hand, ESP, HUD and screens stay sharp. Rendering is driven from MixinEntityRenderer.
 */
public class MotionBlur extends Module {
    private static final int[] SAMPLES = {8, 12, 20, 32};

    public final ModeProperty quality = new ModeProperty("quality", 2, new String[]{"LOW", "MEDIUM", "HIGH", "ULTRA"});
    /** Shutter angle relative to a 60 fps frame: 180° is the film look, 360° a full-frame smear. */
    public final IntProperty shutter = new IntProperty("shutter", 180, 30, 360);
    /** Longest blur streak, in percent of the screen height. */
    public final IntProperty maxLength = new IntProperty("max-length", 4, 1, 10);
    /** Blur from walking and flying too, not only from turning. */
    public final BooleanProperty translation = new BooleanProperty("translation", true);
    /** Keep players, mobs and other entities sharp; only terrain and sky are blurred. */
    public final BooleanProperty excludeEntities = new BooleanProperty("exclude-entities", true);

    public MotionBlur() {
        super("MotionBlur", false);
    }

    /** Called once per frame after the world is drawn and before the hand. */
    public void render() {
        if (!this.isEnabled() || mc.theWorld == null) return;
        int q = Math.min(Math.max(this.quality.getValue(), 0), SAMPLES.length - 1);
        MotionBlurRenderer.apply(SAMPLES[q], this.shutter.getValue(), this.maxLength.getValue(), this.translation.getValue(),
                this.excludeEntities.getValue());
    }

    @Override
    public void onEnabled() {
        MotionBlurRenderer.reset();
    }

    @Override
    public void onDisabled() {
        MotionBlurRenderer.release();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.quality.getModeString())};
    }
}
