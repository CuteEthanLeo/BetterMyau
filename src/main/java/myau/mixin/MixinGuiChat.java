package myau.mixin;

import myau.ui.glass.Island;
import myau.via.ViaHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While chat is open the mouse is free: clicks and the wheel over a Dynamic Island satellite (the
 * music capsule) go to it instead of the chat.
 */
@SideOnly(Side.CLIENT)
@Mixin({GuiChat.class})
public abstract class MixinGuiChat {
    @Shadow protected GuiTextField inputField;

    /** Cross-version: 1.11+ servers take 256 characters per message. */
    @Inject(method = "initGui", at = @At("RETURN"))
    private void bettermyau$viaChatLimit(CallbackInfo ci) {
        if (this.inputField != null) this.inputField.setMaxStringLength(ViaHooks.chatLimit(100));
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void bettermyau$islandClick(int mouseX, int mouseY, int mouseButton, CallbackInfo ci) {
        if (Island.click(mouseX, mouseY, mouseButton)) ci.cancel();
    }

    @Inject(method = "handleMouseInput", at = @At("HEAD"), cancellable = true)
    private void bettermyau$islandWheel(CallbackInfo ci) {
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen self = (GuiScreen) (Object) this;
        if (mc.displayWidth <= 0 || mc.displayHeight <= 0) return;
        float x = Mouse.getEventX() * self.width / (float) mc.displayWidth;
        float y = self.height - Mouse.getEventY() * self.height / (float) mc.displayHeight - 1;
        if (Island.scroll(x, y, wheel)) ci.cancel();
    }
}
