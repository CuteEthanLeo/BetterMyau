package myau.mixin;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen a world list goes back to, so the glass one can take its place. */
@SideOnly(Side.CLIENT)
@Mixin({GuiSelectWorld.class})
public interface IAccessorGuiSelectWorld {
    @Accessor("parentScreen")
    GuiScreen getParentScreen();
}
