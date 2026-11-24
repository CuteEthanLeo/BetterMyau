package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.ContainerSkin;
import myau.ui.glass.Draw;
import myau.ui.glass.UiSound;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Container screens. The standard ones (inventory, chests, crafting table, furnace, dispenser,
 * hopper, brewing stand) lose their grey texture for a glass panel with a well under every
 * slot, see {@link ContainerSkin}; the rest keep their texture over a glass frame. All of them
 * get the gliding slot highlight, a hovered item that grows a little, and sounds for picking
 * up and placing. Slot positions are untouched, so nothing that depends on the layout changes.
 */
@SideOnly(Side.CLIENT)
@Mixin(GuiContainer.class)
public abstract class MixinGuiContainer extends GuiScreen {
    @Shadow protected int xSize;
    @Shadow protected int ySize;
    @Shadow protected int guiLeft;
    @Shadow protected int guiTop;
    @Shadow private Slot theSlot;

    @Shadow protected abstract void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY);

    /** This frame, the glass skin stands in for the vanilla texture. */
    @Unique private boolean bettermyau$skinned;

    @Inject(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawDefaultBackground()V", shift = At.Shift.AFTER), require = 0)
    private void bettermyau$frame(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        ContainerSkin.recolor = false;
        bettermyau$skinned = HUD.glassScreens() && HUD.glassContainers() && ContainerSkin.handles((GuiContainer) (Object) this);
        if (!HUD.glassScreens() || bettermyau$skinned) return;
        Draw.syncGuiScale();
        // The creative inventory has tabs sticking out above and below: shadow only.
        if ((Object) this instanceof GuiContainerCreative) {
            Draw.shadow(this.guiLeft, this.guiTop + 6, this.xSize, this.ySize, 6, 22, 0x66000000);
            myau.ui.glass.ScreenTransition.focus(this.guiLeft, this.guiTop - 28, this.xSize, this.ySize + 56, 6);
            return;
        }
        float pad = 6;
        GlassTheme.panel(this.guiLeft - pad, this.guiTop - pad, this.xSize + pad * 2, this.ySize + pad * 2, GlassTheme.R_PANEL, 1.3f);
        myau.ui.glass.ScreenTransition.focus(this.guiLeft - pad, this.guiTop - pad, this.xSize + pad * 2, this.ySize + pad * 2, GlassTheme.R_PANEL);
        GlStateManager.color(1, 1, 1, 1);
    }

    /** The grey texture, or the glass skin in its place. */
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerBackgroundLayer(FII)V"), require = 0)
    private void bettermyau$background(GuiContainer screen, float partialTicks, int mouseX, int mouseY) {
        if (bettermyau$skinned) ContainerSkin.draw(screen, this.guiLeft, this.guiTop, this.xSize, this.ySize, mouseX, mouseY);
        else this.drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
    }

    // The slot highlight glides from slot to slot and fades out when the cursor leaves the slots.
    @Unique private final Anim.Spring bettermyau$slotX = Anim.spring(0, 0.2f, 0.82f);
    @Unique private final Anim.Spring bettermyau$slotY = Anim.spring(0, 0.2f, 0.82f);
    @Unique private final Anim.Smooth bettermyau$slotShow = new Anim.Smooth(0, 18);
    @Unique private float bettermyau$slotTargetX, bettermyau$slotTargetY, bettermyau$slotSize = 18;
    @Unique private boolean bettermyau$slotHovered, bettermyau$lateHighlight;

    /** Hovered slot: rounded accent highlight instead of the flat white overlay. */
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGradientRect(IIIIII)V"), require = 0)
    private void bettermyau$slotHighlight(GuiContainer screen, int left, int top, int right, int bottom, int startColor, int endColor) {
        if (!HUD.glassScreens()) {
            this.drawGradientRect(left, top, right, bottom, startColor, endColor);
            return;
        }
        bettermyau$slotTargetX = left - 1;
        bettermyau$slotTargetY = top - 1;
        bettermyau$slotSize = right - left + 2;
        bettermyau$slotHovered = true;
        // Until the late hook below has proven it runs, draw right here like before.
        if (!bettermyau$lateHighlight) bettermyau$paintSlot(bettermyau$slotTargetX, bettermyau$slotTargetY, 1);
    }

    /** After every slot is drawn: the animated highlight, on top of the items like vanilla's. */
    @Inject(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerForegroundLayer(II)V"), require = 0)
    private void bettermyau$animatedSlotHighlight(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        bettermyau$lateHighlight = true;
        boolean hovered = bettermyau$slotHovered;
        bettermyau$slotHovered = false;
        bettermyau$trackHover();
        // Labels on the glass skin would be dark grey on dark glass.
        ContainerSkin.recolor = bettermyau$skinned;
        if (!HUD.glassScreens()) return;
        float dt = Anim.dt();
        if (hovered) {
            if (bettermyau$slotShow.value < 0.05f) {
                bettermyau$slotX.snap(bettermyau$slotTargetX);
                bettermyau$slotY.snap(bettermyau$slotTargetY);
            }
            bettermyau$slotX.update(bettermyau$slotTargetX, dt);
            bettermyau$slotY.update(bettermyau$slotTargetY, dt);
        }
        float show = bettermyau$slotShow.update(hovered, dt);
        if (show <= 0.01f) return;
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        bettermyau$paintSlot(bettermyau$slotX.value, bettermyau$slotY.value, show);
        GlStateManager.enableDepth();
        GlStateManager.color(1, 1, 1, 1);
    }

    @Inject(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerForegroundLayer(II)V", shift = At.Shift.AFTER), require = 0)
    private void bettermyau$afterLabels(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        ContainerSkin.recolor = false;
    }

    @Unique
    private void bettermyau$paintSlot(float x, float y, float show) {
        Draw.syncGuiScale();
        int accent = GlassTheme.accent();
        float size = bettermyau$slotSize;
        Draw.pushAlpha(show);
        Draw.rect(x, y, size, size, bettermyau$skinned ? 4 : 3, Draw.alpha(0xffffff, 0.22f));
        Draw.outline(x, y, size, size, bettermyau$skinned ? 4 : 3, 0.75f, Draw.alpha(accent, 0.9f), Draw.alpha(accent, 0.45f));
        Draw.popAlpha();
    }

    // ── The item under the cursor grows a little; the one just left shrinks back.
    @Unique private Slot bettermyau$grownSlot, bettermyau$shrinkingSlot;
    @Unique private final Anim.Smooth bettermyau$grow = new Anim.Smooth(0, 20), bettermyau$shrink = new Anim.Smooth(0, 20);
    @Unique private boolean bettermyau$slotScaled;

    /** Once per frame, after the slots were drawn: {@code theSlot} is this frame's hovered slot. */
    @Unique
    private void bettermyau$trackHover() {
        float dt = Anim.dt();
        Slot hovered = this.theSlot != null && this.theSlot.getHasStack() ? this.theSlot : null;
        if (hovered != bettermyau$grownSlot) {
            bettermyau$shrinkingSlot = bettermyau$grownSlot;
            bettermyau$shrink.snap(bettermyau$grow.value);
            bettermyau$grownSlot = hovered;
            bettermyau$grow.snap(0);
        }
        bettermyau$grow.update(bettermyau$grownSlot != null, dt);
        if (bettermyau$shrink.update(0, dt) <= 0.01f) bettermyau$shrinkingSlot = null;
    }

    @Inject(method = "drawSlot", at = @At("HEAD"), require = 0)
    private void bettermyau$growSlot(Slot slot, CallbackInfo ci) {
        bettermyau$slotScaled = false;
        if (slot == null || !HUD.glassScreens()) return;
        float amount = slot == bettermyau$grownSlot ? bettermyau$grow.value : slot == bettermyau$shrinkingSlot ? bettermyau$shrink.value : 0;
        if (amount <= 0.01f) return;
        float cx = slot.xDisplayPosition + 8, cy = slot.yDisplayPosition + 8, scale = 1 + 0.12f * amount;
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0);
        GlStateManager.scale(scale, scale, 1);
        GlStateManager.translate(-cx, -cy, 0);
        bettermyau$slotScaled = true;
    }

    @Inject(method = "drawSlot", at = @At("RETURN"), require = 0)
    private void bettermyau$growSlotEnd(Slot slot, CallbackInfo ci) {
        if (!bettermyau$slotScaled) return;
        bettermyau$slotScaled = false;
        GlStateManager.popMatrix();
    }

    // ── Sounds for the player's own clicks. ChestStealer and InvManager click through the
    //    player controller directly, so automated moves stay silent.
    @Unique private boolean bettermyau$heldBefore, bettermyau$slotFilledBefore;
    @Unique private int bettermyau$heldCountBefore;

    @Inject(method = "handleMouseClick", at = @At("HEAD"), require = 0)
    private void bettermyau$beforeClick(Slot slot, int slotId, int button, int type, CallbackInfo ci) {
        ItemStack held = this.mc.thePlayer == null ? null : this.mc.thePlayer.inventory.getItemStack();
        bettermyau$heldBefore = held != null;
        bettermyau$heldCountBefore = held == null ? 0 : held.stackSize;
        bettermyau$slotFilledBefore = slot != null && slot.getHasStack();
    }

    @Inject(method = "handleMouseClick", at = @At("RETURN"), require = 0)
    private void bettermyau$afterClick(Slot slot, int slotId, int button, int type, CallbackInfo ci) {
        if (this.mc.thePlayer == null) return;
        ItemStack held = this.mc.thePlayer.inventory.getItemStack();
        boolean has = held != null;
        UiSound sound = null;
        if (type == 4) {
            if (bettermyau$slotFilledBefore) sound = UiSound.DROP;
        } else if (type == 1) {
            if (bettermyau$slotFilledBefore) sound = UiSound.QUICK_MOVE;
        } else if (type == 2) {
            if (bettermyau$slotFilledBefore || (slot != null && slot.getHasStack())) sound = UiSound.PLACE;
        } else if (slotId == -999) {
            if (bettermyau$heldBefore && (!has || held.stackSize < bettermyau$heldCountBefore)) sound = UiSound.DROP;
        } else if (!bettermyau$heldBefore && has) {
            sound = UiSound.PICK;
        } else if (bettermyau$heldBefore && (!has || held.stackSize < bettermyau$heldCountBefore || bettermyau$slotFilledBefore)) {
            sound = UiSound.PLACE;
        }
        if (sound != null) sound.playInventory();
    }
}
