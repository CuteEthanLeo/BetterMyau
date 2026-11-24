package myau.mixin;

import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import myau.ui.glass.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.renderer.Tessellator;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla scrolling lists (servers, worlds, languages, resource packs): the dirt background
 * and dirt header/footer strips are dropped in favour of the blurred screen behind them, and
 * the selection box becomes an accent-coloured glass row.
 */
@SideOnly(Side.CLIENT)
@Mixin(GuiSlot.class)
public abstract class MixinGuiSlot {
    @Shadow @Final protected Minecraft mc;
    @Shadow public int width;
    @Shadow public int top;
    @Shadow public int bottom;
    @Shadow public int right;
    @Shadow public int left;
    @Shadow @Final public int slotHeight;
    @Shadow protected boolean showSelectionBox;
    @Shadow public int headerPadding;

    @Shadow protected abstract int getSize();
    @Shadow protected abstract boolean isSelected(int index);
    @Shadow public abstract int getListWidth();

    @Unique private boolean bettermyau$clipping;

    /** The list area: a slightly recessed sheet instead of the dark dirt texture. */
    @Inject(method = "drawContainerBackground", at = @At("HEAD"), cancellable = true, remap = false)
    private void bettermyau$listBackground(Tessellator tessellator, CallbackInfo ci) {
        if (!HUD.glassScreens()) return;
        Draw.syncGuiScale();
        Draw.rect(this.left, this.top, this.right - this.left, this.bottom - this.top, 0, 0x34000000);
        Draw.rect(this.left, this.top, this.right - this.left, 0.5f, 0, 0x30ffffff);
        Draw.rect(this.left, this.bottom - 0.5f, this.right - this.left, 0.5f, 0, 0x18ffffff);
        // Entries are no longer hidden by the dirt strips, so clip them to the list area.
        // Through Draw's clip stack, so the clip follows the screen while it scales in.
        Draw.pushClip(this.left, this.top, this.right - this.left, this.bottom - this.top);
        bettermyau$clipping = true;
        ci.cancel();
    }

    /** Header and footer strips: only end the clipping; the blurred backdrop stays visible. */
    @Inject(method = "overlayBackground", at = @At("HEAD"), cancellable = true)
    private void bettermyau$noDirtStrips(int startY, int endY, int startAlpha, int endAlpha, CallbackInfo ci) {
        bettermyau$endClipping();
        if (HUD.glassScreens()) ci.cancel();
    }

    /** Safety net: never leave the scissor test on after the list was drawn. */
    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void bettermyau$endClip(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        bettermyau$endClipping();
    }

    @Unique
    private void bettermyau$endClipping() {
        if (!bettermyau$clipping) return;
        bettermyau$clipping = false;
        Draw.popClip();
        // An entry may have reset Draw's state while drawing; never leave the scissor on.
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ── Smooth wheel scrolling: the wheel moves a target, the list eases towards it. Dragging
    //    the scroll bar and the arrow buttons still set amountScrolled directly.
    @Shadow protected float amountScrolled;
    @Shadow protected abstract int getContentHeight();

    @Unique private float bettermyau$scrollTarget, bettermyau$scrollSet;
    @Unique private boolean bettermyau$scrolling;

    @Redirect(method = "handleMouseInput", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/input/Mouse;getEventDWheel()I", remap = false), require = 0)
    private int bettermyau$smoothWheel() {
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0 || !HUD.glassScreens() || myau.ui.glass.Anim.reduced()) return wheel;
        if (!bettermyau$scrolling) bettermyau$scrollTarget = this.amountScrolled;
        bettermyau$scrollTarget += (wheel > 0 ? -1 : 1) * this.slotHeight * 0.75f;
        bettermyau$scrolling = true;
        bettermyau$scrollSet = this.amountScrolled;
        myau.ui.glass.UiSound.SCROLL_TICK.play();
        return 0;
    }

    @Inject(method = "drawScreen", at = @At("HEAD"))
    private void bettermyau$easeScroll(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!bettermyau$scrolling) return;
        // Something else moved the list (drag, buttons, a resize): that position wins.
        if (this.amountScrolled != bettermyau$scrollSet) {
            bettermyau$scrolling = false;
            return;
        }
        float max = Math.max(0, this.getContentHeight() - (this.bottom - this.top - 4));
        bettermyau$scrollTarget = Math.max(0, Math.min(max, bettermyau$scrollTarget));
        float next = this.amountScrolled + (bettermyau$scrollTarget - this.amountScrolled)
                * (1 - (float) Math.exp(-16 * myau.ui.glass.Anim.dt()));
        if (Math.abs(bettermyau$scrollTarget - next) < 0.4f) {
            next = bettermyau$scrollTarget;
            bettermyau$scrolling = false;
        }
        this.amountScrolled = next;
        bettermyau$scrollSet = next;
    }

    @Unique private final myau.ui.glass.Anim.Smooth bettermyau$selectAt = new myau.ui.glass.Anim.Smooth(-1, 20);
    @Unique private final myau.ui.glass.Anim.Smooth bettermyau$hoverAt = new myau.ui.glass.Anim.Smooth(0, 24);
    @Unique private final myau.ui.glass.Anim.Smooth bettermyau$hoverShow = new myau.ui.glass.Anim.Smooth(0, 18);
    @Unique private int bettermyau$lastSelected = -2;

    /** Glass selection and hover rows, drawn under the entries; both glide between rows. */
    @Inject(method = "drawSelectionBox", at = @At("HEAD"))
    private void bettermyau$glassSelection(int insideLeft, int insideTop, int mouseX, int mouseY, CallbackInfo ci) {
        if (!HUD.glassScreens()) return;
        Draw.syncGuiScale();
        float dt = myau.ui.glass.Anim.dt();
        int size = this.getSize(), listWidth = this.getListWidth();
        float x = this.left + (this.width / 2f - listWidth / 2f), rowH = this.slotHeight - 4;
        int accent = GlassTheme.accent();
        int selected = -1, hovered = -1;
        for (int i = 0; i < size; i++) {
            if (selected < 0 && this.showSelectionBox && this.isSelected(i)) selected = i;
            float y = insideTop + i * this.slotHeight + this.headerPadding;
            if (mouseX >= x && mouseX < x + listWidth && mouseY >= y - 2 && mouseY < y + rowH + 2
                    && mouseY >= this.top && mouseY <= this.bottom) hovered = i;
        }
        if (hovered == selected) hovered = -1;
        // A row chosen while the list is on screen (not the selection it opened with).
        if (selected != bettermyau$lastSelected) {
            if (bettermyau$lastSelected != -2 && selected >= 0) myau.ui.glass.UiSound.SELECT.play();
            bettermyau$lastSelected = selected;
        }

        if (hovered >= 0) {
            if (bettermyau$hoverShow.value < 0.05f) bettermyau$hoverAt.snap(hovered);
            bettermyau$hoverAt.update(hovered, dt);
        }
        float glow = bettermyau$hoverShow.update(hovered >= 0, dt);
        if (glow > 0.01f) {
            float y = insideTop + bettermyau$hoverAt.value * this.slotHeight + this.headerPadding;
            if (y <= this.bottom && y + rowH >= this.top) {
                Draw.pushAlpha(glow);
                GlassTheme.card(x, y - 2, listWidth, rowH + 4, GlassTheme.R_CONTROL, 0.4f);
                Draw.popAlpha();
            }
        }

        if (selected >= 0) {
            if (bettermyau$selectAt.value < 0) bettermyau$selectAt.snap(selected);
            float y = insideTop + bettermyau$selectAt.update(selected, dt) * this.slotHeight + this.headerPadding;
            if (y <= this.bottom && y + rowH >= this.top) {
                Draw.glow(x, y - 2, listWidth, rowH + 4, GlassTheme.R_CONTROL, 7, Draw.alpha(accent, 0.22f));
                Draw.rect(x, y - 2, listWidth, rowH + 4, GlassTheme.R_CONTROL, Draw.alpha(accent, 0.18f));
                Draw.outline(x, y - 2, listWidth, rowH + 4, GlassTheme.R_CONTROL, 0.75f, Draw.alpha(accent, 0.85f), Draw.alpha(accent, 0.4f));
            }
        } else {
            bettermyau$selectAt.snap(-1);
        }
        net.minecraft.client.renderer.GlStateManager.enableTexture2D();
    }

    /** The vanilla grey/black selection frame is replaced by the glass row above. */
    @Redirect(method = "drawSelectionBox", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/GuiSlot;showSelectionBox:Z"), require = 0)
    private boolean bettermyau$hideVanillaSelection(GuiSlot slot) {
        return this.showSelectionBox && !HUD.glassScreens();
    }
}
