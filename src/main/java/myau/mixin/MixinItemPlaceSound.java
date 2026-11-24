package myau.mixin;

import myau.via.ViaHooks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemReed;
import net.minecraft.item.ItemSlab;
import net.minecraft.item.ItemSnow;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cross-version: 1.9+ servers no longer send the placer the sound of a block they place (the
 * client is expected to play it), while 1.8 leaves it to the server. On a translated 1.9+
 * connection the client's own placement plays its sound locally, like a 1.9+ client.
 */
@SideOnly(Side.CLIENT)
@Mixin({ItemBlock.class, ItemSlab.class, ItemReed.class, ItemSnow.class})
public abstract class MixinItemPlaceSound {
    @Redirect(method = "onItemUse(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;FFF)Z",
            require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;playSoundEffect(DDDLjava/lang/String;FF)V"))
    private void bettermyau$placeSound(World world, double x, double y, double z, String sound, float volume, float pitch) {
        ViaHooks.placeSound(world, x, y, z, sound, volume, pitch);
    }
}
