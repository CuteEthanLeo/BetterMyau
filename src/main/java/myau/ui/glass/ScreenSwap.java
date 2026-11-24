package myau.ui.glass;

import myau.Myau;
import myau.module.modules.HUD;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;

/**
 * Replaces vanilla screens with their glass versions wherever they are opened from (other mods
 * included). Only the exact vanilla classes are replaced; subclasses belong to someone else. Each
 * replacement has its own HUD switch, and anything that fails to build keeps the vanilla screen.
 * Called from {@code Minecraft.displayGuiScreen}, possibly off the client thread: the glass
 * screens' constructors do no GL work.
 */
public final class ScreenSwap {
    private ScreenSwap() {
    }

    public static GuiScreen swap(GuiScreen screen) {
        if (screen == null) return null;
        Class<?> type = screen.getClass();
        try {
            if (type == GuiMainMenu.class) {
                if (!Minecraft.getMinecraft().isDemo() && HUD.glassMainMenu()) return new GlassMainMenu();
            } else if (type == GuiSelectWorld.class) {
                if (HUD.glassWorldList()) {
                    return new GlassSingleplayer(((myau.mixin.IAccessorGuiSelectWorld) screen).getParentScreen());
                }
            } else if (type == GuiMultiplayer.class) {
                if (HUD.glassServerList()) {
                    return new GlassMultiplayer(((myau.mixin.IAccessorGuiMultiplayer) screen).getParentScreen());
                }
            }
        } catch (RuntimeException e) {
            Myau.LOGGER.error("[Glass] {} failed to open, using vanilla", type.getSimpleName(), e);
        }
        return screen;
    }
}
