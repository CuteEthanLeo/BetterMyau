package myau.module.modules;

import myau.Myau;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.ui.glass.AugustusClickGui;
import myau.ui.glass.ClickGuiBase;
import myau.ui.glass.GlassClickGui;
import myau.ui.glass.GuiPalette;
import myau.ui.glass.SidebarClickGui;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

public class GuiModule extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public static final int STYLE_SIDEBAR = 0, STYLE_PANELS = 1, STYLE_AUGUSTUS = 2;
    public static final int FONT_SMOOTH = 0, FONT_MINECRAFT = 1;
    private static final String[] STYLES = {"SIDEBAR", "PANELS", "AUGUSTUS"};

    /** Layout of the click GUI. Changing it while the GUI is open switches on the spot. Names are saved: only append. */
    public final ModeProperty style = new ModeProperty("style", STYLE_SIDEBAR, STYLES);
    /** Colour theme of the click GUI (both layouts); the rest of the UI keeps its glass look. */
    public final ModeProperty theme = new ModeProperty("theme", 0, GuiPalette.NAMES);
    /** Text of the Augustus layout: the client's smooth font, or Minecraft's own pixel font. */
    public final ModeProperty font = new ModeProperty("font", FONT_SMOOTH, new String[]{"SMOOTH", "MINECRAFT"},
            () -> this.style.getValue() == STYLE_AUGUSTUS);

    private ClickGuiBase sidebar, panels, augustus;

    public GuiModule() {
        super("ClickGui", false);
        setKey(Keyboard.KEY_RSHIFT);
    }

    /** Display name of a layout, for the links that switch to it. */
    public static String styleName(int style) {
        return style == STYLE_SIDEBAR ? "Sidebar" : style == STYLE_PANELS ? "Panels" : "Augustus";
    }

    /** The layout after the current one, for a button that cycles through them. */
    public int nextStyle() {
        return (this.style.getValue() + 1) % STYLES.length;
    }

    /** The screen for the selected layout, created on first use. */
    public ClickGuiBase screen() {
        int selected = this.style.getValue();
        if (selected == STYLE_SIDEBAR) {
            try {
                if (this.sidebar == null) this.sidebar = new SidebarClickGui();
                return this.sidebar;
            } catch (RuntimeException e) {
                // Never leave the client without a GUI: fall back to the panels layout.
                Myau.LOGGER.error("Sidebar click GUI could not be created, using panels", e);
                this.style.setValue(STYLE_PANELS);
            }
        } else if (selected == STYLE_AUGUSTUS) {
            try {
                if (this.augustus == null) this.augustus = new AugustusClickGui();
                return this.augustus;
            } catch (RuntimeException e) {
                Myau.LOGGER.error("Augustus click GUI could not be created, using panels", e);
                this.style.setValue(STYLE_PANELS);
            }
        }
        if (this.panels == null) this.panels = new GlassClickGui();
        return this.panels;
    }

    @Override
    public void onEnabled() {
        setEnabled(false);
        mc.displayGuiScreen(screen());
    }
}
