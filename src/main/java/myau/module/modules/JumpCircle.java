package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.JumpEvent;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class JumpCircle extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int COLOR_CUSTOM = 0, COLOR_THEME = 1;
    private static final int TEXTURE_SUPERNATURAL = 0, TEXTURE_CIRCLE = 1;
    private static final int CIRCLE_STEPS = 40;

    private static final ResourceLocation CIRCLE_ICON = new ResourceLocation("bettermyau/jumpcircle/circle1.png");
    private static final ResourceLocation SUPERNATURAL_ICON = new ResourceLocation("bettermyau/jumpcircle/circle2.png");

    public final ModeProperty colorMode = new ModeProperty("color", COLOR_THEME, new String[]{"Custom", "Theme"});
    public final FloatProperty minRadius = new FloatProperty("min-radius", 0.15F, 0.0F, 3.0F);
    public final FloatProperty maxRadius = new FloatProperty("max-radius", 0.8F, 0.0F, 3.0F);
    public final ColorProperty innerColor = new ColorProperty("inner-color", 0x000000, () -> this.colorMode.getValue() == COLOR_CUSTOM);
    public final IntProperty innerAlpha = new IntProperty("inner-alpha", 50, 0, 255, () -> this.colorMode.getValue() == COLOR_CUSTOM);
    public final ColorProperty outerColor = new ColorProperty("outer-color", 0x006FFF, () -> this.colorMode.getValue() == COLOR_CUSTOM);
    public final IntProperty outerAlpha = new IntProperty("outer-alpha", 255, 0, 255, () -> this.colorMode.getValue() == COLOR_CUSTOM);
    public final IntProperty hueOffsetAnim = new IntProperty("hue-offset-anim", 63, -360, 360);
    public final IntProperty lifeTime = new IntProperty("life-time", 20, 1, 50);
    public final BooleanProperty blackHole = new BooleanProperty("black-hole", false);
    public final BooleanProperty useTexture = new BooleanProperty("use-texture", true);
    public final ModeProperty texture = new ModeProperty(
            "texture", TEXTURE_SUPERNATURAL, new String[]{"Supernatural", "Circle"}, () -> this.useTexture.getValue()
    );
    public final BooleanProperty deepestLight = new BooleanProperty("deepest-light", true, () -> this.useTexture.getValue());

    private static final class JumpData {
        final Vec3 pos;
        final int endTime;

        JumpData(Vec3 pos, int endTime) {
            this.pos = pos;
            this.endTime = endTime;
        }
    }

    private final List<JumpData> circles = new ArrayList<>();
    private boolean hasJumped = false;
    /** Game ticks since start (xw runTimeTicks). */
    private int runTimeTicks = 0;

    public JumpCircle() {
        super("JumpCircle", false);
    }

    @Override
    public void onDisabled() {
        this.circles.clear();
        this.hasJumped = false;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.circles.clear();
        this.hasJumped = false;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            this.runTimeTicks++;
        }
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        this.hasJumped = true;
    }

    private void createCircleForEntity(Entity entity, float partialTicks) {
        double dx = entity.posX - entity.lastTickPosX;
        double dy = entity.posY - entity.lastTickPosY;
        double dz = entity.posZ - entity.lastTickPosZ;
        Vec3 entityPos = new Vec3(
                entity.lastTickPosX + dx * partialTicks + dx * 2.0,
                entity.lastTickPosY + dy * partialTicks + 0.005,
                entity.lastTickPosZ + dz * partialTicks + dz * 2.0
        );
        Block block = mc.theWorld.getBlockState(new BlockPos(entityPos)).getBlock();
        if (block == Blocks.snow || block == Blocks.snow_layer) {
            entityPos = entityPos.addVector(0.0, 0.125, 0.0);
        }
        this.circles.add(new JumpData(entityPos, this.runTimeTicks + (this.blackHole.getValue() ? this.lifeTime.getValue() : 0)));
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        if (mc.thePlayer.onGround && this.hasJumped) {
            this.createCircleForEntity(mc.thePlayer, partialTicks);
            this.hasJumped = false;
        }
        if (this.circles.isEmpty()) {
            return;
        }

        float lightFactor = this.deepestLight.getValue() ? 1.0F : 0.0F;
        float effectStrength = 0.0F;
        if (lightFactor >= 1.0F / 255.0F) {
            effectStrength = this.texture.getValue() == TEXTURE_CIRCLE ? 0.1F : 0.075F;
        }
        int lifeTime = this.lifeTime.getValue();
        float min = this.minRadius.getValue();
        float max = this.maxRadius.getValue();
        boolean textured = this.useTexture.getValue();
        IAccessorRenderManager renderManager = (IAccessorRenderManager) mc.getRenderManager();

        // setup (everything changed here is put back below)
        boolean lightingEnabled = GL11.glIsEnabled(GL11.GL_LIGHTING);
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.0F);
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        if (lightingEnabled) {
            GlStateManager.disableLighting();
        }
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.tryBlendFuncSeparate(770, 1, 1, 0);
        GlStateManager.translate(-renderManager.getRenderPosX(), -renderManager.getRenderPosY(), -renderManager.getRenderPosZ());

        for (Iterator<JumpData> iterator = this.circles.iterator(); iterator.hasNext(); ) {
            JumpData data = iterator.next();
            float progress = ((this.runTimeTicks + partialTicks) - data.endTime) / lifeTime;
            float radius = min + (max - min) * progress;
            if (textured) {
                this.renderTexturedCircle(data.pos, radius, 1.0F - progress, lightFactor, effectStrength);
            } else {
                this.renderSimpleCircle(data.pos, radius, progress);
            }
            if (progress >= 1.0F) {
                iterator.remove();
            }
        }

        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        if (lightingEnabled) {
            GlStateManager.enableLighting();
        }
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /** Inner and outer colour of a circle; {@code timeFraction} as passed by the two render paths of xw. */
    private Color[] getColors(float timeFraction) {
        if (this.colorMode.getValue() == COLOR_THEME) {
            Color base = Color.WHITE;
            HUD hud = Myau.moduleManager == null ? null : (HUD) Myau.moduleManager.modules.get(HUD.class);
            if (hud != null) {
                base = hud.getColor(System.currentTimeMillis());
            }
            Color faded = withAlpha(base, clampAlpha((int) (base.getAlpha() * (1 - timeFraction))));
            return new Color[]{faded, faded};
        }
        return new Color[]{
                this.animateColor(withAlpha(new Color(this.innerColor.getValue()), this.innerAlpha.getValue()), 1.0F - timeFraction),
                this.animateColor(withAlpha(new Color(this.outerColor.getValue()), this.outerAlpha.getValue()), 1.0F - timeFraction)
        };
    }

    private void renderSimpleCircle(Vec3 pos, float radius, float timeFraction) {
        Color[] colors = this.getColors(timeFraction);
        Color inner = colors[0];
        Color outer = colors[1];

        GlStateManager.disableTexture2D();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableDepth();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        for (int i = 0; i <= CIRCLE_STEPS; i++) {
            double theta = 2 * Math.PI * i / CIRCLE_STEPS;
            Color hue = shiftHue(inner, i / CIRCLE_STEPS);
            GL11.glColor4f(hue.getRed() / 255.0F, hue.getGreen() / 255.0F, hue.getBlue() / 255.0F, inner.getAlpha() / 255.0F);
            GL11.glVertex3d(pos.xCoord - Math.sin(theta) * radius, pos.yCoord, pos.zCoord + Math.cos(theta) * radius);
        }
        GL11.glEnd();

        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i <= CIRCLE_STEPS; i++) {
            double theta = 2 * Math.PI * i / CIRCLE_STEPS;
            Color hue = shiftHue(outer, i / CIRCLE_STEPS);
            GL11.glColor4f(hue.getRed() / 255.0F, hue.getGreen() / 255.0F, hue.getBlue() / 255.0F, outer.getAlpha() / 255.0F);
            GL11.glVertex3d(pos.xCoord - Math.sin(theta) * radius, pos.yCoord, pos.zCoord + Math.cos(theta) * radius);
        }
        GL11.glEnd();

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 1, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void renderTexturedCircle(Vec3 pos, double maxRadius, float timeFraction, float shift, float intensity) {
        float waveValue = 1.0F - timeFraction;
        float wave = easeWave(waveValue);
        float alphaFraction = (float) easeOutCirc(wave);
        if (timeFraction < 0.5F) {
            alphaFraction *= (float) easeInOutExpo(alphaFraction);
        }
        double mainFactor = timeFraction > 0.5F ? easeOutElasticX(wave * wave) : easeOutBounce(wave);
        float circleRadius = (float) (mainFactor * maxRadius);
        double rotation = easeInOutElasticX(wave) * 90.0 / (1.0 + wave);
        ResourceLocation textureResource = this.texture.getValue() == TEXTURE_CIRCLE ? CIRCLE_ICON : SUPERNATURAL_ICON;

        Color[] colors = this.getColors(timeFraction);
        float red = colors[0].getRed() / 255.0F;
        float green = colors[0].getGreen() / 255.0F;
        float blue = colors[0].getBlue() / 255.0F;
        float alpha = colors[0].getAlpha() / 255.0F;
        float red2 = colors[1].getRed() / 255.0F;
        float green2 = colors[1].getGreen() / 255.0F;
        float blue2 = colors[1].getBlue() / 255.0F;

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();

        mc.getTextureManager().bindTexture(textureResource);
        mc.getTextureManager().getTexture(textureResource).setBlurMipmap(true, true);
        GlStateManager.pushMatrix();
        GlStateManager.translate(pos.xCoord - circleRadius / 2.0, pos.yCoord, pos.zCoord - circleRadius / 2.0);
        GlStateManager.rotate(90.0F, 1.0F, 0.0F, 0.0F);
        // rotate around the centre of the quad
        GlStateManager.translate(circleRadius / 2.0, circleRadius / 2.0, 0.0);
        GlStateManager.rotate((float) rotation, 0.0F, 0.0F, 1.0F);
        GlStateManager.translate(-circleRadius / 2.0, -circleRadius / 2.0, 0.0);
        worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        worldRenderer.pos(0.0, 0.0, 0.0).tex(0.0, 0.0).color(red, green, blue, alpha).endVertex();
        worldRenderer.pos(0.0, circleRadius, 0.0).tex(0.0, 1.0).color(red, green, blue, alpha).endVertex();
        worldRenderer.pos(circleRadius, circleRadius, 0.0).tex(1.0, 1.0).color(red, green, blue, alpha).endVertex();
        worldRenderer.pos(circleRadius, 0.0, 0.0).tex(1.0, 0.0).color(red, green, blue, alpha).endVertex();
        tessellator.draw();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();

        if (shift >= 1.0F / 255.0F) {
            float alphaCheck = alphaFraction * intensity * shift;
            if (alphaCheck * 255 < 1) {
                return;
            }
            float layerAlpha = (int) (alphaCheck * 255) / 255.0F;

            GlStateManager.pushMatrix();
            GlStateManager.translate(pos.xCoord, pos.yCoord, pos.zCoord);
            GlStateManager.rotate((float) rotation, 0.0F, 1.0F, 0.0F);
            worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            int polygons = 40;
            float maxY = circleRadius / 3.5F;
            float maxXZ = circleRadius / 7.0F;
            for (int i = 1; i < polygons; i++) {
                float fraction = i / (float) polygons;
                float fractionValue = fraction - (1.5F / polygons);
                float circVal = (float) easeOutCirc(easeWave(fractionValue));
                float half = (circleRadius + circVal * maxXZ) / 2.0F;
                double y = maxY * i / polygons - maxY / polygons;
                worldRenderer.pos(-half, y, -half).tex(0.0, 0.0).color(red2, green2, blue2, layerAlpha).endVertex();
                worldRenderer.pos(-half, y, half).tex(0.0, 1.0).color(red2, green2, blue2, layerAlpha).endVertex();
                worldRenderer.pos(half, y, half).tex(1.0, 1.0).color(red2, green2, blue2, layerAlpha).endVertex();
                worldRenderer.pos(half, y, -half).tex(1.0, 0.0).color(red2, green2, blue2, layerAlpha).endVertex();
            }
            tessellator.draw();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private Color animateColor(Color baseColor, float progress) {
        Color color = withAlpha(baseColor, clampAlpha((int) (baseColor.getAlpha() * (1 - progress))));
        int hueOffset = this.hueOffsetAnim.getValue();
        if (hueOffset == 0) {
            return color;
        }
        return shiftHue(color, (int) (hueOffset * progress));
    }

    private static int clampAlpha(int alpha) {
        return Math.max(0, Math.min(255, alpha));
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampAlpha(alpha));
    }

    private static Color shiftHue(Color color, int shift) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        Color shifted = new Color(Color.HSBtoRGB((hsb[0] + (float) shift / 360) % 1.0F, hsb[1], hsb[2]));
        return new Color(shifted.getRed(), shifted.getGreen(), shifted.getBlue(), color.getAlpha());
    }

    // ── easing (xw AnimationUtil) ──

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static float easeWave(float value) {
        return (float) Math.sin(clamp01(value) * Math.PI);
    }

    private static double easeOutCirc(double t) {
        double x = clamp01(t);
        return Math.sqrt(1 - (x - 1) * (x - 1));
    }

    private static double easeInOutExpo(double t) {
        double x = clamp01(t);
        if (x == 0.0) {
            return 0.0;
        }
        if (x == 1.0) {
            return 1.0;
        }
        return x < 0.5 ? Math.pow(2.0, 20 * x - 10) / 2.0 : (2 - Math.pow(2.0, -20 * x + 10)) / 2.0;
    }

    private static double easeOutBounce(double t) {
        double x = clamp01(t);
        double n1 = 7.5625;
        double d1 = 2.75;
        if (x < 1 / d1) {
            return n1 * x * x;
        } else if (x < 2 / d1) {
            x -= 1.5 / d1;
            return n1 * x * x + 0.75;
        } else if (x < 2.5 / d1) {
            x -= 2.25 / d1;
            return n1 * x * x + 0.9375;
        } else {
            x -= 2.625 / d1;
            return n1 * x * x + 0.984375;
        }
    }

    private static double easeOutElasticX(double t) {
        double x = clamp01(t);
        if (x == 0.0 || x == 1.0) {
            return x;
        }
        double c4 = (2 * Math.PI) / 3;
        return Math.pow(2.0, -10 * x) * Math.sin((x * 10 - 0.75) * c4) + 1;
    }

    private static double easeInOutElasticX(double t) {
        double x = clamp01(t);
        if (x == 0.0 || x == 1.0) {
            return x;
        }
        double c5 = (2 * Math.PI) / 4.5;
        return x < 0.5
                ? -(Math.pow(2.0, -20 * x) * Math.sin((-20 * x - 11.125) * c5)) / 2.0
                : (Math.pow(2.0, -20 * x + 20) * Math.sin((-20 * x + 11.125) * c5)) / 2.0 + 1.0;
    }
}
