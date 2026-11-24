package myau.mixin;

import myau.via.ViaHooks;
import net.minecraft.item.ItemSlab;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Cross-version: the slab's second placement path (merging into a double slab); see MixinItemPlaceSound. */
@SideOnly(Side.CLIENT)
@Mixin({ItemSlab.class})
public abstract class MixinItemSlabPlaceSound {
    @Redirect(method = "tryPlace", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;playSoundEffect(DDDLjava/lang/String;FF)V"))
    private void bettermyau$placeSound(World world, double x, double y, double z, String sound, float volume, float pitch) {
        ViaHooks.placeSound(world, x, y, z, sound, volume, pitch);
    }
}
