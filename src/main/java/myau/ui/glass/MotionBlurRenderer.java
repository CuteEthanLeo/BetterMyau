package myau.ui.glass;

import myau.Myau;
import myau.mixin.IAccessorRenderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.vector.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Camera motion blur from a per-pixel velocity buffer.
 * <p>
 * Every frame, after the world and before the hand, the depth buffer is reprojected with the
 * previous frame's view-projection to get each pixel's screen-space motion. The velocities are
 * reduced to tiles (TileMax) and spread to their neighbours (NeighborMax), then the scene is
 * gathered along them with McGuire et al.'s reconstruction filter ("A Reconstruction Filter for
 * Plausible Motion Blur", 2012), sampling half along the neighbourhood's dominant motion and half
 * along the pixel's own (Jimenez, "Next Generation Post Processing in Call of Duty: AW", 2014),
 * with soft depth comparisons so foreground and background do not bleed into each other.
 * <p>
 * The exposure is defined against a 60 fps shutter, so the blur looks the same at any frame rate.
 */
public final class MotionBlurRenderer {
    private MotionBlurRenderer() { }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int MAX_SAMPLES = 32;
    private static final int MAX_TILE = 128;
    /** A camera jump longer than this (blocks) in one frame is a teleport, not motion. */
    private static final double TELEPORT_DISTANCE = 8.0;

    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);

    // ── Shaders ──────────────────────────────────────────────────────────────────────────

    private static final String VERTEX =
            "#version 120\n" +
            "void main() {\n" +
            "  gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
            "  gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n" +
            "}";

    /** Depth → world → previous clip space; outputs the half-motion ("blur radius") in pixels. */
    private static final String VELOCITY_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D depthTex;\n" +
            "uniform mat4 invViewProj;\n" +
            "uniform mat4 prevViewProj;\n" +
            "uniform vec3 camDelta;\n" +
            "uniform vec2 screenSize;\n" +
            "uniform float scale;\n" +
            "uniform float maxRadius;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  float d = texture2D(depthTex, uv).r;\n" +
            "  vec4 p = invViewProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);\n" +
            "  p.xyz /= p.w;\n" +
            // The sky is at infinity: only rotation moves it.
            "  vec3 delta = d >= 0.99999 ? vec3(0.0) : camDelta;\n" +
            "  vec4 prev = prevViewProj * vec4(p.xyz + delta, 1.0);\n" +
            "  vec2 v = vec2(0.0);\n" +
            "  if (prev.w > 0.0001) {\n" +
            "    vec2 prevUv = prev.xy / prev.w * 0.5 + 0.5;\n" +
            "    v = (uv - prevUv) * screenSize * (0.5 * scale);\n" +
            "    float l = length(v);\n" +
            "    if (l > maxRadius) v *= maxRadius / l;\n" +
            "  }\n" +
            "  gl_FragColor = vec4(v, 0.0, 1.0);\n" +
            "}";

    /** Longest velocity in each K-wide horizontal strip. */
    private static final String TILE_X_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 srcSize;\n" +
            "uniform float tile;\n" +
            "void main() {\n" +
            "  vec2 frag = floor(gl_FragCoord.xy);\n" +
            "  float y = (frag.y + 0.5) / srcSize.y;\n" +
            "  float start = frag.x * tile;\n" +
            "  vec2 best = vec2(0.0); float bestLen = -1.0;\n" +
            "  for (int i = 0; i < " + MAX_TILE + "; i++) {\n" +
            "    float x = start + float(i);\n" +
            "    if (float(i) >= tile || x >= srcSize.x) break;\n" +
            "    vec2 v = texture2D(tex, vec2((x + 0.5) / srcSize.x, y)).xy;\n" +
            "    float l = dot(v, v);\n" +
            "    if (l > bestLen) { bestLen = l; best = v; }\n" +
            "  }\n" +
            "  gl_FragColor = vec4(best, 0.0, 1.0);\n" +
            "}";

    /** Longest velocity in each K-tall column of the strips: one value per K×K tile. */
    private static final String TILE_Y_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 srcSize;\n" +
            "uniform float tile;\n" +
            "void main() {\n" +
            "  vec2 frag = floor(gl_FragCoord.xy);\n" +
            "  float x = (frag.x + 0.5) / srcSize.x;\n" +
            "  float start = frag.y * tile;\n" +
            "  vec2 best = vec2(0.0); float bestLen = -1.0;\n" +
            "  for (int i = 0; i < " + MAX_TILE + "; i++) {\n" +
            "    float y = start + float(i);\n" +
            "    if (float(i) >= tile || y >= srcSize.y) break;\n" +
            "    vec2 v = texture2D(tex, vec2(x, (y + 0.5) / srcSize.y)).xy;\n" +
            "    float l = dot(v, v);\n" +
            "    if (l > bestLen) { bestLen = l; best = v; }\n" +
            "  }\n" +
            "  gl_FragColor = vec4(best, 0.0, 1.0);\n" +
            "}";

    /**
     * Dominant velocity of each tile's 3×3 neighbourhood. A diagonal neighbour only counts when
     * its motion actually points into this tile (McGuire 2013), which keeps fast corners from
     * smearing tiles they never reach.
     */
    private static final String NEIGHBOR_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 srcSize;\n" +
            "void main() {\n" +
            "  vec2 frag = floor(gl_FragCoord.xy);\n" +
            "  vec2 best = vec2(0.0); float bestLen = -1.0;\n" +
            "  for (int y = -1; y <= 1; y++) {\n" +
            "    for (int x = -1; x <= 1; x++) {\n" +
            "      vec2 o = vec2(float(x), float(y));\n" +
            "      vec2 c = clamp(frag + o, vec2(0.0), srcSize - 1.0);\n" +
            "      vec2 v = texture2D(tex, (c + 0.5) / srcSize).xy;\n" +
            "      float l = dot(v, v);\n" +
            "      if (x != 0 && y != 0 && l > 0.0) {\n" +
            "        if (dot(normalize(v), normalize(-o)) < 0.7) continue;\n" +
            "      }\n" +
            "      if (l > bestLen) { bestLen = l; best = v; }\n" +
            "    }\n" +
            "  }\n" +
            "  gl_FragColor = vec4(best, 0.0, 1.0);\n" +
            "}";

    private static final String RECONSTRUCT_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D colorTex;\n" +
            "uniform sampler2D velocityTex;\n" +
            "uniform sampler2D neighborTex;\n" +
            "uniform sampler2D depthTex;\n" +
            "uniform vec2 screenSize;\n" +
            "uniform vec2 tileCount;\n" +
            "uniform float tile;\n" +
            "uniform vec2 nearFar;\n" +
            "uniform int samples;\n" +
            // Depth just before / after the entities were drawn: where it got nearer, an entity is.
            "uniform sampler2D preEntityTex;\n" +
            "uniform sampler2D postEntityTex;\n" +
            "uniform int useMask;\n" +
            "bool isEntity(vec2 uv) {\n" +
            "  return useMask != 0 && texture2D(postEntityTex, uv).r < texture2D(preEntityTex, uv).r - 0.000001;\n" +
            "}\n" +
            "float linearDepth(vec2 uv) {\n" +
            "  float z = texture2D(depthTex, uv).r * 2.0 - 1.0;\n" +
            "  float n = nearFar.x, f = nearFar.y;\n" +
            "  return 2.0 * n * f / (f + n - z * (f - n));\n" +
            "}\n" +
            // 1 when a is in front of (or level with) b, fading to 0 over a depth-relative extent.
            "float inFront(float za, float zb, float soft) { return clamp(1.0 - (za - zb) / soft, 0.0, 1.0); }\n" +
            "float cone(float dist, float len) { return clamp(1.0 - dist / len, 0.0, 1.0); }\n" +
            "float cylinder(float dist, float len) { return 1.0 - smoothstep(0.95 * len, 1.05 * len, dist); }\n" +
            "float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec2 px = gl_FragCoord.xy;\n" +
            "  vec3 center = texture2D(colorTex, uv).rgb;\n" +
            "  vec2 tileUv = (floor(px / tile) + 0.5) / tileCount;\n" +
            "  vec2 vN = texture2D(neighborTex, tileUv).xy;\n" +
            "  float lenN = length(vN);\n" +
            "  if (lenN < 0.5 || isEntity(uv)) { gl_FragColor = vec4(center, 1.0); return; }\n" +
            "  vec2 vC = texture2D(velocityTex, uv).xy;\n" +
            "  float lenC = max(length(vC), 0.5);\n" +
            "  bool ownDir = length(vC) > 1.5;\n" +
            "  float zC = linearDepth(uv);\n" +
            "  float jitter = ign(px) - 0.5;\n" +
            "  float total = 1.0 / lenC;\n" +
            "  vec3 sum = center * total;\n" +
            "  float n = float(samples);\n" +
            "  for (int i = 0; i < " + MAX_SAMPLES + "; i++) {\n" +
            "    if (i >= samples) break;\n" +
            "    float t = mix(-1.0, 1.0, (float(i) + 0.5 + jitter) / n);\n" +
            // Odd samples follow the pixel's own motion where it differs from the tile's.
            "    vec2 dir = (ownDir && mod(float(i), 2.0) > 0.5) ? vC : vN;\n" +
            "    vec2 offset = dir * t;\n" +
            "    float dist = length(offset);\n" +
            "    vec2 suv = (px + offset) / screenSize;\n" +
            // Entities stay sharp, so the background must not pick their colour up either.
            "    if (isEntity(suv)) continue;\n" +
            "    float zS = linearDepth(suv);\n" +
            "    float lenS = max(length(texture2D(velocityTex, suv).xy), 0.5);\n" +
            "    float soft = 0.05 + 0.02 * min(zC, zS);\n" +
            "    float fg = inFront(zS, zC, soft);\n" +
            "    float bg = inFront(zC, zS, soft);\n" +
            "    float w = fg * cone(dist, lenS)\n" +
            "            + bg * cone(dist, lenC)\n" +
            "            + cylinder(dist, lenS) * cylinder(dist, lenC) * 2.0;\n" +
            "    sum += texture2D(colorTex, suv).rgb * w;\n" +
            "    total += w;\n" +
            "  }\n" +
            "  gl_FragColor = vec4(sum / total, 1.0);\n" +
            "}";

    private static GlassShaders.Program velocity, tileX, tileY, neighbor, reconstruct;
    private static boolean programsLoaded;

    private static void loadPrograms() {
        if (programsLoaded) return;
        programsLoaded = true;
        velocity = new GlassShaders.Program("mblur-velocity", VERTEX, VELOCITY_FRAGMENT);
        tileX = new GlassShaders.Program("mblur-tile-x", VERTEX, TILE_X_FRAGMENT);
        tileY = new GlassShaders.Program("mblur-tile-y", VERTEX, TILE_Y_FRAGMENT);
        neighbor = new GlassShaders.Program("mblur-neighbor", VERTEX, NEIGHBOR_FRAGMENT);
        reconstruct = new GlassShaders.Program("mblur-reconstruct", VERTEX, RECONSTRUCT_FRAGMENT);
    }

    private static boolean programsOk() {
        loadPrograms();
        return velocity.ok && tileX.ok && tileY.ok && neighbor.ok && reconstruct.ok;
    }

    // ── GPU resources ────────────────────────────────────────────────────────────────────

    /** A texture with a framebuffer around it (fbo is 0 for the copy-only textures). */
    private static final class Target {
        int texture, fbo, width, height;

        void delete() {
            if (fbo != 0) OpenGlHelper.glDeleteFramebuffers(fbo);
            if (texture != 0) GL11.glDeleteTextures(texture);
            fbo = texture = 0;
        }
    }

    private static final Target color = new Target(), depth = new Target(), velocityRt = new Target(),
            tileXRt = new Target(), tileRt = new Target(), neighborRt = new Target(),
            preEntityDepth = new Target(), postEntityDepth = new Target();
    private static int builtWidth, builtHeight, builtTile;
    /** Both entity depth snapshots were taken during the current frame. */
    private static boolean preCaptured, postCaptured;
    private static boolean built, depthStencil, depthFormatChecked, failed, warnedUnsupported;

    // ── Frame history ────────────────────────────────────────────────────────────────────

    private static final Matrix4f prevViewProj = new Matrix4f();
    private static double prevCamX, prevCamY, prevCamZ;
    private static boolean hasPrev;
    private static int prevWidth, prevHeight;
    private static WorldClient prevWorld;
    private static long lastFrameNanos;
    private static double smoothDt = 1.0 / 60.0;

    /** Forget the previous frame: the next frame only records, it does not blur. */
    public static void reset() {
        hasPrev = false;
        lastFrameNanos = 0;
    }

    /** Frees every GL object; safe to call at any time on the render thread. */
    public static void release() {
        for (Target t : new Target[]{color, depth, velocityRt, tileXRt, tileRt, neighborRt, preEntityDepth, postEntityDepth}) t.delete();
        preCaptured = postCaptured = false;
        built = false;
        reset();
    }

    private static boolean supported() {
        if (failed) return false;
        if (!OpenGlHelper.shadersSupported || !OpenGlHelper.isFramebufferEnabled() || mc.getFramebuffer() == null) return false;
        ContextCapabilities caps = GLContext.getCapabilities();
        boolean floatRg = caps.OpenGL30 || (caps.GL_ARB_texture_rg && caps.GL_ARB_texture_float);
        if (!floatRg || !programsOk()) {
            if (!warnedUnsupported) {
                warnedUnsupported = true;
                Myau.LOGGER.warn("[MotionBlur] unsupported: floatRg={}, programs={}", floatRg, programsOk());
            }
            return false;
        }
        return true;
    }

    /**
     * Snapshots the main depth buffer right before ({@code after == false}) or right after the
     * entities are drawn. Where the second snapshot is nearer than the first, an entity was drawn,
     * and the reconstruction leaves those pixels sharp. Called from the render thread while the
     * main framebuffer is bound; does nothing until the blur targets exist at the current size.
     */
    public static void captureEntityDepth(boolean after) {
        if (!after) preCaptured = postCaptured = false;
        if (failed || !built || !depthFormatChecked || mc.gameSettings.anaglyph) return;
        Framebuffer main = mc.getFramebuffer();
        if (main == null || main.framebufferTextureWidth != builtWidth || main.framebufferTextureHeight != builtHeight) return;
        if (after && !preCaptured) return;
        Target target = after ? postEntityDepth : preEntityDepth;
        if (target.texture == 0) return;

        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousFramebuffer = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
        try {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, main.framebufferObject);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.texture);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, builtWidth, builtHeight);
            if (after) postCaptured = true; else preCaptured = true;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFramebuffer);
            GL13.glActiveTexture(previousUnit);
        }
    }

    /**
     * Blurs what is in the main framebuffer now (the world, before the hand) along the camera's
     * motion since the previous frame.
     *
     * @param samples         gather samples per pixel (1..32)
     * @param shutter         shutter angle in degrees, relative to a 60 fps frame
     * @param maxPercent      longest blur, in percent of the screen height
     * @param translation     whether camera movement blurs as well as rotation
     * @param excludeEntities keep entities sharp (needs {@link #captureEntityDepth} this frame)
     */
    public static void apply(int samples, float shutter, float maxPercent, boolean translation, boolean excludeEntities) {
        boolean mask = excludeEntities && preCaptured && postCaptured;
        preCaptured = postCaptured = false;
        if (mc.gameSettings.anaglyph || !supported()) return;
        Framebuffer main = mc.getFramebuffer();
        int width = main.framebufferTextureWidth, height = main.framebufferTextureHeight;
        if (width <= 0 || height <= 0) return;

        // ── This frame's camera ──
        Matrix4f projection = readMatrix(GL11.GL_PROJECTION_MATRIX);
        Matrix4f modelView = readMatrix(GL11.GL_MODELVIEW_MATRIX);
        Matrix4f viewProj = Matrix4f.mul(projection, modelView, null);
        Matrix4f invViewProj = Matrix4f.invert(viewProj, null);
        IAccessorRenderManager rm = (IAccessorRenderManager) mc.getRenderManager();
        double camX = rm.getRenderPosX(), camY = rm.getRenderPosY(), camZ = rm.getRenderPosZ();

        long now = System.nanoTime();
        if (lastFrameNanos != 0) {
            double dt = Math.min(Math.max((now - lastFrameNanos) / 1.0e9, 1.0 / 1000.0), 0.1);
            smoothDt += (dt - smoothDt) * 0.2;
        }
        lastFrameNanos = now;

        double dx = camX - prevCamX, dy = camY - prevCamY, dz = camZ - prevCamZ;
        boolean usable = hasPrev && invViewProj != null && prevWorld == mc.theWorld
                && width == prevWidth && height == prevHeight
                && dx * dx + dy * dy + dz * dz < TELEPORT_DISTANCE * TELEPORT_DISTANCE;

        try {
            if (usable) {
                // Blur radius in pixels; tiles are as wide as the longest blur.
                int maxRadius = Math.max(4, Math.min(MAX_TILE, Math.round(height * maxPercent / 100f)));
                float scale = (float) ((shutter / 360.0) * (1.0 / 60.0) / smoothDt);
                float[] nearFar = nearFar(projection);
                if (!translation) dx = dy = dz = 0;
                render(main, width, height, maxRadius, Math.max(1, Math.min(MAX_SAMPLES, samples)), scale,
                        invViewProj, (float) dx, (float) dy, (float) dz, nearFar, mask);
            }
        } catch (RuntimeException e) {
            failed = true;
            Myau.LOGGER.error("[MotionBlur] disabled after an error", e);
            release();
            return;
        }

        prevViewProj.load(viewProj);
        prevCamX = camX;
        prevCamY = camY;
        prevCamZ = camZ;
        prevWorld = mc.theWorld;
        prevWidth = width;
        prevHeight = height;
        hasPrev = true;
    }

    private static Matrix4f readMatrix(int which) {
        MATRIX.clear();
        GL11.glGetFloat(which, MATRIX);
        MATRIX.rewind();
        Matrix4f m = new Matrix4f();
        m.load(MATRIX);
        return m;
    }

    /** Near and far planes of a perspective projection. */
    private static float[] nearFar(Matrix4f p) {
        // Column-major: m22 = -(f+n)/(f-n), m32 = -2fn/(f-n).
        float a = p.m22, b = p.m32;
        float near = b / (a - 1f), far = b / (a + 1f);
        if (!(near > 0f) || !(far > near) || Float.isInfinite(far)) {
            near = 0.05f;
            far = mc.gameSettings.renderDistanceChunks * 16f * 2f;
        }
        return new float[]{near, far};
    }

    private static void render(Framebuffer main, int width, int height, int tile, int samples, float scale,
                               Matrix4f invViewProj, float dx, float dy, float dz, float[] nearFar, boolean mask) {
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousFramebuffer = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        boolean rebuilt = false;
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            rebuilt = ensureBuilt(main, width, height, tile);

            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            // 1. Copy the scene's colour and depth out of the main framebuffer.
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, main.framebufferObject);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color.texture);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            if (!copyDepth(width, height)) return;

            // 2. Velocity.
            GL20.glUseProgram(velocity.id);
            GL20.glUniform1i(velocity.loc("depthTex"), 0);
            uniformMatrix(velocity.loc("invViewProj"), invViewProj);
            uniformMatrix(velocity.loc("prevViewProj"), prevViewProj);
            GL20.glUniform3f(velocity.loc("camDelta"), dx, dy, dz);
            GL20.glUniform2f(velocity.loc("screenSize"), width, height);
            GL20.glUniform1f(velocity.loc("scale"), scale);
            GL20.glUniform1f(velocity.loc("maxRadius"), tile);
            pass(depth.texture, velocityRt);

            // 3. TileMax, separably.
            GL20.glUseProgram(tileX.id);
            GL20.glUniform1i(tileX.loc("tex"), 0);
            GL20.glUniform2f(tileX.loc("srcSize"), velocityRt.width, velocityRt.height);
            GL20.glUniform1f(tileX.loc("tile"), tile);
            pass(velocityRt.texture, tileXRt);

            GL20.glUseProgram(tileY.id);
            GL20.glUniform1i(tileY.loc("tex"), 0);
            GL20.glUniform2f(tileY.loc("srcSize"), tileXRt.width, tileXRt.height);
            GL20.glUniform1f(tileY.loc("tile"), tile);
            pass(tileXRt.texture, tileRt);

            // 4. NeighborMax.
            GL20.glUseProgram(neighbor.id);
            GL20.glUniform1i(neighbor.loc("tex"), 0);
            GL20.glUniform2f(neighbor.loc("srcSize"), tileRt.width, tileRt.height);
            pass(tileRt.texture, neighborRt);

            // 5. Reconstruction, straight back into the main framebuffer.
            GL20.glUseProgram(reconstruct.id);
            GL20.glUniform1i(reconstruct.loc("colorTex"), 0);
            GL20.glUniform1i(reconstruct.loc("velocityTex"), 1);
            GL20.glUniform1i(reconstruct.loc("neighborTex"), 2);
            GL20.glUniform1i(reconstruct.loc("depthTex"), 3);
            GL20.glUniform2f(reconstruct.loc("screenSize"), width, height);
            GL20.glUniform2f(reconstruct.loc("tileCount"), neighborRt.width, neighborRt.height);
            GL20.glUniform1f(reconstruct.loc("tile"), tile);
            GL20.glUniform2f(reconstruct.loc("nearFar"), nearFar[0], nearFar[1]);
            GL20.glUniform1i(reconstruct.loc("samples"), samples);
            GL20.glUniform1i(reconstruct.loc("preEntityTex"), 4);
            GL20.glUniform1i(reconstruct.loc("postEntityTex"), 5);
            GL20.glUniform1i(reconstruct.loc("useMask"), mask ? 1 : 0);
            bindUnit(5, postEntityDepth.texture);
            bindUnit(4, preEntityDepth.texture);
            bindUnit(3, depth.texture);
            bindUnit(2, neighborRt.texture);
            bindUnit(1, velocityRt.texture);
            bindUnit(0, color.texture);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, main.framebufferObject);
            GL11.glViewport(0, 0, width, height);
            quad();
        } finally {
            GL20.glUseProgram(previousProgram);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glPopAttrib();
            GL11.glViewport(VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3));
            GL13.glActiveTexture(previousUnit);
            // Framebuffer creation goes through GlStateManager and leaves its cache at texture 0.
            if (rebuilt) GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
    }

    /**
     * Copies the main depth buffer into {@link #depth}. The copy needs the texture's format to
     * match the renderbuffer's (with or without stencil); if the first guess is refused the other
     * format is tried, and only when both fail is motion blur turned off.
     */
    private static boolean copyDepth(int width, int height) {
        if (!depthFormatChecked) drainErrors();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth.texture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        if (depthFormatChecked) return true;
        int error = GL11.glGetError();
        if (error == GL11.GL_NO_ERROR) {
            depthFormatChecked = true;
            return true;
        }
        if (depthStencil == initialDepthStencil) {
            Myau.LOGGER.warn("[MotionBlur] depth copy refused (GL error {}), trying the other depth format", error);
            depthStencil = !depthStencil;
            allocateDepth(width, height);
            return false;
        }
        throw new IllegalStateException("cannot copy the depth buffer (GL error " + error + ")");
    }

    private static boolean initialDepthStencil;

    private static void drainErrors() {
        for (int i = 0; i < 32 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) { }
    }

    private static boolean ensureBuilt(Framebuffer main, int width, int height, int tile) {
        int tilesX = (width + tile - 1) / tile, tilesY = (height + tile - 1) / tile;
        boolean sizeChanged = !built || width != builtWidth || height != builtHeight;
        boolean tileChanged = !built || tile != builtTile;
        if (!sizeChanged && !tileChanged) return false;

        int previousFramebuffer = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
        if (sizeChanged) {
            color.delete();
            velocityRt.delete();
            allocate(color, width, height, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, GL11.GL_LINEAR, false);
            if (!depthFormatChecked) {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, main.framebufferObject);
                depthStencil = GL11.glGetInteger(GL11.GL_STENCIL_BITS) > 0;
                initialDepthStencil = depthStencil;
            }
            allocateDepth(width, height);
            allocate(velocityRt, width, height, GL30.GL_RG16F, GL30.GL_RG, GL11.GL_FLOAT, GL11.GL_NEAREST, true);
        }
        tileXRt.delete();
        tileRt.delete();
        neighborRt.delete();
        allocate(tileXRt, tilesX, height, GL30.GL_RG16F, GL30.GL_RG, GL11.GL_FLOAT, GL11.GL_NEAREST, true);
        allocate(tileRt, tilesX, tilesY, GL30.GL_RG16F, GL30.GL_RG, GL11.GL_FLOAT, GL11.GL_NEAREST, true);
        allocate(neighborRt, tilesX, tilesY, GL30.GL_RG16F, GL30.GL_RG, GL11.GL_FLOAT, GL11.GL_NEAREST, true);
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFramebuffer);

        builtWidth = width;
        builtHeight = height;
        builtTile = tile;
        built = true;
        return true;
    }

    /** (Re)allocates every depth copy: the scene's and the two entity-mask snapshots. */
    private static void allocateDepth(int width, int height) {
        allocateDepth(depth, width, height);
        allocateDepth(preEntityDepth, width, height);
        allocateDepth(postEntityDepth, width, height);
    }

    private static void allocateDepth(Target target, int width, int height) {
        target.delete();
        if (depthStencil) {
            allocate(target, width, height, GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, GL11.GL_NEAREST, false);
        } else {
            allocate(target, width, height, GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, GL11.GL_NEAREST, false);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_DEPTH_TEXTURE_MODE, GL11.GL_LUMINANCE);
    }

    private static void allocate(Target target, int width, int height, int internal, int format, int type,
                                 int filter, boolean withFbo) {
        target.width = Math.max(1, width);
        target.height = Math.max(1, height);
        target.texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, target.width, target.height, 0, format, type, (ByteBuffer) null);
        if (withFbo) {
            target.fbo = OpenGlHelper.glGenFramebuffers();
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, target.fbo);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, target.texture, 0);
            int status = OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER);
            if (status != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("motion blur framebuffer incomplete: " + status);
            }
        }
    }

    private static void bindUnit(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private static void uniformMatrix(int location, Matrix4f matrix) {
        MATRIX.clear();
        matrix.store(MATRIX);
        MATRIX.flip();
        GL20.glUniformMatrix4(location, false, MATRIX);
    }

    private static void pass(int source, Target target) {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, target.fbo);
        GL11.glViewport(0, 0, target.width, target.height);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
        quad();
    }

    private static void quad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
        GL11.glEnd();
    }
}
