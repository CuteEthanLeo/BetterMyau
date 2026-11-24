package myau.util;

import myau.mixin.IAccessorEntityLivingBase;
import myau.mixin.IAccessorEntityPlayer;
import myau.mixin.IAccessorGuiOptionSlider;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;

/**
 * Accessor casts for mixin classes. Mixin 0.7 rewrites any reference to another mixin type inside
 * a mixin, and fails ("unable to find corresponding type ... in hierarchy of") when that type's
 * target is not a supertype of the class being mixed into. Whether it trips depends on the order
 * the mixins happen to be prepared in, so it can appear after unrelated changes. Mixins call
 * these plain helpers instead of casting to an accessor themselves.
 */
public final class MixinBridge {
    private MixinBridge() {
    }

    public static float sliderValue(Object slider) {
        return ((IAccessorGuiOptionSlider) slider).getSliderValue();
    }

    public static ItemStack itemInUse(EntityPlayer player) {
        return ((IAccessorEntityPlayer) player).getItemInUse();
    }

    public static void setItemInUse(EntityPlayer player, ItemStack stack) {
        ((IAccessorEntityPlayer) player).setItemInUse(stack);
    }

    public static int itemInUseCount(EntityPlayer player) {
        return ((IAccessorEntityPlayer) player).getItemInUseCount();
    }

    public static void setItemInUseCount(EntityPlayer player, int count) {
        ((IAccessorEntityPlayer) player).setItemInUseCount(count);
    }

    /** The potion is in the entity's map, without going through isPotionActive (which is redirected). */
    public static boolean hasPotion(EntityLivingBase entity, Potion potion) {
        return ((IAccessorEntityLivingBase) entity).getActivePotionsMap().containsKey(potion.id);
    }
}
