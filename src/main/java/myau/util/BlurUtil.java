package myau.util;

import myau.ui.glass.GlassBlur;

/**
 * Legacy entry points kept for the HUD and world modules. The blur itself now lives in
 * {@link GlassBlur}: a multi-pass blur computed once per frame stage and shared by all callers.
 */
public class BlurUtil {
    public static void init() {
        GlassBlur.available();
    }

    public static boolean isBlurAvailable() {
        return GlassBlur.available();
    }

    /** No longer needed: every caller already shares one blurred copy of the screen. */
    public static void beginBlurBatch() {
    }

    public static void endBlurBatch() {
    }

    /**
     * Draws the blurred screen over the whole viewport; the caller restricts the area with
     * scissor or stencil. The rectangle and radius are ignored (one strength for all glass).
     */
    public static void blur(float x, float y, float width, float height, float radius) {
        GlassBlur.drawFullscreen();
    }
}
