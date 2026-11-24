/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Ported from Atlas v3.7.4 by Marco-hacker666:
 * https://github.com/Marco-hacker666/Atlas_Client/blob/v3.7.4/src/main/java/atlas/module/modules/TNTTimer.java
 * Atlas credits OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid)
 * and the raven-bS TNTTimer concept. Adapted for BetterMyau's events and rendering.
 */
package myau.module.modules;

import myau.event.EventTarget;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.ui.glass.GlState;
import myau.util.FxRenderUtil;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.List;

/** Shows the remaining fuse time above primed TNT. */
public final class TNTTimer extends Module {
    private static final float FULL_FUSE = 80.0F;

    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 3.0F);
    public final FloatProperty radius = new FloatProperty("radius", 64.0F, 8.0F, 128.0F);
    public final BooleanProperty shadow = new BooleanProperty("shadow", true);
    public final BooleanProperty background = new BooleanProperty("background", true);

    private int tracked;

    public TNTTimer() {
        super("TNTTimer", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{Integer.toString(this.tracked)};
    }

    @Override
    public void onDisabled() {
        this.tracked = 0;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.tracked = 0;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        this.tracked = 0;
        double range = this.radius.getValue();
        double rangeSq = range * range;
        AxisAlignedBB bounds = mc.thePlayer.getEntityBoundingBox().expand(range, range, range);
        List<EntityTNTPrimed> nearby = mc.theWorld.getEntitiesWithinAABB(EntityTNTPrimed.class, bounds);
        if (nearby.isEmpty()) return;

        float partialTicks = event.getPartialTicks();
        double cameraX = FxRenderUtil.renderX();
        double cameraY = FxRenderUtil.renderY();
        double cameraZ = FxRenderUtil.renderZ();
        float textScale = 0.02666667F * this.scale.getValue();
        boolean drawShadow = this.shadow.getValue();
        boolean drawBackground = this.background.getValue();
        FontRenderer font = mc.fontRendererObj;

        int state = GlState.capture();
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int blendSrc = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int blendDst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try {
            GlStateManager.disableLighting();
            GlStateManager.depthMask(false);
            GlStateManager.disableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

            for (EntityTNTPrimed tnt : nearby) {
                if (tnt.isDead || tnt.fuse < 1 || mc.thePlayer.getDistanceSqToEntity(tnt) > rangeSq) continue;

                double x = tnt.lastTickPosX + (tnt.posX - tnt.lastTickPosX) * partialTicks - cameraX;
                double y = tnt.lastTickPosY + (tnt.posY - tnt.lastTickPosY) * partialTicks - cameraY + tnt.height + 0.6;
                double z = tnt.lastTickPosZ + (tnt.posZ - tnt.lastTickPosZ) * partialTicks - cameraZ;

                // At 20 ticks per second each tenth of a second takes two ticks.
                int tenths = Math.round(Math.max(1.0F, tnt.fuse - partialTicks) / 2.0F);
                String text = (tenths / 10) + "." + (tenths % 10);
                int green = Math.round(255.0F * Math.min(1.0F, tnt.fuse / FULL_FUSE));
                int color = 0xFF000000 | ((255 - green) << 16) | (green << 8);
                this.renderLabel(font, x, y, z, textScale, text, color, drawShadow, drawBackground);
                this.tracked++;
            }
        } finally {
            GlStateManager.bindTexture(texture);
            GlStateManager.depthMask(depthWrite);
            GlStateManager.tryBlendFuncSeparate(blendSrc, blendDst, blendSrcAlpha, blendDstAlpha);
            GlState.restore(state);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private void renderLabel(FontRenderer font, double x, double y, double z, float size,
                             String text, int color, boolean drawShadow, boolean drawBackground) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, z);
            FxRenderUtil.billboard();
            GlStateManager.scale(-size, -size, size);

            float halfWidth = font.getStringWidth(text) * 0.5F;
            if (drawBackground) {
                GlStateManager.disableTexture2D();
                Tessellator tessellator = Tessellator.getInstance();
                WorldRenderer buffer = tessellator.getWorldRenderer();
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
                buffer.pos(-halfWidth - 1, -1, 0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
                buffer.pos(-halfWidth - 1, 8, 0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
                buffer.pos(halfWidth + 1, 8, 0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
                buffer.pos(halfWidth + 1, -1, 0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
                tessellator.draw();
                GlStateManager.enableTexture2D();
            }
            font.drawString(text, -halfWidth, 0.0F, color, drawShadow);
        } finally {
            GlStateManager.popMatrix();
        }
    }
}
