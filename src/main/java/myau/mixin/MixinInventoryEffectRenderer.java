package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.glass.ContainerSkin;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraft.inventory.Container;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The list of active potion effects beside the inventory, as glass cards. */
@SideOnly(Side.CLIENT)
@Mixin(InventoryEffectRenderer.class)
public abstract class MixinInventoryEffectRenderer extends GuiContainer {
    public MixinInventoryEffectRenderer(Container container) {
        super(container);
    }

    @Inject(method = "drawActivePotionEffects", at = @At("HEAD"), cancellable = true, require = 0)
    private void bettermyau$glassEffects(CallbackInfo ci) {
        if (!HUD.glassScreens() || !HUD.glassContainers()) return;
        ContainerSkin.effects(this.guiLeft, this.guiTop);
        ci.cancel();
    }
}
