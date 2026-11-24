package myau.mixin;

import myau.module.modules.Animations;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Animations "swing-speed": the base length (6 ticks) of the local player's arm swing.
 * {@code getArmSwingAnimationEnd()I} holds that 6 three times (haste, mining fatigue, neither) and
 * all three are replaced, so the potion adjustments keep working on top of the new base.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = {EntityLivingBase.class}, priority = 9999)
public abstract class MixinEntityLivingBaseAnimations {
    @ModifyConstant(
            method = {"getArmSwingAnimationEnd"},
            constant = {@Constant(intValue = 6)}
    )
    private int bettermyau$swingDuration(int base) {
        if (!((Object) this instanceof EntityPlayerSP)) {
            return base;
        }
        Animations animations = Animations.instance();
        return animations != null && animations.isEnabled() ? animations.swingDuration(base) : base;
    }
}
