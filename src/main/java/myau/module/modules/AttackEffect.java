package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.FxRenderUtil;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumParticleTypes;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Effects on the entity you hit, in two styles.
 * <ul>
 * <li>SHAPES: small flat shapes burst out of the target while it is flashing red, drift,
 * fall and fade. Drawn by the client.</li>
 * <li>VANILLA: the game's own particles (or a lightning bolt) and a sound on every attack.</li>
 * </ul>
 * Everything is client side; nothing is sent to the server.
 */
public class AttackEffect extends Module {
    public final ModeProperty style = new ModeProperty("style", 0, new String[]{"SHAPES", "VANILLA"});

    // SHAPES
    public final IntProperty amount = new IntProperty("amount", 8, 1, 50, this::isShapes);
    public final IntProperty lifeTime = new IntProperty("life-time", 1000, 100, 5000, this::isShapes);
    public final FloatProperty speed = new FloatProperty("speed", 1.0F, 0.1F, 5.0F, this::isShapes);
    public final FloatProperty gravity = new FloatProperty("gravity", 1.0F, 0.0F, 3.0F, this::isShapes);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.1F, 3.0F, this::isShapes);
    public final ModeProperty shape = new ModeProperty("shape", 0, new String[]{"TRIANGLE", "CIRCLE", "SQUARE", "STAR", "DIAMOND"}, this::isShapes);
    public final ModeProperty colorMode = new ModeProperty("color", 0, new String[]{"CUSTOM", "HEALTH", "RAINBOW", "SKY", "FADE", "HUD"}, this::isShapes);
    public final ColorProperty customColor = new ColorProperty(
            "custom-color", 0xFFFFFF, () -> this.isShapes() && (this.colorMode.getValue() == 0 || this.colorMode.getValue() == 4)
    );
    public final IntProperty alpha = new IntProperty("alpha", 255, 0, 255, this::isShapes);
    public final FloatProperty saturation = new FloatProperty("saturation", 1.0F, 0.0F, 1.0F, () -> this.isShapes() && this.colorMode.getValue() == 3);
    public final FloatProperty brightness = new FloatProperty("brightness", 1.0F, 0.0F, 1.0F, () -> this.isShapes() && this.colorMode.getValue() == 3);

    // VANILLA
    public final ModeProperty particle = new ModeProperty(
            "particle", 1, new String[]{"NONE", "BLOOD", "LIGHTNING", "FIRE", "HEART", "WATER", "SMOKE", "MAGIC", "CRITS"}, this::isVanilla
    );
    public final IntProperty particleAmount = new IntProperty("particle-amount", 5, 1, 20, () -> this.isVanilla() && this.particle.getValue() != 0);
    public final ModeProperty sound = new ModeProperty("sound", 1, new String[]{"NONE", "HIT", "ORB", "POP", "SPLASH", "LIGHTNING"}, this::isVanilla);
    public final FloatProperty volume = new FloatProperty("volume", 1.0F, 0.1F, 5.0F, () -> this.isVanilla() && this.sound.getValue() != 0);
    public final FloatProperty pitch = new FloatProperty("pitch", 1.0F, 0.1F, 5.0F, () -> this.isVanilla() && this.sound.getValue() != 0);

    private static final String[] SOUNDS = {null, "random.bowhit", "random.orb", "random.pop", "random.splash", "ambient.weather.thunder"};

    /** Length of one simulation step. A shape's motion and age advance once per step. */
    private static final long STEP_NANOS = 5_000_000L;
    /** Steps caught up in one frame at most, so a long stall does not fast-forward the shapes. */
    private static final int MAX_STEPS = 40;
    private static final int MAX_SHAPES = 4000;
    /** Ticks after the last attack during which the target still sheds shapes when hurt. */
    private static final int TARGET_TICKS = 40;
    private static final double MAX_DISTANCE_SQ = 300.0;
    private static final float SHAPE_SIZE = 0.04F;
    private static final int GLOW_ALPHA = 50;

    /** Outlines of the shapes as triangle lists, x and y alternating; see {@link #shape}. */
    private static final float[][] SHAPES = {
            {0.0F, -1.5F, -1.0F, 0.5F, 1.0F, 0.5F},
            fan(10, 1.0F, 1.0F),
            {-1.0F, -1.0F, 1.0F, -1.0F, 1.0F, 1.0F, -1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F},
            fan(36, 1.5F, 0.6F),
            {0.0F, -1.5F, 1.0F, 0.0F, 0.0F, 1.5F, 0.0F, -1.5F, 0.0F, 1.5F, -1.0F, 0.0F}
    };

    private static final class Shape {
        double x, y, z;
        double motionX, motionY, motionZ;
        int age;
        final int maxAge;

        Shape(double x, double y, double z, float speed, int maxAge) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.motionX = (Math.random() * 2.5 - 1.25) * 0.04 * speed;
            this.motionY = (Math.random() * 0.5 - 0.2) * 0.04 * speed;
            this.motionZ = (Math.random() * 2.5 - 1.25) * 0.04 * speed;
            this.maxAge = maxAge;
        }

        void step(float gravity) {
            this.x += this.motionX;
            this.y += this.motionY;
            this.z += this.motionZ;
            this.motionX *= 0.998;
            this.motionY -= 3.1E-5 * gravity;
            this.motionZ *= 0.998;
            this.age++;
        }
    }

    private final ArrayDeque<Shape> shapes = new ArrayDeque<>();
    private final double[] axes = new double[6];
    private EntityLivingBase target;
    private int targetTicks;
    private int lastHurtTime;
    private long lastFrame;
    private long pendingNanos;

    public AttackEffect() {
        super("AttackEffect", false);
    }

    private boolean isShapes() {
        return this.style.getValue() == 0;
    }

    private boolean isVanilla() {
        return this.style.getValue() == 1;
    }

    /** A disc or star around the origin: one triangle per {@code step} degrees, radius alternating. */
    private static float[] fan(int step, float evenRadius, float oddRadius) {
        int count = 360 / step;
        float[] triangles = new float[count * 6];
        for (int i = 0; i < count; i++) {
            double from = Math.toRadians(i * step), to = Math.toRadians((i + 1) * step);
            float fromRadius = i % 2 == 0 ? evenRadius : oddRadius, toRadius = i % 2 == 0 ? oddRadius : evenRadius;
            triangles[i * 6 + 2] = (float) Math.cos(from) * fromRadius;
            triangles[i * 6 + 3] = (float) Math.sin(from) * fromRadius;
            triangles[i * 6 + 4] = (float) Math.cos(to) * toRadius;
            triangles[i * 6 + 5] = (float) Math.sin(to) * toRadius;
        }
        return triangles;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.isCancelled() || !(event.getTarget() instanceof EntityLivingBase)) return;
        EntityLivingBase entity = (EntityLivingBase) event.getTarget();

        if (this.isShapes()) {
            this.target = entity;
            this.targetTicks = 0;
            return;
        }

        int kind = this.particle.getValue();
        if (kind != 0) {
            int count = this.particleAmount.getValue();
            for (int i = 0; i < count; i++) this.spawnVanilla(kind, entity);
        }
        int soundIndex = this.sound.getValue();
        if (soundIndex > 0 && soundIndex < SOUNDS.length) {
            mc.thePlayer.playSound(SOUNDS[soundIndex], this.volume.getValue(), this.pitch.getValue());
        }
    }

    private void spawnVanilla(int kind, EntityLivingBase entity) {
        switch (kind) {
            case 1: // BLOOD
                mc.theWorld.spawnParticle(
                        EnumParticleTypes.BLOCK_CRACK, entity.posX, entity.posY + entity.height - 0.75, entity.posZ,
                        0.0, 0.0, 0.0, Block.getStateId(Blocks.redstone_block.getDefaultState())
                );
                break;
            case 2: // LIGHTNING
                mc.theWorld.addWeatherEffect(new EntityLightningBolt(mc.theWorld, entity.posX, entity.posY, entity.posZ));
                break;
            case 3:
                this.spawnOnBody(EnumParticleTypes.LAVA, entity, false);
                break;
            case 4:
                this.spawnOnBody(EnumParticleTypes.HEART, entity, false);
                break;
            case 5:
                this.spawnOnBody(EnumParticleTypes.WATER_DROP, entity, false);
                break;
            case 6:
                this.spawnOnBody(EnumParticleTypes.SMOKE_NORMAL, entity, false);
                break;
            case 7:
                this.spawnOnBody(EnumParticleTypes.CRIT_MAGIC, entity, true);
                break;
            case 8:
                this.spawnOnBody(EnumParticleTypes.CRIT, entity, true);
                break;
            default:
                break;
        }
    }

    /**
     * One vanilla particle somewhere inside the entity. Crit sparks get the outward push the
     * game gives them on a real critical hit; the other kinds move on their own.
     */
    private void spawnOnBody(EnumParticleTypes type, EntityLivingBase entity, boolean scatter) {
        double x = entity.posX + (Math.random() - 0.5) * entity.width;
        double y = entity.posY + Math.random() * entity.height;
        double z = entity.posZ + (Math.random() - 0.5) * entity.width;
        double motionX = scatter ? Math.random() * 2.0 - 1.0 : 0.0;
        double motionY = scatter ? Math.random() * 2.0 - 1.0 + 0.2 : 0.0;
        double motionZ = scatter ? Math.random() * 2.0 - 1.0 : 0.0;
        mc.effectRenderer.spawnEffectParticle(type.getParticleID(), x, y, z, motionX, motionY, motionZ);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null || this.target == null) return;

        if (!this.isShapes() || ++this.targetTicks > TARGET_TICKS || this.target.isDead || this.target.worldObj != mc.theWorld) {
            this.target = null;
            return;
        }
        // hurtTime counts down over the red flash, so a burst leaves every tick of it.
        int hurtTime = this.target.hurtTime;
        if (hurtTime == 0 || hurtTime == this.lastHurtTime) return;
        this.lastHurtTime = hurtTime;

        int count = this.amount.getValue();
        float velocity = this.speed.getValue();
        int maxAge = this.lifeTime.getValue();
        for (int i = 0; i < count; i++) {
            if (this.shapes.size() >= MAX_SHAPES) this.shapes.pollFirst();
            this.shapes.addLast(new Shape(
                    this.target.posX + (Math.random() - 0.5) * 0.5,
                    this.target.posY + Math.random() + 0.5,
                    this.target.posZ + (Math.random() - 0.5) * 0.5,
                    velocity, maxAge
            ));
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        long now = System.nanoTime();
        if (this.shapes.isEmpty()) {
            this.lastFrame = now;
            this.pendingNanos = 0L;
            return;
        }

        // Fixed steps, so the shapes move the same at any frame rate.
        if (!mc.isGamePaused()) this.pendingNanos += now - this.lastFrame;
        this.lastFrame = now;
        int steps = (int) Math.min(this.pendingNanos / STEP_NANOS, MAX_STEPS);
        this.pendingNanos = this.pendingNanos >= STEP_NANOS * MAX_STEPS ? 0L : this.pendingNanos - steps * STEP_NANOS;

        float pull = this.gravity.getValue();
        for (Iterator<Shape> iterator = this.shapes.iterator(); iterator.hasNext(); ) {
            Shape shape = iterator.next();
            for (int i = 0; i < steps && shape.age < shape.maxAge; i++) shape.step(pull);
            if (shape.age >= shape.maxAge || mc.thePlayer.getDistanceSq(shape.x, shape.y, shape.z) > MAX_DISTANCE_SQ) {
                iterator.remove();
            }
        }
        if (this.shapes.isEmpty()) return;

        double renderX = FxRenderUtil.renderX(), renderY = FxRenderUtil.renderY(), renderZ = FxRenderUtil.renderZ();
        int rgb = this.currentColor();
        int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
        int baseAlpha = this.alpha.getValue();
        float[] outline = SHAPES[Math.min(Math.max(this.shape.getValue(), 0), SHAPES.length - 1)];
        // The shapes face the camera; the minus mirrors them like the name tag transform does.
        double size = -SHAPE_SIZE * this.scale.getValue();
        FxRenderUtil.cameraAxes(this.axes);
        double rightX = this.axes[0] * size, rightY = this.axes[1] * size, rightZ = this.axes[2] * size;
        double upX = this.axes[3] * size, upY = this.axes[4] * size, upZ = this.axes[5] * size;
        FxRenderUtil.beginFrustum();

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        int index = 0;
        for (Shape shape : this.shapes) {
            int i = index++;
            double distance = mc.thePlayer.getDistance(shape.x, shape.y - 1.0, shape.z);
            // Far away only some of the shapes are drawn.
            if (i % 10 != 0 && distance > 25.0) continue;
            if (i % 3 == 0 && distance > 15.0) continue;
            if (!FxRenderUtil.inFrustum(shape.x, shape.y, shape.z, 0.1)) continue;

            float progress = (float) shape.age / (float) shape.maxAge;
            int shapeAlpha = Math.min(Math.max((int) (baseAlpha * (1.0F - progress * 0.5F)), 0), 255);
            double x = shape.x - renderX, y = shape.y - renderY, z = shape.z - renderZ;
            // Up close a second, faint copy on top makes the shape a little denser.
            int layers = distance < 4.0 ? 2 : 1;
            for (int layer = 0; layer < layers; layer++) {
                int layerAlpha = layer == 0 ? shapeAlpha : GLOW_ALPHA;
                for (int v = 0; v < outline.length; v += 2) {
                    float localX = outline[v], localY = outline[v + 1];
                    buffer.pos(x + rightX * localX + upX * localY, y + rightY * localX + upY * localY, z + rightZ * localX + upZ * localY)
                            .color(red, green, blue, layerAlpha).endVertex();
                }
            }
        }
        tessellator.draw();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.resetColor();
    }

    private int currentColor() {
        switch (this.colorMode.getValue()) {
            case 0:
                return this.customColor.getValue();
            case 1:
                return FxRenderUtil.healthColor(mc.thePlayer.getHealth(), mc.thePlayer.getMaxHealth());
            case 2:
                return FxRenderUtil.rainbow();
            case 3:
                return FxRenderUtil.skyRainbow(this.saturation.getValue(), this.brightness.getValue());
            case 4:
                return FxRenderUtil.fade(this.customColor.getValue());
            case 5:
                return FxRenderUtil.themeColor();
            default:
                return 0xFFFFFF;
        }
    }

    private void reset() {
        this.shapes.clear();
        this.target = null;
        this.lastHurtTime = 0;
        this.pendingNanos = 0L;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.reset();
    }

    @Override
    public void onDisabled() {
        this.reset();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.style.getModeString())};
    }
}
