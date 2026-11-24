package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.GlassScreens;
import myau.ui.glass.UiSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiOptionButton;
import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws plain vanilla buttons and option sliders in the glass style on every vanilla screen.
 * Specialised buttons (language, lock icon, mod buttons) keep their own look.
 */
@SideOnly(Side.CLIENT)
@Mixin(GuiButton.class)
public abstract class MixinGuiButton {
    @Shadow protected int width;
    @Shadow protected int height;
    @Shadow public int xPosition;
    @Shadow public int yPosition;
    @Shadow public String displayString;
    @Shadow public boolean enabled;
    @Shadow public boolean visible;
    @Shadow protected boolean hovered;

    @Shadow protected abstract void mouseDragged(Minecraft mc, int mouseX, int mouseY);

    @Unique private myau.ui.glass.Interaction bettermyau$touch;
    @Unique private long bettermyau$lastFrame;

    @Unique
    private boolean bettermyau$styled() {
        Class<?> type = ((Object) this).getClass();
        return (type == GuiButton.class || type == GuiOptionButton.class || type == GuiOptionSlider.class)
                && HUD.glassScreens();
    }

    @Inject(method = "drawButton", at = @At("HEAD"), cancellable = true)
    private void bettermyau$drawGlass(Minecraft mc, int mouseX, int mouseY, CallbackInfo ci) {
        if (!bettermyau$styled()) return;
        ci.cancel();
        if (!this.visible) return;
        this.hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;

        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - bettermyau$lastFrame) / 1_000_000_000f) * myau.ui.glass.Anim.speed();
        bettermyau$lastFrame = now;
        if (bettermyau$touch == null) bettermyau$touch = new myau.ui.glass.Interaction();
        boolean active = this.hovered && this.enabled;
        bettermyau$touch.update(active, active && org.lwjgl.input.Mouse.isButtonDown(0), dt);
        // A slider is dragged, not pressed: shrinking it would shift the value under the cursor.
        float down = (Object) this instanceof GuiOptionSlider ? 0 : bettermyau$touch.down;

        float slider = -1;
        if ((Object) this instanceof GuiOptionSlider) {
            // The vanilla drag handler also paints the textured knob; keep the logic, drop the paint.
            GL11.glColorMask(false, false, false, false);
            try {
                this.mouseDragged(mc, mouseX, mouseY);
            } finally {
                GL11.glColorMask(true, true, true, true);
            }
            slider = myau.util.MixinBridge.sliderValue(this);
        } else {
            this.mouseDragged(mc, mouseX, mouseY);
        }
        GlassScreens.button(this.xPosition, this.yPosition, this.width, this.height, this.displayString,
                this.enabled, bettermyau$touch.hot, down, slider, mouseX, mouseY);
    }

    @Inject(method = "playPressSound", at = @At("HEAD"), cancellable = true)
    private void bettermyau$pressSound(SoundHandler soundHandler, CallbackInfo ci) {
        if (!bettermyau$styled()) return;
        if (bettermyau$touch != null) bettermyau$touch.pulse();
        HUD hud = myau.Myau.moduleManager == null ? null : (HUD) myau.Myau.moduleManager.modules.get(HUD.class);
        if (hud != null && !hud.uiSounds.getValue()) return; // keep the vanilla click
        UiSound.CLICK.play();
        ci.cancel();
    }
}
