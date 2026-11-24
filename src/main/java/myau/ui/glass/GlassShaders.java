package myau.ui.glass;

import myau.Myau;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

/** GLSL 120 programs shared by every glass surface. Compiled lazily on the render thread. */
public final class GlassShaders {
    private GlassShaders() { }

    /** A linked program, or an unusable one ({@link #ok} false) when the driver rejected it. */
    public static final class Program {
        public final int id;
        public final boolean ok;
        private final Map<String, Integer> uniforms = new HashMap<>();

        Program(String name, String vertex, String fragment) {
            int program = 0;
            boolean linked = false;
            try {
                int vs = compile(name, GL20.GL_VERTEX_SHADER, vertex);
                int fs = compile(name, GL20.GL_FRAGMENT_SHADER, fragment);
                if (vs != 0 && fs != 0) {
                    program = GL20.glCreateProgram();
                    GL20.glAttachShader(program, vs);
                    GL20.glAttachShader(program, fs);
                    GL20.glLinkProgram(program);
                    linked = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0;
                    if (!linked) Myau.LOGGER.error("[Glass] {} link failed: {}", name, GL20.glGetProgramInfoLog(program, 2048));
                }
                if (vs != 0) GL20.glDeleteShader(vs);
                if (fs != 0) GL20.glDeleteShader(fs);
            } catch (RuntimeException e) {
                Myau.LOGGER.error("[Glass] {} unavailable", name, e);
            }
            this.id = program;
            this.ok = linked;
        }

        private static int compile(String name, int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                Myau.LOGGER.error("[Glass] {} compile failed: {}", name, GL20.glGetShaderInfoLog(shader, 2048));
                GL20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        public int loc(String uniform) {
            Integer cached = uniforms.get(uniform);
            if (cached == null) {
                cached = GL20.glGetUniformLocation(id, uniform);
                uniforms.put(uniform, cached);
            }
            return cached;
        }
    }

    private static final String SHAPE_VERTEX =
            "#version 120\n" +
            "void main() {\n" +
            "  gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
            "  gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n" +
            "}";

    /**
     * Signed-distance rounded rectangle. gl_TexCoord[0].xy is the position relative to the
     * rectangle centre, in the same units as the vertices.
     */
    private static final String SHAPE_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 halfSize;\n" +
            "uniform vec4 radii;\n" +      // tl, tr, br, bl
            "uniform float aa;\n" +        // one pixel, in units
            "uniform float soft;\n" +      // > 0: soft shadow / glow falloff distance
            "uniform float stroke;\n" +    // > 0: outline thickness
            "uniform vec4 colA;\n" +
            "uniform vec4 colB;\n" +
            "uniform vec2 grad;\n" +       // gradient direction, (0,0) = solid colA
            "uniform vec3 spot;\n" +       // x, y, radius of a radial mask; radius <= 0 = off
            "uniform vec2 band;\n" +       // position, width of a diagonal sheen; width <= 0 = off
            "uniform float texMode;\n" +   // 1 = sample the blurred screen
            "uniform sampler2D tex;\n" +
            "uniform vec2 screen;\n" +
            "float sdf(vec2 p) {\n" +
            "  float r = p.x > 0.0 ? (p.y > 0.0 ? radii.z : radii.y) : (p.y > 0.0 ? radii.w : radii.x);\n" +
            "  vec2 q = abs(p) - halfSize + r;\n" +
            "  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 p = gl_TexCoord[0].xy;\n" +
            "  float d = sdf(p);\n" +
            "  float a;\n" +
            "  if (soft > 0.0) { a = 1.0 - smoothstep(-soft * 0.35, soft, d); a *= a; }\n" +
            "  else {\n" +
            "    if (stroke > 0.0) d = abs(d + stroke * 0.5) - stroke * 0.5;\n" +
            "    float px = length(vec2(dFdx(d), dFdy(d)));\n" +
            "    a = clamp(0.5 - d / max(px > 0.0 ? px : aa, 0.00001), 0.0, 1.0);\n" +
            "  }\n" +
            "  vec4 c = colA;\n" +
            "  if (grad.x != 0.0 || grad.y != 0.0) {\n" +
            "    float t = dot(p, grad) / max(dot(abs(grad), halfSize), 0.0001) * 0.5 + 0.5;\n" +
            "    c = mix(colA, colB, clamp(t, 0.0, 1.0));\n" +
            "  }\n" +
            "  if (texMode > 0.5) {\n" +
            "    vec3 s = texture2D(tex, gl_FragCoord.xy / screen).rgb;\n" +
            "    float l = dot(s, vec3(0.299, 0.587, 0.114));\n" +
            "    s = mix(vec3(l), s, 1.22);\n" +
            "    c = vec4(clamp(s, 0.0, 1.0), c.a);\n" +
            "  }\n" +
            "  if (spot.z > 0.0) { float f = clamp(1.0 - length(p - spot.xy) / spot.z, 0.0, 1.0); a *= f * f; }\n" +
            "  if (band.y > 0.0) { float f = (p.x + p.y * 0.6 - band.x) / band.y; a *= exp(-f * f); }\n" +
            "  gl_FragColor = vec4(c.rgb, c.a * a);\n" +
            "}";

    /**
     * Liquid glass. Normal mode samples the blurred screen through a lens that bends the
     * backdrop along the rounded edge, tints it and adds a directional rim light. Snapshot
     * mode (snap.z > 0) instead shows a cut-out of a full-screen texture taken earlier.
     */
    private static final String GLASS_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 halfSize;\n" +
            "uniform vec4 radii;\n" +      // tl, tr, br, bl
            "uniform float aa;\n" +        // one pixel, in units
            "uniform vec4 tintA;\n" +      // top tint rgb + coverage
            "uniform vec4 tintB;\n" +      // bottom tint
            "uniform float opacity;\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 screen;\n" +
            "uniform vec4 lens;\n" +       // x refraction (pixels), y bevel width (units), z dispersion, w saturation
            "uniform vec4 rim;\n" +        // x main light, y second light, z inner shadow, w grain (1/255)
            "uniform vec3 snap;\n" +       // z > 0: snapshot mode; xy = original centre (window pixels, y up), z = pixels per unit
            "float cornerRadius(vec2 p) {\n" +
            "  return p.x > 0.0 ? (p.y > 0.0 ? radii.z : radii.y) : (p.y > 0.0 ? radii.w : radii.x);\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 p = gl_TexCoord[0].xy;\n" +
            "  float r = cornerRadius(p);\n" +
            "  vec2 q = abs(p) - halfSize + r;\n" +
            "  float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "  float px = length(vec2(dFdx(d), dFdy(d)));\n" +
            "  float a = clamp(0.5 - d / max(px > 0.0 ? px : aa, 0.00001), 0.0, 1.0);\n" +
            "  vec3 s;\n" +
            "  if (snap.z > 0.0) {\n" +
            "    s = texture2D(tex, vec2(snap.x + p.x * snap.z, snap.y - p.y * snap.z) / screen).rgb;\n" +
            "  } else {\n" +
            "    vec2 uv = gl_FragCoord.xy / screen;\n" +
            "    float depth = -d;\n" +
            "    float bevel = max(lens.y, 0.0001);\n" +
            "    float e = clamp(1.0 - depth / bevel, 0.0, 1.0);\n" +
            "    float rr = max(r, bevel);\n" +
            "    vec2 q2 = abs(p) - halfSize + rr;\n" +
            "    vec2 n = sign(p) * normalize(max(q2, vec2(0.0)) + vec2(0.0001));\n" +
            "    vec2 off = n * (e * e) * lens.x / screen;\n" +
            "    off.y = -off.y;\n" +
            "    if (lens.z > 0.0) {\n" +
            "      s.r = texture2D(tex, uv - off * (1.0 + lens.z)).r;\n" +
            "      s.g = texture2D(tex, uv - off).g;\n" +
            "      s.b = texture2D(tex, uv - off * (1.0 - lens.z)).b;\n" +
            "    } else {\n" +
            "      s = texture2D(tex, uv - off).rgb;\n" +
            "    }\n" +
            "    float l = dot(s, vec3(0.299, 0.587, 0.114));\n" +
            "    s = mix(vec3(l), s, lens.w);\n" +
            "    float v = clamp(p.y / max(halfSize.y, 0.0001) * 0.5 + 0.5, 0.0, 1.0);\n" +
            "    vec4 t = mix(tintA, tintB, v);\n" +
            "    s = mix(s, t.rgb, t.a);\n" +
            "    s = mix(s, vec3(1.0), 0.094 * (1.0 - v));\n" +
            "    float ndl = dot(n, vec2(-0.5547, -0.8321));\n" +
            "    float band = e * e;\n" +
            "    float lineW = max(aa * 1.25, 0.6);\n" +
            "    float line = clamp(1.0 - depth / lineW, 0.0, 1.0);\n" +
            "    s *= 1.0 - rim.z * band * (0.35 + 0.65 * max(-ndl, 0.0)) * (1.0 - line);\n" +
            "    float hi = band * rim.x * pow(max(ndl, 0.0), 1.5)\n" +
            "             + line * (0.10 + 0.34 * max(ndl, 0.0) + rim.y * max(-ndl, 0.0));\n" +
            "    s = mix(s, vec3(1.0), clamp(hi, 0.0, 1.0));\n" +
            "    float nz = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);\n" +
            "    s += (nz - 0.5) * rim.w / 255.0;\n" +
            "  }\n" +
            "  gl_FragColor = vec4(clamp(s, 0.0, 1.0), a * opacity);\n" +
            "}";

    /**
     * Liquid glass NextGen (the "Better" material). The panel is one clear slab: every pixel of it is the same
     * material, a mix of the lightly filtered and the blurred backdrop, brought into the range the
     * ink stays readable on. The whole slab magnifies a little, like a lens, and its rounded bevel
     * bends the scene more strongly towards the edge, so the body and the edge differ only in
     * their optics and never in their material. Highlights come from a light that has a place on
     * screen, so they differ from panel to panel and travel when a panel or the light moves.
     * <p>
     * Paired with SHAPE_VERTEX.
     */
    private static final String BETTER_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 halfSize;\n" +
            "uniform vec4 radii;\n" +      // tl, tr, br, bl
            "uniform float aa;\n" +      // one pixel, in units; used only where the derivatives are zero
            "uniform vec4 tintA;\n" +      // top tint rgb + coverage
            "uniform vec4 tintB;\n" +      // bottom tint
            "uniform float opacity;\n" +
            "uniform sampler2D tex;\n" +      // unit 0: the blurred screen
            "uniform sampler2D sharpTex;\n" +      // unit 3: the screen, only lightly filtered
            "uniform vec2 screen;\n" +
            "uniform vec4 lens;\n" +      // x bevel width, y glass thickness, z gap to the backdrop (units), w refraction gain
            "uniform float zoom;\n" +      // how much the whole slab magnifies, as a share of the distance to its centre
            "uniform vec4 look;\n" +      // x dispersion, y saturation, z clarity, w grain (1/255)
            "uniform vec4 adapt;\n" +      // backdrop luminance: x lift floor, y lift knee, z compress knee, w compress cap
            "uniform vec4 shine;\n" +      // x key light, y fill light, z reflection of the surroundings, w inner shadow
            "uniform vec3 light;\n" +      // xy position in screen heights from the bottom left (y up), z height
            "const vec3 LUMA = vec3(0.299, 0.587, 0.114);\n" +
            "const float ETA = 0.6667;\n" +      // air into glass, 1 / 1.5
            "const float RIM_START = 0.3;\n" +      // bevel coordinate (0 body .. 1 edge) below which the bevel bends nothing yet
            "const float MAX_SLOPE = 4.0;\n" +
            "const float SHININESS = 96.0;\n" +
            "const float FILL_SHININESS = 24.0;\n" +
            "const float TOP_LIFT = 0.03;\n" +
            "const float SHEEN = 0.07;\n" +      // the broad glow across the slab on the side facing the light
            "const float LIGHT_MIN = 0.6;\n" +      // highlight gain in a black scene; 1.25 in a white one
            "float cornerRadius(vec2 p) {\n" +
            "  return p.x > 0.0 ? (p.y > 0.0 ? radii.z : radii.y) : (p.y > 0.0 ? radii.w : radii.x);\n" +
            "}\n" +
            "float hash(vec2 p) {\n" +
            "  vec3 q = fract(vec3(p.xyx) * 0.1031);\n" +
            "  q += dot(q, q.yzx + 33.33);\n" +
            "  return fract((q.x + q.y) * q.z);\n" +
            "}\n" +
            // Luminance the way text contrast sees it (gamma 2 stands in for sRGB): a saturated green counts as bright.
            "float lum(vec3 c) {\n" +
            "  return sqrt(dot(c * c, vec3(0.2126, 0.7152, 0.0722)));\n" +
            "}\n" +
            // A displacement in units (y down) as window pixels (y up), through the mapping that is really on screen.
            "vec2 toPixels(vec2 v, vec2 jx, vec2 jy) {\n" +
            "  float det = jx.x * jy.y - jy.x * jx.y;\n" +
            "  if (abs(det) < 0.00000001) return vec2(v.x, -v.y) / max(aa, 0.00001);\n" +
            "  return vec2(jy.y * v.x - jy.x * v.y, jx.x * v.y - jx.y * v.x) / det;\n" +
            "}\n" +
            // A surface normal in units as a direction in the window. Normals take the transpose, not the inverse.
            "vec2 normalToWindow(vec2 n, vec2 jx, vec2 jy) {\n" +
            "  vec2 w = vec2(dot(n, jx), dot(n, jy));\n" +
            "  float len = length(w);\n" +
            "  return len > 0.00001 ? w / len : vec2(n.x, -n.y);\n" +
            "}\n" +
            // Where a backdrop of luminance l should end up so the ink on the panel stays readable.
            "float aim(float l) {\n" +
            "  float target = l;\n" +
            "  float capKnee = adapt.z;\n" +
            "  float capSpan = max(adapt.w - capKnee, 0.001);\n" +
            "  if (l > capKnee) {\n" +
            "    float x = (l - capKnee) / capSpan;\n" +
            "    target = capKnee + capSpan * x / (1.0 + x);\n" +
            "  }\n" +
            "  float floorSpan = max(adapt.y - adapt.x, 0.001);\n" +
            "  if (l < adapt.y) {\n" +
            "    float x = (adapt.y - l) / floorSpan;\n" +
            "    target = adapt.y - floorSpan * x / (1.0 + x);\n" +
            "  }\n" +
            "  return target;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 p = gl_TexCoord[0].xy;\n" +
            // The quad's own mapping from pixels to units: covers scales Draw never hears of, and squash.
            "  vec2 jx = dFdx(p);\n" +
            "  vec2 jy = dFdy(p);\n" +
            "  float r = cornerRadius(p);\n" +
            "  vec2 q = abs(p) - halfSize + r;\n" +
            "  float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "  float px = length(vec2(dFdx(d), dFdy(d)));\n" +
            "  float unit = px > 0.0 ? px : aa;\n" +
            "  float a = clamp(0.5 - d / max(unit, 0.00001), 0.0, 1.0);\n" +
            "  if (a <= 0.0) {\n" +
            "    gl_FragColor = vec4(0.0);\n" +
            "    return;\n" +
            "  }\n" +
            "  vec2 scr = max(screen, vec2(1.0));\n" +
            "  vec2 uv = gl_FragCoord.xy / scr;\n" +
            "  float o = clamp(opacity, 0.0, 1.0);\n" +
            // A panel that fades in forms its lens and its highlights on the way, instead of only fading.
            "  float form = o * o * (3.0 - 2.0 * o);\n" +
            "  float depth = -d;\n" +
            "  float bevel = max(lens.x, 0.0001);\n" +
            "  float u = clamp(1.0 - depth / bevel, 0.0, 1.0);\n" +
            "  float v = clamp(p.y / max(halfSize.y, 0.0001) * 0.5 + 0.5, 0.0, 1.0);\n" +
            "  vec3 toLight = vec3(light.xy - gl_FragCoord.xy / scr.y, max(light.z, 0.05));\n" +
            // The whole slab is a weak lens: each pixel shows the backdrop a little closer to the centre.
            "  vec2 shift = -p * zoom * form;\n" +
            "  float rim = 0.0;\n" +
            "  float shade = 0.0;\n" +
            "  float glint = 0.0;\n" +
            "  float mirror = 0.0;\n" +
            "  vec3 env = vec3(0.0);\n" +
            "  if (u > RIM_START && lens.x > 0.0) {\n" +
            // The edge is a squircle bevel: flat towards the body, steep at the rim. Its bending adds
            // to the body's lens and starts from nothing, so there is no line where the two meet.
            "    float u2 = u * u;\n" +
            "    float rest = max(1.0 - u2 * u2, 0.0001);\n" +
            "    float root = sqrt(rest);\n" +
            "    float rise = sqrt(root);\n" +
            "    float slope = min(lens.y / bevel * u2 * u / (root * rise), MAX_SLOPE);\n" +
            "    float rr = max(r, bevel);\n" +
            "    vec2 q2 = abs(p) - halfSize + rr;\n" +
            "    vec2 n2 = sign(p) * normalize(max(q2, vec2(0.0)) + vec2(0.0001));\n" +
            "    vec3 n = normalize(vec3(n2 * slope, 1.0));\n" +
            // The ray that enters here meets the backdrop further inside: the bevel is convex.
            "    vec3 bent = refract(vec3(0.0, 0.0, -1.0), n, ETA);\n" +
            "    shift += bent.xy / max(-bent.z, 0.25) * (lens.z + lens.y * rise) * lens.w * form;\n" +
            "    rim = smoothstep(RIM_START, 0.9, u);\n" +
            // Light: Blinn-Phong on the bevel, less what a flat surface returns; the flat part has the sheen below.
            "    vec2 ns = normalToWindow(n2, jx, jy);\n" +
            "    vec3 nView = vec3(ns * sqrt(max(1.0 - n.z * n.z, 0.0)), n.z);\n" +
            "    vec3 h1 = normalize(normalize(toLight) + vec3(0.0, 0.0, 1.0));\n" +
            "    vec3 h2 = normalize(normalize(vec3(-toLight.xy, toLight.z * 1.6)) + vec3(0.0, 0.0, 1.0));\n" +
            "    float base1 = pow(max(h1.z, 0.0001), SHININESS);\n" +
            "    float base2 = pow(max(h2.z, 0.0001), FILL_SHININESS);\n" +
            "    float key = max(pow(max(dot(nView, h1), 0.0001), SHININESS) - base1, 0.0) / max(1.0 - base1, 0.05);\n" +
            "    float fill = max(pow(max(dot(nView, h2), 0.0001), FILL_SHININESS) - base2, 0.0) / max(1.0 - base2, 0.05);\n" +
            // The surroundings just outside the edge colour its steepest part and set how bright the light is.
            "    env = texture2D(tex, uv + toPixels(n2 * (max(depth, 0.0) + 3.0), jx, jy) / scr).rgb;\n" +
            "    float envLuma = clamp(dot(env, LUMA), 0.0, 1.0);\n" +
            "    float graze = 1.0 - n.z;\n" +
            "    mirror = graze * graze * graze * shine.z * form;\n" +
            "    float side = dot(ns, toLight.xy / max(length(toLight.xy), 0.0001));\n" +
            "    float lineWidth = max(unit * 1.25, 0.6);\n" +
            "    float line = clamp(1.0 - depth / lineWidth, 0.0, 1.0);\n" +
            "    float curve = smoothstep(RIM_START, RIM_START + 0.25, u);\n" +
            "    shade = shine.w * rim * rim * (0.35 + 0.65 * max(-side, 0.0)) * (1.0 - line);\n" +
            "    glint = (key * shine.x + fill * shine.y) * mix(LIGHT_MIN, 1.25, envLuma) * curve * form\n" +
            "          + line * (0.13 + 0.30 * max(side, 0.0) + 0.14 * max(-side, 0.0)) * mix(LIGHT_MIN, 1.15, envLuma) * form;\n" +
            "  }\n" +
            // One material everywhere: the same mix of the two backdrops, sampled where the optics point.
            // Neither texture has mipmaps, so sampling them after the branch above is well defined.
            "  vec2 off = toPixels(shift, jx, jy) / scr;\n" +
            "  vec3 blurred = texture2D(tex, uv + off).rgb;\n" +
            "  vec3 seen;\n" +
            // Off the bevel rim is 0 and the three samples would be one: the body pays for one.
            "  if (look.x > 0.0 && rim > 0.0) {\n" +
            "    seen.r = texture2D(sharpTex, uv + off * (1.0 + look.x * rim)).r;\n" +
            "    seen.g = texture2D(sharpTex, uv + off).g;\n" +
            "    seen.b = texture2D(sharpTex, uv + off * (1.0 - look.x * rim)).b;\n" +
            "  } else {\n" +
            "    seen = texture2D(sharpTex, uv + off).rgb;\n" +
            "  }\n" +
            "  vec3 s = mix(blurred, seen, clamp(look.z, 0.0, 1.0));\n" +
            "  float back = lum(blurred);\n" +
            "  s = clamp(mix(vec3(dot(s, LUMA)), s, look.y), 0.0, 1.0);\n" +
            // One gain for the whole pixel, from the blurred luminance: the mean lands in range, local contrast survives.
            "  float target = aim(back);\n" +
            "  if (target < back) s *= target / max(back, 0.001);\n" +
            "  else s = mix(s, vec3(1.0), (target - back) / max(1.0 - back, 0.001));\n" +
            "  vec4 t = mix(tintA, tintB, v);\n" +
            "  s = mix(s, t.rgb, clamp(t.a, 0.0, 1.0));\n" +
            // A broad glow on the side of the slab that faces the light, so the middle is lit too.
            "  vec2 lightDir = toLight.xy / max(length(toLight.xy), 0.0001);\n" +
            "  float along = dot(p / max(halfSize, vec2(0.0001)), vec2(lightDir.x, -lightDir.y));\n" +
            "  float sheen = SHEEN * shine.x * form * smoothstep(-0.3, 1.1, along);\n" +
            "  s = mix(s, vec3(1.0), clamp(sheen + TOP_LIFT * (1.0 - v), 0.0, 1.0));\n" +
            "  s *= 1.0 - clamp(shade, 0.0, 1.0);\n" +
            "  s = mix(s, env, clamp(mirror, 0.0, 1.0));\n" +
            "  s = mix(s, vec3(1.0), clamp(glint, 0.0, 1.0));\n" +
            "  s += (hash(gl_FragCoord.xy) - 0.5) * look.w / 255.0;\n" +
            "  gl_FragColor = vec4(clamp(s, 0.0, 1.0), a * o);\n" +
            "}";

    /**
     * A texture region (player head, server icon) inside a rounded rectangle. The region is
     * uv.xy (top-left) .. uv.zw (bottom-right) and is clamped, so texels outside it (the rest
     * of a skin) never bleed in at the antialiased edge.
     */
    private static final String IMAGE_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 halfSize;\n" +
            "uniform vec4 radii;\n" +      // tl, tr, br, bl
            "uniform float aa;\n" +
            "uniform vec4 uv;\n" +
            "uniform vec4 tint;\n" +       // multiplied in, alpha already includes the current fade
            "uniform sampler2D tex;\n" +
            "void main() {\n" +
            "  vec2 p = gl_TexCoord[0].xy;\n" +
            "  float r = p.x > 0.0 ? (p.y > 0.0 ? radii.z : radii.y) : (p.y > 0.0 ? radii.w : radii.x);\n" +
            "  vec2 q = abs(p) - halfSize + r;\n" +
            "  float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "  float px = length(vec2(dFdx(d), dFdy(d)));\n" +
            "  float a = clamp(0.5 - d / max(px > 0.0 ? px : aa, 0.00001), 0.0, 1.0);\n" +
            "  vec2 f = clamp(p / max(halfSize, vec2(0.0001)) * 0.5 + 0.5, 0.0, 1.0);\n" +
            "  vec2 lo = min(uv.xy, uv.zw), hi = max(uv.xy, uv.zw);\n" +
            "  vec2 st = clamp(mix(uv.xy, uv.zw, f), lo + 0.00001, hi - 0.00001);\n" +
            "  vec4 c = texture2D(tex, st) * tint;\n" +
            "  gl_FragColor = vec4(c.rgb, c.a * a);\n" +
            "}";

    private static final String PASS_VERTEX =
            "#version 120\n" +
            "void main() {\n" +
            "  gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
            "  gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n" +
            "}";

    private static final String DOWN_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 halfpixel;\n" +
            "uniform float offset;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec2 o = halfpixel * offset;\n" +
            "  vec4 sum = texture2D(tex, uv) * 4.0;\n" +
            "  sum += texture2D(tex, uv - o);\n" +
            "  sum += texture2D(tex, uv + o);\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, -o.y));\n" +
            "  sum += texture2D(tex, uv - vec2(o.x, -o.y));\n" +
            "  gl_FragColor = vec4(sum.rgb / 8.0, 1.0);\n" +
            "}";

    private static final String UP_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 halfpixel;\n" +
            "uniform float offset;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec2 o = halfpixel * offset;\n" +
            "  vec4 sum = texture2D(tex, uv + vec2(-o.x * 2.0, 0.0));\n" +
            "  sum += texture2D(tex, uv + vec2(-o.x, o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(0.0, o.y * 2.0));\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(o.x * 2.0, 0.0));\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, -o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(0.0, -o.y * 2.0));\n" +
            "  sum += texture2D(tex, uv + vec2(-o.x, -o.y)) * 2.0;\n" +
            "  gl_FragColor = vec4(sum.rgb / 12.0, 1.0);\n" +
            "}";

    private static final String COPY_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "void main() { gl_FragColor = vec4(texture2D(tex, gl_TexCoord[0].st).rgb, 1.0); }";

    // The same two kernels for a texture whose alpha matters (WorldGlow's silhouette mask).
    private static final String DOWN_ALPHA_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 halfpixel;\n" +
            "uniform float offset;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec2 o = halfpixel * offset;\n" +
            "  vec4 sum = texture2D(tex, uv) * 4.0;\n" +
            "  sum += texture2D(tex, uv - o);\n" +
            "  sum += texture2D(tex, uv + o);\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, -o.y));\n" +
            "  sum += texture2D(tex, uv - vec2(o.x, -o.y));\n" +
            "  gl_FragColor = sum / 8.0;\n" +
            "}";

    private static final String UP_ALPHA_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 halfpixel;\n" +
            "uniform float offset;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec2 o = halfpixel * offset;\n" +
            "  vec4 sum = texture2D(tex, uv + vec2(-o.x * 2.0, 0.0));\n" +
            "  sum += texture2D(tex, uv + vec2(-o.x, o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(0.0, o.y * 2.0));\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(o.x * 2.0, 0.0));\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, -o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(0.0, -o.y * 2.0));\n" +
            "  sum += texture2D(tex, uv + vec2(-o.x, -o.y)) * 2.0;\n" +
            "  gl_FragColor = sum / 12.0;\n" +
            "}";

    /**
     * Puts WorldGlow's result on screen: the blurred mask as a halo outside the silhouettes,
     * plus a faint tint inside them. Both textures hold premultiplied colour; the output is
     * premultiplied too, with its alpha cut so the halo adds light more than it covers.
     */
    private static final String GLOW_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D blurTex;\n" +
            "uniform sampler2D maskTex;\n" +
            "uniform float strength;\n" +
            "uniform float fill;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec4 g = texture2D(blurTex, uv);\n" +
            "  vec4 m = texture2D(maskTex, uv);\n" +
            "  vec3 haloColor = g.a > 0.001 ? g.rgb / g.a : vec3(0.0);\n" +
            "  vec3 innerColor = m.a > 0.001 ? m.rgb / m.a : vec3(0.0);\n" +
            "  float halo = clamp(g.a * strength, 0.0, 1.0);\n" +
            "  halo = halo * halo * (3.0 - 2.0 * halo) * (1.0 - m.a);\n" +
            "  float inner = m.a * fill;\n" +
            "  vec3 c = haloColor * halo + innerColor * inner;\n" +
            "  gl_FragColor = vec4(c, (halo + inner) * 0.55);\n" +
            "}";


    /** Hash and value noise shared by the backdrop scenes (no sin(): steady on every driver). */
    private static final String NOISE =
            "float hash(vec2 p) {\n" +
            "  vec3 q = fract(vec3(p.xyx) * 0.1031);\n" +
            "  q += dot(q, q.yzx + 33.33);\n" +
            "  return fract((q.x + q.y) * q.z);\n" +
            "}\n" +
            "float noise(vec2 p) {\n" +
            "  vec2 i = floor(p), f = fract(p);\n" +
            "  vec2 u = f * f * (3.0 - 2.0 * f);\n" +
            "  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);\n" +
            "}\n";

    /**
     * AURORA: soft lights in the palette colours drifting and melting into each other over a
     * near-black ground, warped by layered noise; a faint diagonal sheen, vignette and grain.
     * Uniforms: res (pixels), time (seconds), c1..c3 (palette), mouse (-1..1, eased).
     */
    private static final String AURORA_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 res;\n" +
            "uniform float time;\n" +
            "uniform vec3 c1, c2, c3;\n" +
            "uniform vec2 mouse;\n" +
            NOISE +
            "float fbm3(vec2 p) {\n" +
            "  float v = 0.0, a = 0.5;\n" +
            "  for (int i = 0; i < 3; i++) { v += a * noise(p); p = p * 2.02 + vec2(1.7, 9.2); a *= 0.5; }\n" +
            "  return v / 0.875;\n" +
            "}\n" +
            "float blob(vec2 p, vec2 c, float k) { vec2 d = p - c; return exp(-dot(d, d) * k); }\n" +
            "void main() {\n" +
            "  vec2 frag = gl_FragCoord.xy;\n" +
            "  float aspect = res.x / res.y;\n" +
            "  vec2 p = vec2((frag.x / res.x - 0.5) * aspect, frag.y / res.y - 0.5) + mouse * 0.012;\n" +
            "  float t = time * 0.045;\n" +
            "  vec2 q = vec2(fbm3(p * 1.3 + vec2(0.0, t)), fbm3(p * 1.3 + vec2(5.2, -t * 0.8)));\n" +
            "  vec2 r = vec2(fbm3(p * 1.6 + 2.4 * q + vec2(1.7, 9.2) + t * 0.7), fbm3(p * 1.6 + 2.4 * q + vec2(8.3, 2.8) - t * 0.6));\n" +
            "  float f = fbm3(p * 1.1 + 2.0 * r);\n" +
            "  vec2 w = p + (r - 0.5) * 0.35;\n" +
            "  float b1 = blob(w, vec2(-0.42 * aspect + 0.10 * sin(t * 2.3), 0.20 + 0.08 * cos(t * 1.9)), 4.4);\n" +
            "  float b2 = blob(w, vec2(0.38 * aspect + 0.12 * cos(t * 1.6), -0.18 + 0.10 * sin(t * 2.1)), 3.8);\n" +
            "  float b3 = blob(w, vec2(0.05 + 0.18 * sin(t * 1.2), 0.42 + 0.06 * sin(t * 2.7)), 4.2);\n" +
            "  float b4 = blob(w, vec2(-0.10 * aspect + 0.14 * cos(t * 1.4), -0.40), 5.0);\n" +
            "  vec3 col = mix(vec3(0.012, 0.014, 0.024), vec3(0.022, 0.026, 0.042), frag.y / res.y);\n" +
            "  float lift = 0.30 + 0.85 * f * f;\n" +
            "  col += c1 * b1 * lift * 0.72;\n" +
            "  col += c2 * b2 * lift * 0.66;\n" +
            "  col += c3 * b3 * lift * 0.50;\n" +
            "  col += mix(c2, c1, 0.5) * b4 * lift * 0.30;\n" +
            "  float ribbon = smoothstep(0.55, 0.95, f) * (b1 + b2 + b3);\n" +
            "  col += mix(c1, c3, clamp(r.x, 0.0, 1.0)) * ribbon * 0.18;\n" +
            "  float band = p.x * 0.55 + p.y + 0.12 * sin(t * 2.6);\n" +
            "  col += vec3(0.85, 0.9, 1.0) * exp(-band * band * 9.0) * 0.022;\n" +
            "  vec2 v = vec2(p.x / aspect, p.y) * 1.55;\n" +
            "  col *= mix(0.62, 1.0, smoothstep(1.1, 0.2, length(v)));\n" +
            "  col += (hash(frag + fract(time * 7.13) * 397.0) - 0.5) * 0.016;\n" +
            "  gl_FragColor = vec4(max(col, vec3(0.0)), 1.0);\n" +
            "}";

    /**
     * NIGHT: an original blocky night scene. The picture is built on a grid of square blocks
     * (about screen height / 110 pixels): a sky fading to a palette-coloured glow at the horizon,
     * twinkling pixel stars, a square moon, drifting block clouds, and four ridges of stepped hills
     * with fog between them, the nearer ones moving more with the pointer; a few warm lights.
     */
    private static final String NIGHT_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 res;\n" +
            "uniform float time;\n" +
            "uniform vec3 c1, c2, c3;\n" +
            "uniform vec2 mouse;\n" +
            NOISE +
            "float n1(float x) { float i = floor(x), f = fract(x); return mix(hash(vec2(i, 3.1)), hash(vec2(i + 1.0, 3.1)), f * f * (3.0 - 2.0 * f)); }\n" +
            "float ridge(float x, float seed) { return n1(x + seed) * 0.62 + n1(x * 2.1 + seed * 1.7) * 0.28 + n1(x * 4.3 + seed * 2.3) * 0.10; }\n" +
            "void main() {\n" +
            "  float B = max(3.0, floor(res.y / 110.0));\n" +
            "  vec2 frag = gl_FragCoord.xy;\n" +
            "  vec2 grid = res / B;\n" +
            "  vec2 cell = floor(frag / B);\n" +
            "  float h = (cell.y + 0.5) / grid.y;\n" +
            "  vec3 horizon = mix(vec3(0.10, 0.10, 0.20), c1, 0.5) * 0.78;\n" +
            "  vec3 sky = mix(horizon, vec3(0.035, 0.050, 0.120), smoothstep(0.08, 0.48, h));\n" +
            "  sky = mix(sky, vec3(0.012, 0.018, 0.048), smoothstep(0.48, 1.0, h));\n" +
            "  vec3 col = sky;\n" +
            // stars, a little parallax
            "  vec2 sc = floor((frag + mouse * B * 1.5) / B);\n" +
            "  float s = hash(sc * 1.37 + 11.0);\n" +
            "  if (s > 0.986) {\n" +
            "    float tw = 0.55 + 0.45 * sin(time * (1.2 + (s - 0.986) * 300.0) + s * 300.0);\n" +
            "    col += mix(vec3(0.75, 0.82, 1.0), c3, 0.25) * tw * smoothstep(0.32, 0.75, h) * (s > 0.996 ? 0.95 : 0.5);\n" +
            "  }\n" +
            // the moon: a square with a softer square halo
            "  vec2 moon = floor(vec2(grid.x * 0.78, grid.y * 0.76) + mouse * 2.0);\n" +
            "  vec2 dm = abs(cell - moon);\n" +
            "  float md = max(dm.x, dm.y);\n" +
            "  col += vec3(0.55, 0.62, 0.85) * 0.10 * exp(-md * 0.22);\n" +
            "  if (md < 3.5) col = mix(vec3(0.86, 0.89, 0.95), vec3(0.70, 0.74, 0.84), step(0.5, hash(cell + 7.0)) * 0.35 * step(md, 2.5));\n" +
            // clouds: coarse noise on the block grid, drifting
            "  vec2 cc = floor((frag + vec2(time * B * 0.9, 0.0) + mouse * B * 3.0) / B);\n" +
            "  float cn = noise(cc / vec2(11.0, 4.5) + vec2(3.0, 1.0)) * 0.75 + noise(cc / vec2(5.0, 2.5) + 9.0) * 0.25;\n" +
            "  float cloud = step(0.64, cn) * smoothstep(0.52, 0.64, h) * (1.0 - smoothstep(0.86, 0.94, h));\n" +
            "  col = mix(col, mix(vec3(0.20, 0.23, 0.34), horizon * 1.2, 0.3), cloud * 0.45);\n" +
            // hills, far to near
            "  for (int i = 0; i < 4; i++) {\n" +
            "    float k = float(i);\n" +
            "    float shift = mouse.x * B * (1.5 + k * 3.5) + time * B * (0.06 + k * 0.10);\n" +
            "    float hx = floor((frag.x + shift) / B);\n" +
            "    float scale = 34.0 - k * 7.0;\n" +
            "    float base = grid.y * (0.40 - k * 0.075) + mouse.y * (k * 1.2);\n" +
            "    float amp = grid.y * (0.10 + k * 0.035);\n" +
            "    float top = floor(base + amp * (ridge(hx / scale, k * 17.0) - 0.5));\n" +
            "    if (cell.y < top) {\n" +
            "      float depth = k / 3.0;\n" +
            "      vec3 land = mix(mix(horizon, vec3(0.06, 0.08, 0.15), 0.55), vec3(0.010, 0.013, 0.024), depth);\n" +
            "      float below = top - cell.y;\n" +
            "      if (below <= 1.0) land += mix(c1 * 0.10, vec3(0.08, 0.10, 0.16), depth);\n" +
            "      land = mix(land, horizon * 0.8, (1.0 - depth) * 0.25 * (1.0 - smoothstep(0.0, 14.0, below)));\n" +
            "      if (i == 3 && below > 3.0) {\n" +
            "        float l = hash(vec2(hx, cell.y) + k * 31.0);\n" +
            "        if (l > 0.9965) land = mix(land, vec3(1.0, 0.64, 0.28), 0.75 * (0.8 + 0.2 * sin(time * 3.0 + l * 90.0)));\n" +
            "      }\n" +
            "      col = land;\n" +
            "    }\n" +
            "  }\n" +
            "  vec2 v = (frag / res - 0.5) * vec2(1.25, 1.6);\n" +
            "  col *= mix(0.6, 1.0, smoothstep(1.0, 0.25, length(v)));\n" +
            "  col += (hash(frag + fract(time * 7.13) * 397.0) - 0.5) * 0.012;\n" +
            "  gl_FragColor = vec4(max(col, vec3(0.0)), 1.0);\n" +
            "}";

    /**
     * PLASMA: large soft fields of fixed colour (deep indigo and navy on the left, purple at the top,
     * magenta in the middle, crimson and orange-red to the right, a dark ember glow at the bottom)
     * that drift and bleed into each other. The colour anchors are blended by normalised Gaussian
     * weights in a domain-warped space, so the borders are never hard; a pointer light, vignette,
     * soft tone curve and grain finish it. Ignores the palette uniforms on purpose.
     * Uniforms: res (pixels), time (seconds), mouse (-1..1, eased).
     */
    private static final String PLASMA_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 res;\n" +
            "uniform float time;\n" +
            "uniform vec2 mouse;\n" +
            NOISE +
            "float fbm4(vec2 p) {\n" +
            "  float v = 0.0, a = 0.5;\n" +
            "  for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.03 + vec2(3.1, 7.7); a *= 0.5; }\n" +
            "  return v / 0.9375;\n" +
            "}\n" +
            "float aspect;\n" +
            "float wgt(vec2 s, vec2 c, float k) { vec2 d = (s - c) * vec2(aspect, 1.0); return exp(-dot(d, d) * k); }\n" +
            "vec2 drift(vec2 c, float t, float seed) { return c + vec2(0.07 * sin(t * 1.3 + seed), 0.05 * cos(t * 1.1 + seed * 2.1)); }\n" +
            "void main() {\n" +
            "  vec2 frag = gl_FragCoord.xy;\n" +
            "  aspect = res.x / res.y;\n" +
            "  vec2 uv = frag / res;\n" +
            "  vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);\n" +
            "  float t = time * 0.03;\n" +
            "  vec2 q = vec2(fbm4(p * 1.1 + vec2(0.0, t)), fbm4(p * 1.1 + vec2(5.2, -t * 0.9)));\n" +
            "  vec2 r = vec2(fbm4(p * 1.3 + 2.2 * q + vec2(1.7, 9.2) + t * 0.8), fbm4(p * 1.3 + 2.2 * q + vec2(8.3, 2.8) - t * 0.7));\n" +
            "  float f = fbm4(p * 1.2 + 2.6 * r);\n" +
            "  vec2 s = uv + (r - 0.5) * 0.36 + (q - 0.5) * 0.10 + mouse * 0.008;\n" +
            "  float T = t * 6.0;\n" +
            "  float w1 = wgt(s, drift(vec2(0.22, 0.90), T, 0.0), 5.5);\n" +
            "  float w2 = wgt(s, drift(vec2(0.04, 0.44), T, 1.7), 4.2);\n" +
            "  float w3 = wgt(s, drift(vec2(0.42, 0.82), T, 3.1), 5.0);\n" +
            "  float w4 = wgt(s, drift(vec2(0.56, 0.52), T, 4.4), 5.5);\n" +
            "  float w5 = wgt(s, drift(vec2(0.74, 0.28), T, 5.9), 5.5);\n" +
            "  float w6 = wgt(s, drift(vec2(0.90, 0.60), T, 7.3), 5.2);\n" +
            "  float w7 = wgt(s, drift(vec2(0.13, 0.07), T, 8.8), 6.0);\n" +
            "  float w8 = wgt(s, drift(vec2(0.46, 0.05), T, 10.2), 6.5);\n" +
            "  float w9 = wgt(s, drift(vec2(0.70, 0.88), T, 11.6), 6.0);\n" +
            "  vec3 col = vec3(0.14, 0.12, 0.42) * w1 + vec3(0.04, 0.04, 0.13) * w2 + vec3(0.36, 0.11, 0.56) * w3\n" +
            "      + vec3(0.70, 0.11, 0.42) * w4 + vec3(0.64, 0.06, 0.20) * w5 + vec3(1.00, 0.42, 0.17) * w6\n" +
            "      + vec3(0.18, 0.05, 0.06) * w7 + vec3(0.36, 0.10, 0.05) * w8 + vec3(0.62, 0.11, 0.36) * w9;\n" +
            "  col /= max(w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8 + w9, 1e-4);\n" +
            "  col *= 0.52 + 0.80 * f;\n" +
            "  col += col * smoothstep(0.62, 0.92, f) * 0.22;\n" +
            "  vec2 dm = (uv - (mouse * 0.5 + 0.5)) * vec2(aspect, 1.0);\n" +
            "  col += vec3(1.0, 0.72, 0.82) * exp(-dot(dm, dm) * 5.0) * 0.05;\n" +
            "  vec2 v = (uv - 0.5) * vec2(1.2, 1.45);\n" +
            "  col *= mix(0.50, 1.0, smoothstep(1.05, 0.2, length(v)));\n" +
            "  col = 1.0 - exp(-col * 1.45);\n" +
            "  col += (hash(frag + fract(time * 7.13) * 397.0) - 0.5) * 0.022;\n" +
            "  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);\n" +
            "}";

    private static Program shape, glass, image, down, up, copy, downAlpha, upAlpha, glow, aurora, night, plasma;
    private static boolean loaded;

    private static void load() {
        if (loaded) return;
        loaded = true;
        shape = new Program("shape", SHAPE_VERTEX, SHAPE_FRAGMENT);
        glass = new Program("glass", SHAPE_VERTEX, GLASS_FRAGMENT);
        image = new Program("image", SHAPE_VERTEX, IMAGE_FRAGMENT);
        down = new Program("blur-down", PASS_VERTEX, DOWN_FRAGMENT);
        up = new Program("blur-up", PASS_VERTEX, UP_FRAGMENT);
        copy = new Program("blur-copy", PASS_VERTEX, COPY_FRAGMENT);
        downAlpha = new Program("glow-down", PASS_VERTEX, DOWN_ALPHA_FRAGMENT);
        upAlpha = new Program("glow-up", PASS_VERTEX, UP_ALPHA_FRAGMENT);
        glow = new Program("glow", PASS_VERTEX, GLOW_FRAGMENT);
        aurora = new Program("backdrop-aurora", PASS_VERTEX, AURORA_FRAGMENT);
        night = new Program("backdrop-night", PASS_VERTEX, NIGHT_FRAGMENT);
        plasma = new Program("backdrop-plasma", PASS_VERTEX, PLASMA_FRAGMENT);
        int error = GL11.glGetError();
        if (error != 0) Myau.LOGGER.warn("[Glass] GL error {} after shader setup", error);
    }

    public static Program shape() { load(); return shape; }
    public static Program glass() { load(); return glass; }
    public static Program image() { load(); return image; }
    public static Program down() { load(); return down; }
    public static Program up() { load(); return up; }
    public static Program copy() { load(); return copy; }
    public static Program downAlpha() { load(); return downAlpha; }
    public static Program upAlpha() { load(); return upAlpha; }
    public static Program glow() { load(); return glow; }
    public static Program aurora() { load(); return aurora; }
    public static Program night() { load(); return night; }
    public static Program plasma() { load(); return plasma; }

    private static Program better;

    /** Compiled on first use and apart from {@link #load()}: a driver that rejects it takes nothing else down. */
    public static Program glassBetter() {
        if (better == null) better = new Program("glass-better", SHAPE_VERTEX, BETTER_FRAGMENT);
        return better;
    }
}
