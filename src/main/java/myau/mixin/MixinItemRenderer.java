package myau.mixin;

import myau.module.modules.Animations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks for the Animations module in {@code renderItemInFirstPerson(F)V}.
 * <p>
 * That method calls {@code transformFirstPersonItem(FF)V} five times, in this order:
 * 0 = NONE, 1 = EAT/DRINK, 2 = BLOCK, 3 = BOW, 4 = item not in use. Only the BLOCK one is
 * followed by {@code doBlockTransformations()V}, which is called exactly once.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = {ItemRenderer.class}, priority = 9999)
public abstract class MixinItemRenderer {
    @Shadow
    @Final
    private Minecraft mc;

    @Unique
    private float bettermyau$partialTicks;
    /** Set while the Animations module has drawn the block pose, so the vanilla one is skipped. */
    @Unique
    private boolean bettermyau$blockReplaced;

    @Shadow
    protected abstract void transformFirstPersonItem(float equipProgress, float swingProgress);

    @Shadow
    protected abstract void doBlockTransformations();

    @Shadow
    protected abstract void doItemUsedTransformations(float swingProgress);

    @Inject(
            method = {"renderItemInFirstPerson"},
            at = {@At("HEAD")}
    )
    private void bettermyau$storePartialTicks(float partialTicks, CallbackInfo callbackInfo) {
        this.bettermyau$partialTicks = partialTicks;
        this.bettermyau$blockReplaced = false;
    }

    /** Offset / rotation of the whole hand, right after the method's only pushMatrix(). */
    @Inject(
            method = {"renderItemInFirstPerson"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;pushMatrix()V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )}
    )
    private void bettermyau$handTransform(float partialTicks, CallbackInfo callbackInfo) {
        Animations animations = Animations.instance();
        if (animations != null && animations.isEnabled()) {
            animations.applyHand();
        }
    }

    /** BLOCK branch, first half: the third transformFirstPersonItem call of the method. */
    @Redirect(
            method = {"renderItemInFirstPerson"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemRenderer;transformFirstPersonItem(FF)V",
                    ordinal = 2
            )
    )
    private void bettermyau$blockTransform(ItemRenderer itemRenderer, float equipProgress, float swingProgress) {
        Animations animations = Animations.instance();
        if (animations != null && animations.isEnabled() && this.mc.thePlayer != null) {
            this.bettermyau$blockReplaced = true;
            animations.applyBlock(equipProgress, this.mc.thePlayer.getSwingProgress(this.bettermyau$partialTicks));
        } else {
            this.bettermyau$blockReplaced = false;
            this.transformFirstPersonItem(equipProgress, swingProgress);
        }
    }

    /** BLOCK branch, second half: already part of the animation when the module drew the pose. */
    @Redirect(
            method = {"renderItemInFirstPerson"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemRenderer;doBlockTransformations()V"
            )
    )
    private void bettermyau$blockTransformTail(ItemRenderer itemRenderer) {
        if (this.bettermyau$blockReplaced) {
            this.bettermyau$blockReplaced = false;
            return;
        }
        this.doBlockTransformations();
    }

    /** odd-swing: the item is not moved by the swing, it only rotates. */
    @Redirect(
            method = {"renderItemInFirstPerson"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemRenderer;doItemUsedTransformations(F)V"
            )
    )
    private void bettermyau$swingOffset(ItemRenderer itemRenderer, float swingProgress) {
        Animations animations = Animations.instance();
        if (animations != null && animations.isEnabled() && animations.skipSwingOffset()) {
            return;
        }
        this.doItemUsedTransformations(swingProgress);
    }
}
