package myau.mixin;

import myau.Myau;
import myau.event.EventManager;
import myau.events.Render2DEvent;
import myau.module.modules.NickHider;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = {GuiIngameForge.class}, priority = 9999)
public abstract class MixinGuiIngameForge {
    @Inject(
            method = {"renderGameOverlay"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/client/GuiIngameForge;renderTitle(IIF)V",
                    shift = At.Shift.AFTER,
                    remap = false
            )}
    )
    private void renderGameOverlay(float float1, CallbackInfo callbackInfo) {
        myau.ui.glass.GlassBlur.invalidate();
        // Whatever ran before (vanilla, other mods, the 3D pass) may have left GL out of step
        // with GlStateManager's cache; resync so glass text and shadows blend correctly.
        myau.ui.glass.GlState.hudDefaults();
        EventManager.call(new Render2DEvent(float1));
        myau.ui.glass.GlState.hudDefaults();
    }

    /** The drawn crosshair replaces the vanilla texture, in the same layer. */
    @Inject(method = "renderCrosshairs", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void bettermyau$crosshair(int width, int height, CallbackInfo ci) {
        myau.module.modules.Crosshair crosshair = myau.module.modules.Crosshair.instance();
        if (crosshair != null && crosshair.isEnabled() && crosshair.render()) ci.cancel();
    }

    // ── The glass dock (Hotbar module) draws these itself in the 2D pass.

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void bettermyau$hotbar(net.minecraft.client.gui.ScaledResolution res, float partialTicks, CallbackInfo ci) {
        if (myau.module.modules.Hotbar.replacesHotbar()) ci.cancel();
    }

    @Inject(method = "renderToolHightlight", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void bettermyau$heldItemName(net.minecraft.client.gui.ScaledResolution res, CallbackInfo ci) {
        if (myau.module.modules.Hotbar.replacesHotbar()) ci.cancel();
    }

    @Inject(method = {"renderHealth", "renderArmor", "renderFood", "renderAir", "renderHealthMount", "renderExperience", "renderJumpBar"},
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void bettermyau$status(int width, int height, CallbackInfo ci) {
        if (!myau.module.modules.Hotbar.replacesStatus()) return;
        // Whatever other mods stack above the vanilla bars should clear the dock instead.
        int reserved = myau.module.modules.Hotbar.reservedHeight();
        if (GuiIngameForge.left_height < reserved) GuiIngameForge.left_height = reserved;
        if (GuiIngameForge.right_height < reserved) GuiIngameForge.right_height = reserved;
        ci.cancel();
    }

    @Redirect(
            method = {"renderExperience"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;experience:F"
            )
    )
    private float renderExperience(EntityPlayerSP entityPlayerSP) {
        if (Myau.moduleManager == null) {
            return entityPlayerSP.experience;
        } else {
            NickHider event = (NickHider) Myau.moduleManager.modules.get(NickHider.class);
            return event.isEnabled() && event.level.getValue() ? 0.0F : entityPlayerSP.experience;
        }
    }

    @Redirect(
            method = {"renderExperience"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;experienceLevel:I"
            )
    )
    private int renderExperienceLevel(EntityPlayerSP entityPlayerSP) {
        if (Myau.moduleManager == null) {
            return entityPlayerSP.experienceLevel;
        } else {
            NickHider event = (NickHider) Myau.moduleManager.modules.get(NickHider.class);
            return event.isEnabled() && event.level.getValue() ? 0 : entityPlayerSP.experienceLevel;
        }
    }
}
