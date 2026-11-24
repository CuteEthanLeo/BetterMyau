package myau.mixin;

import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@SideOnly(Side.CLIENT)
@Mixin({GuiOptionSlider.class})
public interface IAccessorGuiOptionSlider {
    @Accessor
    float getSliderValue();
}
