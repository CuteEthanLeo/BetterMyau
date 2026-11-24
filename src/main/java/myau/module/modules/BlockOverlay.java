package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.FxRenderUtil;
import myau.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.opengl.GL11;

/**
 * Tints the block under the crosshair and can show its name and numeric id next to the
 * crosshair.
 */
public class BlockOverlay extends Module {
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BOX", "OTHER_BOX", "OUTLINE"});
    /** Draws over everything instead of being hidden by blocks in front. */
    public final BooleanProperty depth3D = new BooleanProperty("depth-3d", false);
    public final FloatProperty thickness = new FloatProperty("thickness", 2.0F, 1.0F, 5.0F);
    public final BooleanProperty info = new BooleanProperty("info", false);
    public final ColorProperty color = new ColorProperty("color", 0x4475FF);
    public final IntProperty alpha = new IntProperty("alpha", 100, 0, 255);

    public BlockOverlay() {
        super("BlockOverlay", false);
    }

    /** The block the crosshair is on, or null for air, liquids and anything outside the world border. */
    public BlockPos getCurrentBlock() {
        if (mc.theWorld == null || mc.objectMouseOver == null) return null;
        if (mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
        BlockPos pos = mc.objectMouseOver.getBlockPos();
        if (pos == null) return null;
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block.getMaterial() == Material.air || block == Blocks.water || block == Blocks.lava) return null;
        return mc.theWorld.getWorldBorder().contains(pos) ? pos : null;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        BlockPos pos = this.getCurrentBlock();
        if (pos == null) return;

        Block block = mc.theWorld.getBlockState(pos).getBlock();
        block.setBlockBoundsBasedOnState(mc.theWorld, pos);
        AxisAlignedBB box = block.getSelectedBoundingBox(mc.theWorld, pos)
                .expand(0.002, 0.002, 0.002)
                .offset(-FxRenderUtil.renderX(), -FxRenderUtil.renderY(), -FxRenderUtil.renderZ());

        boolean through = this.depth3D.getValue();
        int style = this.mode.getValue();

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        if (through) GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glLineWidth(this.thickness.getValue());
        FxRenderUtil.color(this.color.getValue(), this.alpha.getValue());

        if (style == 0 || style == 1) FxRenderUtil.filledBox(box);
        if (style == 0 || style == 2) FxRenderUtil.outlinedBox(box);

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(2.0F);
        GlStateManager.depthMask(true);
        if (through) GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.resetColor();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !this.info.getValue() || mc.thePlayer == null || mc.theWorld == null) return;
        BlockPos pos = this.getCurrentBlock();
        if (pos == null) return;

        Block block = mc.theWorld.getBlockState(pos).getBlock();
        String text = block.getLocalizedName() + " §7ID: " + Block.getIdFromBlock(block);
        ScaledResolution resolution = new ScaledResolution(mc);
        float x = resolution.getScaledWidth() / 2, y = resolution.getScaledHeight() / 2;

        RenderUtil.enableRenderState();
        FxRenderUtil.borderedRect(
                x - 2.0F, y + 5.0F, x + mc.fontRendererObj.getStringWidth(text) + 2.0F, y + 16.0F, 3.0F, 0xFF000000, 0xFF000000
        );
        RenderUtil.disableRenderState();
        mc.fontRendererObj.drawString(text, x, y + 7.0F, 0xFFFFFFFF, false);
        GlStateManager.resetColor();
    }
}
