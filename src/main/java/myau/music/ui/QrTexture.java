package myau.music.ui;

import io.nayuki.qrcodegen.QrCode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * A QR code as a GL texture, generated locally (the sign-in key never goes to a third-party QR
 * service). One texel per module plus a quiet zone; drawn with nearest filtering it stays sharp.
 * Main thread only.
 */
public final class QrTexture {
    private static final int QUIET = 2;

    private DynamicTexture texture;
    private String content = "";

    /** The texture for {@code text}, (re)built when it changes. Returns the GL id. */
    public int texture(String text) {
        if (text == null || text.isEmpty()) return 0;
        if (this.texture == null || !text.equals(this.content)) {
            this.release();
            QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
            int size = qr.size + QUIET * 2;
            DynamicTexture tex = new DynamicTexture(size, size);
            int[] data = tex.getTextureData();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int mx = x - QUIET, my = y - QUIET;
                    boolean dark = mx >= 0 && my >= 0 && mx < qr.size && my < qr.size && qr.getModule(mx, my);
                    data[y * size + x] = dark ? 0xff101114 : 0xffffffff;
                }
            }
            tex.updateDynamicTexture();
            this.texture = tex;
            this.content = text;
        }
        return this.texture.getGlTextureId();
    }

    public void release() {
        if (this.texture != null) this.texture.deleteGlTexture();
        this.texture = null;
        this.content = "";
    }
}
