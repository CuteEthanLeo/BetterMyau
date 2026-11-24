package myau.mixin;

import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen a multiplayer screen goes back to, so the glass one can take its place. */
@SideOnly(Side.CLIENT)
@Mixin({GuiMultiplayer.class})
public interface IAccessorGuiMultiplayer {
    @Accessor("parentScreen")
    GuiScreen getParentScreen();
}
