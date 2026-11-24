package myau.mixin;

import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The tab list's header and footer, for the island's player list. */
@SideOnly(Side.CLIENT)
@Mixin({GuiPlayerTabOverlay.class})
public interface IAccessorGuiPlayerTabOverlay {
    @Accessor("header")
    IChatComponent getHeader();

    @Accessor("footer")
    IChatComponent getFooter();
}
