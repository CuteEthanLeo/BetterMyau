package myau.mixin;

import myau.module.modules.ItemPhysics;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * ItemPhysics: while the module is on, {@code doRender} is cancelled at its head and the item is
 * drawn here. The body follows the vanilla (Forge 11.15.1) method step by step; only the placement
 * that {@code func_177077_a} does (hover, bob, spin) and the offsets of stacked copies differ.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = {RenderEntityItem.class}, priority = 9999)
public abstract class MixinRenderEntityItem extends Render<EntityItem> {
    /** Half the thickness of a flat item model on the ground (1 pixel at half scale, plus a hair). */
    @Unique
    private static final float BETTERMYAU_FLAT_LIFT = 0.0166F;
    /** Half the size of a block model on the ground. */
    @Unique
    private static final float BETTERMYAU_BLOCK_LIFT = 0.125F;
    /** Distance between the copies of a stack of flat items lying on each other. */
    @Unique
    private static final float BETTERMYAU_FLAT_STEP = 0.032F;

    @Shadow
    @Final
    private RenderItem itemRenderer;

    /** Created on first use: the offsets of stacked copies, reseeded every frame like vanilla's. */
    @Unique
    private Random bettermyau$random;

    protected MixinRenderEntityItem(RenderManager renderManager) {
        super(renderManager);
    }

    @Inject(
            method = {"doRender(Lnet/minecraft/entity/item/EntityItem;DDDFF)V"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void bettermyau$itemPhysics(EntityItem entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo callbackInfo) {
        ItemPhysics physics = ItemPhysics.instance();
        if (physics == null || !physics.isEnabled()) {
            return;
        }
        ItemStack stack = entity.getEntityItem();
        if (stack == null || stack.getItem() == null) {
            return;
        }
        callbackInfo.cancel();

        if (this.bettermyau$random == null) {
            this.bettermyau$random = new Random();
        }
        Random random = this.bettermyau$random;
        random.setSeed(187L);
        boolean mipmap = false;
        if (this.bindEntityTexture(entity)) {
            this.renderManager.renderEngine.getTexture(this.getEntityTexture(entity)).setBlurMipmap(false, false);
            mipmap = true;
        }
        GlStateManager.enableRescaleNormal();
        GlStateManager.alphaFunc(516, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.pushMatrix();

        IBakedModel model = this.itemRenderer.getItemModelMesher().getItemModel(stack);
        boolean block = model.isGui3d();
        boolean resting = entity.onGround;
        int copies = bettermyau$copies(stack);

        // No hover and no bob: the model sits on the entity's feet.
        GlStateManager.translate((float) x, (float) y + (block ? BETTERMYAU_BLOCK_LIFT : BETTERMYAU_FLAT_LIFT), (float) z);
        if (resting) {
            // Face up; after this the model's z axis points down and its yaw turns it on the ground.
            GlStateManager.rotate(90.0F, 1.0F, 0.0F, 0.0F);
            if (physics.realistic.getValue()) {
                GlStateManager.rotate(entity.rotationYaw, 0.0F, 1.0F, 0.6F);
            } else {
                GlStateManager.rotate(entity.rotationYaw, 0.0F, 0.0F, 1.0F);
            }
        } else {
            float age = (float) entity.getAge() + partialTicks;
            float spin = (age / 20.0F + entity.hoverStart) * (180.0F / (float) Math.PI);
            spin *= physics.rotationSpeed.getValue() * (1.0F + Math.min(age / 360.0F, 1.0F));
            float weight = physics.weight.getValue();
            GlStateManager.rotate(spin * 7.0F, weight, weight, 1.35F);
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        boolean spread = ((RenderEntityItem) (Object) this).shouldSpreadItems();
        for (int i = 0; i < copies; i++) {
            GlStateManager.pushMatrix();
            if (i > 0) {
                float offsetX = (random.nextFloat() * 2.0F - 1.0F) * 0.15F;
                float offsetY = (random.nextFloat() * 2.0F - 1.0F) * 0.15F;
                float offsetZ = (random.nextFloat() * 2.0F - 1.0F) * 0.15F;
                if (resting) {
                    // Nothing may end up under the ground: flat items pile up, blocks only shift upwards.
                    offsetZ = block ? -Math.abs(offsetZ) : -i * BETTERMYAU_FLAT_STEP;
                }
                GlStateManager.translate(spread ? offsetX : 0.0F, spread ? offsetY : 0.0F, offsetZ);
            }
            if (model.isGui3d()) {
                GlStateManager.scale(0.5F, 0.5F, 0.5F);
            }
            model = ForgeHooksClient.handleCameraTransforms(model, ItemCameraTransforms.TransformType.GROUND);
            this.itemRenderer.renderItem(stack, model);
            GlStateManager.popMatrix();
        }

        GlStateManager.popMatrix();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        this.bindEntityTexture(entity);
        if (mipmap) {
            this.renderManager.renderEngine.getTexture(this.getEntityTexture(entity)).restoreLastBlurMipmap();
        }
        // What Render.doRender, the super call of the cancelled method, does.
        this.renderName(entity, x, y, z);
    }

    /** Same thresholds as vanilla {@code func_177078_a}. */
    @Unique
    private static int bettermyau$copies(ItemStack stack) {
        if (stack.stackSize > 48) {
            return 5;
        } else if (stack.stackSize > 32) {
            return 4;
        } else if (stack.stackSize > 16) {
            return 3;
        } else {
            return stack.stackSize > 1 ? 2 : 1;
        }
    }
}
