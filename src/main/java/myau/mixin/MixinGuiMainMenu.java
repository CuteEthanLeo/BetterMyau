package myau.mixin;

import myau.Myau;
import myau.ui.GlassButton;
import myau.ui.GlassTheme;
import myau.ui.glass.Anim;
import myau.ui.glass.Draw;
import myau.ui.glass.GlassBlur;
import myau.ui.glass.MenuBackdrop;
import myau.ui.glass.SmoothFont;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu extends GuiScreen {
    @Unique
    private long bettermyau$openedAt;
    /** Where the title's line is centred, for the opening to hand the name over to. */
    @Unique
    private float bettermyau$titleCenterY = -1;

    @Inject(method = "initGui", at = @At("HEAD"))
    private void onMainMenuInit(CallbackInfo ci) {
        bettermyau$openedAt = System.nanoTime();
    }

    @Inject(method = "initGui", at = @At("RETURN"))
    private void styleMainMenuButtons(CallbackInfo ci) {
        for (int i = 0; i < buttonList.size(); i++) {
            GuiButton old = buttonList.get(i);
            // Specialized vanilla buttons (language) keep their icon; move them clear of the panel.
            if (old.getClass() != GuiButton.class) {
                if (old.xPosition < this.width / 2 - 100) old.xPosition -= 14;
                continue;
            }
            GuiButton replacement = new GlassButton(old.id, old.xPosition, old.yPosition,
                    old.getButtonWidth(), old.height, old.displayString);
            replacement.enabled = old.enabled;
            replacement.visible = old.visible;
            // Realms (beside Mods) has no use here: that place opens the guide instead.
            if (old.id == BETTERMYAU$REALMS) {
                replacement.displayString = "使用指南";
                replacement.enabled = true;
            }
            buttonList.set(i, replacement);
        }
    }

    @Unique
    private static final int BETTERMYAU$REALMS = 14;

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void bettermyau$openGuide(GuiButton button, CallbackInfo ci) {
        if (button.id != BETTERMYAU$REALMS) return;
        ci.cancel();
        this.mc.displayGuiScreen(new myau.guide.GuideScreen(this));
    }

    @Inject(method = "drawScreen", at = @At("HEAD"))
    private void bettermyau$background(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        myau.ui.glass.Intro.frame();
        Draw.useGuiScale();
        MenuBackdrop.draw(this.width, this.height);
    }

    /** The one-time opening, over everything the menu drew. */
    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void bettermyau$opening(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        bettermyau$guideDot();
        if (!myau.ui.glass.Intro.running()) return;
        float center = bettermyau$titleCenterY >= 0 ? bettermyau$titleCenterY : this.height * 0.22f;
        myau.ui.glass.Intro.overlay(this.width, this.height, center, 26);
    }

    /** A small dot on the guide button until the guide has been opened once. */
    @Unique
    private void bettermyau$guideDot() {
        if (myau.guide.GuideState.seen()) return;
        for (GuiButton button : buttonList) {
            if (button.id != BETTERMYAU$REALMS || !button.visible) continue;
            float seconds = (System.nanoTime() - bettermyau$openedAt) / 1_000_000_000f;
            float appear = myau.ui.glass.Intro.running() ? 0 : Anim.outCubic((seconds - 0.3f) / 0.4f);
            if (appear <= 0) return;
            Draw.useGuiScale();
            float cx = button.xPosition + button.getButtonWidth() - 7, cy = button.yPosition + 6;
            double pulse = 0.5 + 0.5 * Math.sin(System.nanoTime() / 1e9 * Math.PI * 1.4);
            Draw.pushAlpha(appear);
            Draw.glow(cx - 2.5f, cy - 2.5f, 5, 5, 2.5f, 4 + (float) pulse * 3, Draw.alpha(0xff4d6d, 0.55f));
            Draw.circle(cx, cy, 2.5f, 0xffff4d6d);
            Draw.popAlpha();
            return;
        }
    }

    /** The backdrop is drawn in code and cannot fail to load, so the panorama is never needed. */
    @Inject(method = "renderSkybox", at = @At("HEAD"), cancellable = true)
    private void cancelSkybox(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * Vanilla's white and black fullscreen hazes were made for the panorama; they would grey the backdrop.
     * Their state changes are kept: everything drawn after them relies on texturing having been switched
     * off and on again here (without it the text and the language button come out as solid blocks).
     */
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiMainMenu;drawGradientRect(IIIIII)V"), require = 0)
    private void hideHaze(GuiMainMenu menu, int left, int top, int right, int bottom, int startColor, int endColor) {
        GlStateManager.disableTexture2D();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    /** The vanilla logo is replaced by the BetterMyau title drawn in {@link #drawGlassMenu}. */
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiMainMenu;drawTexturedModalRect(IIIIII)V"), require = 0)
    private void hideLogo(GuiMainMenu menu, int x, int y, int textureX, int textureY, int width, int height) {
    }

    /** The yellow splash text does not fit the glass menu. */
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiMainMenu;drawCenteredString(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V"), require = 0)
    private void hideSplash(GuiMainMenu menu, FontRenderer font, String text, int x, int y, int color) {
    }

    /** Version, Forge branding and copyright lines in the corners use the glass UI font. */
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiMainMenu;drawString(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V"), require = 0)
    private void glassCornerText(GuiMainMenu menu, FontRenderer font, String text, int x, int y, int color) {
        Draw.syncGuiScale();
        SmoothFont small = SmoothFont.regular(8);
        int tint = Draw.fade(GlassTheme.MUTED, 0.85f);
        // Right-aligned lines were positioned with the vanilla font's width; re-anchor them.
        if (x > this.width / 2) small.drawRight(text, x + font.getStringWidth(text), y - 1, tint);
        else small.draw(text, x + 1, y - 1, tint);
    }

    /** Glass panel behind the button column plus the title, drawn right before the buttons. */
    @Inject(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiScreen;drawScreen(IIF)V", ordinal = 0), require = 0)
    private void drawGlassMenu(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (GuiButton button : buttonList) {
            if (!(button instanceof GlassButton) || !button.visible) continue;
            left = Math.min(left, button.xPosition);
            top = Math.min(top, button.yPosition);
            right = Math.max(right, button.xPosition + button.getButtonWidth());
            bottom = Math.max(bottom, button.yPosition + button.height);
        }
        if (left > right) return;

        // During the opening the panel waits for its turn; afterwards it enters whenever the menu is rebuilt.
        boolean opening = myau.ui.glass.Intro.running();
        float seconds = opening ? myau.ui.glass.Intro.menuSeconds() : (System.nanoTime() - bettermyau$openedAt) / 1_000_000_000f;
        float appear = Anim.outCubic(seconds / 0.45f);
        Draw.useGuiScale();
        GlassBlur.invalidate();

        float pad = 10, x = left - pad, y = top - pad, w = right - left + pad * 2, h = bottom - top + pad * 2;
        Draw.pushTransform(x + w / 2, y + h / 2, Anim.lerp(0.94f, 1, Anim.outBack(seconds / 0.5f)), 0, (1 - appear) * 10);
        Draw.pushAlpha(appear);
        GlassTheme.panel(x, y, w, h, GlassTheme.R_PANEL + 2, 1.5f);
        // Leaving the menu cross-dissolves into the next screen, like any other glass screen.
        myau.ui.glass.ScreenTransition.markGlass();
        myau.ui.glass.ScreenTransition.focus(x, y, w, h, GlassTheme.R_PANEL + 2);
        Draw.spot(x, y, w, h, GlassTheme.R_PANEL + 2, mouseX, mouseY, 120, 0x10ffffff);
        Draw.spotOutline(x, y, w, h, GlassTheme.R_PANEL + 2, 1, mouseX, mouseY, 150, 0x80ffffff);
        float sheen = (seconds - 0.3f) / 0.7f;
        if (sheen > 0 && sheen < 1) Draw.sheen(x, y, w, h, GlassTheme.R_PANEL + 2, sheen, Draw.alpha(0xffffff, 0.2f * (1 - sheen * sheen)));
        Draw.popAlpha();
        Draw.popTransform();

        // The opening brings the name here itself and fades the real title in under it.
        Draw.pushAlpha(opening ? myau.ui.glass.Intro.titleAlpha() : appear);
        SmoothFont title = SmoothFont.bold(26);
        float titleY = y - 16 - title.height() - (opening ? 0 : (1 - appear) * 8);
        bettermyau$titleCenterY = y - 16 - title.height() / 2;
        String name = "BetterMyau";
        float titleW = title.width(name);
        int accent = GlassTheme.accent();
        Draw.glow(this.width / 2f - titleW / 2 + 8, titleY + title.height() * 0.45f, titleW - 16, 4, 2, 26, Draw.alpha(accent, 0.3f));
        title.drawShadowed(name, this.width / 2f - titleW / 2, titleY, GlassTheme.TEXT);
        SmoothFont sub = SmoothFont.regular(8.5f);
        sub.drawCentered("v" + Myau.version, this.width / 2f, titleY + title.height() - 1, Draw.fade(GlassTheme.MUTED, 0.9f));
        Draw.popAlpha();
    }
}
