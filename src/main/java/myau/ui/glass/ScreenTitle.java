package myau.ui.glass;

import myau.module.modules.HUD;
import myau.ui.GlassTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiCreateFlatWorld;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.GuiCustomizeSkin;
import net.minecraft.client.gui.GuiCustomizeWorldScreen;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiRenameWorld;
import net.minecraft.client.gui.GuiScreenAddServer;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.gui.GuiScreenServerList;
import net.minecraft.client.gui.GuiSnooper;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScreenChatOptions;

/**
 * Titles of vanilla screens outside a world, in the menus' style: a small tracked label naming the
 * area ("SETTINGS", "SINGLEPLAYER"...), the title in the glass font, and a short accent line.
 * Vanilla draws its titles as the first white centred string near the top; only that one is
 * replaced, so second lines (the snooper's, Forge config paths) stay as they are.
 */
public final class ScreenTitle {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static Object lastOwner;
    private static int lastFrame = -1;

    private ScreenTitle() {
    }

    /** @return true when the title was drawn here and vanilla should not draw it */
    public static boolean handle(Object owner, String text, int x, int y, int color) {
        if (owner == null || owner != mc.currentScreen || mc.theWorld != null) return false;
        if (y > 25 || (color & 0xffffff) != 0xffffff || text == null || text.isEmpty()) return false;
        int frame = GlassTheme.frame();
        if (owner == lastOwner && frame == lastFrame) return false;
        lastOwner = owner;
        lastFrame = frame;
        if (!HUD.glassScreens()) return false;

        Draw.syncGuiScale();
        SmoothFont font = SmoothFont.bold(11.5f);
        float width = font.width(text);
        float top = y - 1.5f;
        String area = area(owner);
        if (area != null && y >= 14) {
            SmoothFont small = SmoothFont.bold(5.5f);
            float spacing = 1.3f, w = 0;
            for (int i = 0; i < area.length(); i++) w += small.width(String.valueOf(area.charAt(i))) + spacing;
            float lx = x - (w - spacing) / 2;
            for (int i = 0; i < area.length(); i++) {
                String ch = String.valueOf(area.charAt(i));
                small.draw(ch, lx, top - 8, Draw.alpha(0xffffff, 0.5f));
                lx += small.width(ch) + spacing;
            }
        }
        font.drawShadowed(text, x - width / 2, top, GlassTheme.TEXT);
        int accent = GlassTheme.vividAccent();
        float line = Math.min(28, width * 0.5f), ly = top + font.height() + 1.5f;
        Draw.gradientH(x - line / 2, ly, line / 2, 1.2f, 0.6f, accent & 0xffffff, accent);
        Draw.gradientH(x, ly, line / 2, 1.2f, 0.6f, accent, accent & 0xffffff);
        return true;
    }

    private static String area(Object screen) {
        if (screen instanceof GuiOptions || screen instanceof GuiVideoSettings || screen instanceof GuiControls
                || screen instanceof GuiScreenOptionsSounds || screen instanceof GuiLanguage
                || screen instanceof GuiScreenResourcePacks || screen instanceof GuiCustomizeSkin
                || screen instanceof ScreenChatOptions || screen instanceof GuiSnooper) return "SETTINGS";
        if (screen instanceof GuiCreateWorld || screen instanceof GuiRenameWorld || screen instanceof GuiCreateFlatWorld
                || screen instanceof GuiCustomizeWorldScreen) return "SINGLEPLAYER";
        if (screen instanceof GuiScreenAddServer || screen instanceof GuiScreenServerList) return "MULTIPLAYER";
        String name = screen.getClass().getName();
        if (name.startsWith("net.minecraftforge.")) return "FORGE";
        return null;
    }
}
