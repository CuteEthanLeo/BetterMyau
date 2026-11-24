package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.glass.Draw;
import net.minecraft.client.renderer.Tessellator;
import net.minecraftforge.fml.client.GuiScrollingList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Forge's scrolling lists (the mod list) draw a dirt background outside a world; in glass that
 * becomes a faint dark sheet over the menu scene. The draw call still runs with colour writes off,
 * so the list's own state handling is untouched.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = GuiScrollingList.class, remap = false)
public abstract class MixinGuiScrollingList {
    @Shadow @Final protected int top;
    @Shadow @Final protected int bottom;
    @Shadow @Final protected int left;
    @Shadow @Final protected int right;

    @Redirect(method = "drawScreen(IIF)V", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V", ordinal = 0, remap = true))
    private void bettermyau$noDirt(Tessellator tessellator) {
        if (!HUD.glassScreens()) {
            tessellator.draw();
            return;
        }
        GL11.glColorMask(false, false, false, false);
        try {
            tessellator.draw();
        } finally {
            GL11.glColorMask(true, true, true, true);
        }
        Draw.syncGuiScale();
        Draw.rect(this.left, this.top, this.right - this.left, this.bottom - this.top, 0, 0x30000000);
        Draw.rect(this.left, this.top, this.right - this.left, 0.6f, 0, 0x18ffffff);
    }
}
