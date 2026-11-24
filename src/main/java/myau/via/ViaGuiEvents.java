package myau.via;

import myau.ui.GlassButton;
import myau.ui.glass.GlassMultiplayer;
import myau.ui.glass.GlassVersionPicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraftforge.client.event.GuiScreenEvent.ActionPerformedEvent;
import net.minecraftforge.client.event.GuiScreenEvent.InitGuiEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Cross-version on the vanilla multiplayer screen (when the glass server list is off): a
 * "版本" button next to "Accounts" opens the version picker. The glass screen has its own chip.
 */
public final class ViaGuiEvents {
    private static final int BUTTON_ID = 0x5649; // "VI"

    @SubscribeEvent
    public void onInitGui(InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiMultiplayer) || event.gui instanceof GlassMultiplayer) return;
        event.buttonList.add(new GlassButton(BUTTON_ID, event.gui.width - 212, 6, 100, 20, label()).floating());
    }

    @SubscribeEvent
    public void onAction(ActionPerformedEvent.Pre event) {
        if (!(event.gui instanceof GuiMultiplayer) || event.gui instanceof GlassMultiplayer) return;
        GuiButton button = event.button;
        if (button == null || button.id != BUTTON_ID) return;
        event.setCanceled(true);
        Minecraft.getMinecraft().displayGuiScreen(new GlassVersionPicker(event.gui));
    }

    private static String label() {
        return "版本: " + ViaUi.globalLabel();
    }
}
