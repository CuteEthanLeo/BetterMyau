package myau.mixin;

import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether the player cancelled a connection, so a version check that took a while does not connect anyway. */
@SideOnly(Side.CLIENT)
@Mixin({GuiConnecting.class})
public interface IAccessorGuiConnecting {
    @Accessor("cancel")
    boolean isConnectCancelled();
}
