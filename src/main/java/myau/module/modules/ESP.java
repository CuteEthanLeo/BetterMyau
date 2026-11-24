package myau.module.modules;

import myau.Myau;
import myau.enums.ChatColors;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.ui.glass.Draw;
import myau.ui.glass.GlState;
import myau.ui.glass.WorldBoxes;
import myau.ui.glass.WorldGlow;
import myau.util.ColorUtil;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import myau.util.shader.GlowShader;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.awt.Color;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Player ESP. No mode puts a frosted plate behind the player any more (that was a blurry
 * rectangle, and it cost a second full-screen blur every frame):
 * <ul>
 * <li>2D: corner brackets with a soft glow, drawn flat in the HUD pass from rectangles the
 * 3D pass projected;</li>
 * <li>3D: a tinted box that fades upwards, with a halo;</li>
 * <li>OUTLINE: the player's own silhouette, glowing.</li>
 * </ul>
 */
public class ESP extends Module {
    private final GlowShader glowShader = new GlowShader();
    // Read by mixins while silhouettes are being drawn: no name tags, no enchantment glint.
    private boolean outline = true;
    private boolean glow = true;

    private static final FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    public final ModeProperty mode = new ModeProperty("mode", 2, new String[]{"NONE", "2D", "3D", "OUTLINE", "FAKECORNER", "FAKE2D"});
    public final ModeProperty color = new ModeProperty("color", 0, new String[]{"DEFAULT", "TEAMS", "HUD"});
    public final ModeProperty healthBar = new ModeProperty("health-bar", 0, new String[]{"NONE", "2D", "RAVEN"});
    public final BooleanProperty halo = new BooleanProperty("glow", true);
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty friends = new BooleanProperty("friends", true);
    public final BooleanProperty enemies = new BooleanProperty("enemies", true);
    public final BooleanProperty self = new BooleanProperty("self", false);
    public final BooleanProperty bots = new BooleanProperty("bots", false);

    /** A player's box on screen, found in the 3D pass and drawn in the 2D pass. */
    private static final class Frame {
        float x, y, w, h, health;
        int color;
    }

    private final List<EntityPlayer> targets = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    private int frameCount;
    private boolean drawFailed;

    public ESP() {
        super("ESP", false);
    }

    private boolean shouldRenderPlayer(EntityPlayer entityPlayer) {
        if (entityPlayer.deathTime > 0) {
            return false;
        } else if (mc.getRenderViewEntity().getDistanceToEntity(entityPlayer) > 512.0F) {
            return false;
        } else if (!entityPlayer.ignoreFrustumCheck && !RenderUtil.isInViewFrustum(entityPlayer.getEntityBoundingBox(), 0.1F)) {
            return false;
        } else if (entityPlayer != mc.thePlayer && entityPlayer != mc.getRenderViewEntity()) {
            if (TeamUtil.isBot(entityPlayer)) {
                return this.bots.getValue();
            } else if (TeamUtil.isFriend(entityPlayer)) {
                return this.friends.getValue();
            } else {
                return TeamUtil.isTarget(entityPlayer) ? this.enemies.getValue() : this.players.getValue();
            }
        } else {
            return this.self.getValue() && mc.gameSettings.thirdPersonView != 0;
        }
    }

    private Color getEntityColor(EntityPlayer entityPlayer) {
        if (TeamUtil.isFriend(entityPlayer)) {
            return Myau.friendManager.getColor();
        } else if (TeamUtil.isTarget(entityPlayer)) {
            return Myau.targetManager.getColor();
        } else {
            switch (this.color.getValue()) {
                case 0:
                    return TeamUtil.getTeamColor(entityPlayer, 1.0F);
                case 1:
                    int teamColor = TeamUtil.isSameTeam(entityPlayer) ? ChatColors.BLUE.toAwtColor() : ChatColors.RED.toAwtColor();
                    return new Color(teamColor);
                case 2:
                    int hudColor = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis()).getRGB();
                    return new Color(hudColor);
                default:
                    return new Color(-1);
            }
        }
    }

    public boolean isOutlineEnabled() {
        return this.outline;
    }

    public boolean isGlowEnabled() {
        return this.glow;
    }

    // ---------------------------------------------------------------- 3D pass

    @EventTarget
    public void onRender(Render3DEvent event) {
        this.frameCount = 0;
        int modeVal = this.mode.getValue();
        int healthVal = this.healthBar.getValue();
        if (!this.isEnabled() || (modeVal == 0 && healthVal == 0) || mc.theWorld == null || mc.getRenderViewEntity() == null) return;

        this.targets.clear();
        List<Entity> entities = TeamUtil.getRenderEntitiesSorted();
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            if (entity instanceof EntityPlayer && this.shouldRenderPlayer((EntityPlayer) entity)) this.targets.add((EntityPlayer) entity);
        }
        if (this.targets.isEmpty()) return;
        float partial = event.getPartialTicks();
        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();

        // 2D box and 2D health bar: only find where each player is on screen; drawn later.
        if (modeVal == 1 || healthVal == 1) this.projectFrames(partial, renderX, renderY, renderZ);

        if (modeVal == 2) {
            WorldBoxes.begin();
            for (EntityPlayer player : this.targets) {
                AxisAlignedBB bb = player.getEntityBoundingBox();
                double ox = RenderUtil.lerpDouble(player.posX, player.lastTickPosX, partial) - player.posX - renderX;
                double oy = RenderUtil.lerpDouble(player.posY, player.lastTickPosY, partial) - player.posY - renderY;
                double oz = RenderUtil.lerpDouble(player.posZ, player.lastTickPosZ, partial) - player.posZ - renderZ;
                WorldBoxes.add(bb.minX + ox - 0.1, bb.minY + oy - 0.1, bb.minZ + oz - 0.1,
                        bb.maxX + ox + 0.1, bb.maxY + oy + 0.1, bb.maxZ + oz + 0.1,
                        this.getEntityColor(player).getRGB() & 0xffffff, 1.0F, true);
            }
            WorldBoxes.draw(0.16F, 1.5F, this.halo.getValue());
        } else if (modeVal == 3) {
            this.drawSilhouettes(partial);
        }

        if (modeVal == 4 || modeVal == 5 || healthVal == 2) {
            RenderUtil.enableRenderState();
            for (EntityPlayer player : this.targets) {
                if (modeVal == 4) {
                    Color color = this.getEntityColor(player);
                    RenderUtil.drawCornerESP(player, color.getRed() / 255.0F, color.getGreen() / 255.0F, color.getBlue() / 255.0F);
                }
                if (modeVal == 5) {
                    Color color = this.getEntityColor(player);
                    RenderUtil.drawFake2DESP(player, color.getRed() / 255.0F, color.getGreen() / 255.0F, color.getBlue() / 255.0F);
                }
                if (healthVal == 2) {
                    double x = RenderUtil.lerpDouble(player.posX, player.lastTickPosX, partial) - renderX;
                    double y = RenderUtil.lerpDouble(player.posY, player.lastTickPosY, partial) - renderY - 0.1F;
                    double z = RenderUtil.lerpDouble(player.posZ, player.lastTickPosZ, partial) - renderZ;
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(x, y, z);
                    GlStateManager.rotate(mc.getRenderManager().playerViewY * -1.0F, 0.0F, 1.0F, 0.0F);
                    float percent = healthOf(player);
                    Color healthColor = ColorUtil.getHealthBlend(percent);
                    float height = player.height + 0.2F;
                    RenderUtil.drawRect3D(0.57250005F, -0.027500002F, 0.7275F, height + 0.027500002F, Color.black.getRGB());
                    RenderUtil.drawRect3D(0.6F, 0.0F, 0.70000005F, height, Color.darkGray.getRGB());
                    RenderUtil.drawRect3D(0.6F, 0.0F, 0.70000005F, height * percent, healthColor.getRGB());
                    GlStateManager.popMatrix();
                }
            }
            RenderUtil.disableRenderState();
        }
        this.targets.clear();
    }

    private static float healthOf(EntityPlayer player) {
        float heal = player.getHealth() + player.getAbsorptionAmount();
        return Math.min(Math.max(heal / Math.max(1.0F, player.getMaxHealth()), 0.0F), 1.0F);
    }

    /**
     * OUTLINE: every player once more as a flat silhouette, into WorldGlow's mask instead of
     * onto the screen. The camera is the real one (this is the 3D pass), the mask has no depth
     * buffer, so players behind walls glow too.
     */
    private void drawSilhouettes(float partial) {
        if (!WorldGlow.begin()) return;
        int savedState = GlState.capture();
        boolean shadow = mc.gameSettings.entityShadows;
        mc.gameSettings.entityShadows = false;
        this.outline = false;
        this.glow = false;
        GlStateManager.pushMatrix();
        try {
            this.glowShader.use();
            for (EntityPlayer player : this.targets) {
                this.glowShader.W(this.getEntityColor(player));
                boolean invisible = player.isInvisible();
                player.setInvisible(false);
                try {
                    mc.getRenderManager().renderEntityStatic(player, partial, true);
                } finally {
                    player.setInvisible(invisible);
                }
            }
        } catch (RuntimeException e) {
            if (!this.drawFailed) {
                this.drawFailed = true;
                Myau.LOGGER.error("[ESP] outline drawing failed (logged once)", e);
            }
        } finally {
            this.glowShader.stop();
            this.glow = true;
            this.outline = true;
            mc.gameSettings.entityShadows = shadow;
            GlStateManager.popMatrix();
            WorldGlow.end();
            mc.entityRenderer.disableLightmap();
            RenderHelper.disableStandardItemLighting();
            GlState.restore(savedState);
        }
    }

    /**
     * Projects each target's (interpolated) bounding box to the screen with the current camera
     * matrices, read once for all of them. Players with any corner behind the camera are
     * skipped (gluProject mirrors those points and the box would cover the screen), and so are
     * boxes covering most of the screen.
     */
    private void projectFrames(float partial, double renderX, double renderY, double renderZ) {
        modelView.clear(); projection.clear(); viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        float screenW = mc.displayWidth, screenH = mc.displayHeight;

        outer:
        for (EntityPlayer player : this.targets) {
            AxisAlignedBB bb = player.getEntityBoundingBox();
            double ox = RenderUtil.lerpDouble(player.posX, player.lastTickPosX, partial) - player.posX - renderX;
            double oy = RenderUtil.lerpDouble(player.posY, player.lastTickPosY, partial) - player.posY - renderY;
            double oz = RenderUtil.lerpDouble(player.posZ, player.lastTickPosZ, partial) - player.posZ - renderZ;
            double minX = bb.minX + ox - 0.1, minY = bb.minY + oy - 0.1, minZ = bb.minZ + oz - 0.1;
            double maxX = bb.maxX + ox + 0.1, maxY = bb.maxY + oy + 0.15, maxZ = bb.maxZ + oz + 0.1;

            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int corner = 0; corner < 8; corner++) {
                screenCoords.clear();
                if (!GLU.gluProject((float) ((corner & 1) == 0 ? minX : maxX), (float) ((corner & 2) == 0 ? minY : maxY),
                        (float) ((corner & 4) == 0 ? minZ : maxZ), modelView, projection, viewport, screenCoords)) continue outer;
                float depth = screenCoords.get(2);
                if (!(depth >= 0.0F && depth < 1.0F)) continue outer;
                float sx = screenCoords.get(0), sy = screenH - screenCoords.get(1);
                x0 = Math.min(x0, sx); y0 = Math.min(y0, sy);
                x1 = Math.max(x1, sx); y1 = Math.max(y1, sy);
            }
            float w = x1 - x0, h = y1 - y0;
            if (w <= 1.0F || h <= 1.0F || w * h > screenW * screenH * 0.6F) continue;
            if (x1 < 0.0F || y1 < 0.0F || x0 > screenW || y0 > screenH) continue;

            if (this.frameCount == this.frames.size()) this.frames.add(new Frame());
            Frame frame = this.frames.get(this.frameCount++);
            frame.x = x0; frame.y = y0; frame.w = w; frame.h = h;
            frame.color = 0xff000000 | this.getEntityColor(player).getRGB();
            frame.health = healthOf(player);
        }
    }

    // ---------------------------------------------------------------- 2D pass

    @EventTarget(Priority.HIGH)
    public void onRender(Render2DEvent event) {
        if (this.frameCount == 0) return;
        int count = this.frameCount;
        this.frameCount = 0;
        if (!this.isEnabled()) return;
        boolean box = this.mode.getValue() == 1, bar = this.healthBar.getValue() == 1, soft = this.halo.getValue();
        if (!box && !bar) return;

        Draw.useGuiScale();
        float toUnits = 1.0F / Draw.scale();
        GlStateManager.disableDepth();
        try {
            for (int i = 0; i < count; i++) {
                Frame frame = this.frames.get(i);
                float x = frame.x * toUnits, y = frame.y * toUnits, w = frame.w * toUnits, h = frame.h * toUnits;
                if (box) drawFrame(x, y, w, h, frame.color, soft);
                if (bar) drawHealth(x, y, w, h, frame.health);
            }
        } catch (RuntimeException e) {
            if (!this.drawFailed) {
                this.drawFailed = true;
                Myau.LOGGER.error("[ESP] 2D drawing failed (logged once)", e);
            }
        } finally {
            GlStateManager.enableDepth();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            Draw.useGuiScale();
        }
    }

    /**
     * The 2D box: a light inside that spills past the edge, a thin outline, and bright
     * brackets on the four corners (the outline again, clipped to each corner). Far-away
     * players, a few pixels tall, only get the outline.
     */
    private static void drawFrame(float x, float y, float w, float h, int color, boolean soft) {
        float small = Math.min(w, h);
        float radius = Math.min(3.5F, small * 0.12F);
        if (soft) Draw.glow(x, y, w, h, radius, Math.min(9.0F, small * 0.45F), Draw.alpha(color, 0.13F));
        Draw.gradientV(x, y, w, h, radius, Draw.alpha(color, 0.02F), Draw.alpha(color, 0.14F));
        if (small < 7.0F) {
            Draw.outline(x, y, w, h, radius, 0.8F, Draw.alpha(color, 0.95F), Draw.alpha(color, 0.75F));
            return;
        }
        Draw.outline(x, y, w, h, radius, 0.6F, Draw.alpha(color, 0.5F), Draw.alpha(color, 0.28F));
        float corner = Math.max(3.0F, Math.min(11.0F, small * 0.24F)), pad = 1.0F;
        int bright = Draw.mix(color, 0xffffffff, 0.18F);
        for (int i = 0; i < 4; i++) {
            float cx = (i & 1) == 0 ? x - pad : x + w - corner, cy = (i & 2) == 0 ? y - pad : y + h - corner;
            Draw.pushClip(cx, cy, corner + pad, corner + pad);
            Draw.outline(x, y, w, h, radius, 1.5F, bright, bright);
            Draw.popClip();
        }
    }

    /** A thin health pill to the left of the box, filled from the bottom. */
    private static void drawHealth(float x, float y, float w, float h, float percent) {
        float width = Math.max(1.4F, Math.min(3.0F, w * 0.07F)), gap = Math.max(1.5F, width);
        float bx = x - gap - width;
        int color = 0xff000000 | ColorUtil.getHealthBlend(percent).getRGB();
        Draw.rect(bx - 0.5F, y - 0.5F, width + 1.0F, h + 1.0F, (width + 1.0F) / 2, 0x90000000);
        float filled = h * percent;
        if (filled > 0.3F) {
            Draw.gradientV(bx, y + h - filled, width, filled, width / 2, Draw.mix(color, 0xffffffff, 0.3F), color);
        }
    }
}
