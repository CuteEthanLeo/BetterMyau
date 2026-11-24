package myau.mixin;

import myau.Myau;
import myau.module.modules.Cape;
import myau.module.modules.Sprint;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SideOnly(Side.CLIENT)
@Mixin(value = {AbstractClientPlayer.class}, priority = 9999)
public abstract class MixinAbstractClientPlayer extends MixinEntityPlayer {
    @Redirect(
            method = {"getFovModifier"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/ai/attributes/IAttributeInstance;getAttributeValue()D"
            )
    )
    private double getFovModifier(IAttributeInstance iAttributeInstance) {
        double attributeValue = iAttributeInstance.getAttributeValue();
        if ((((Entity) (Object) this)) instanceof EntityPlayerSP && Myau.moduleManager != null) {
            Sprint sprint = (Sprint) Myau.moduleManager.modules.get(Sprint.class);
            return sprint.isEnabled() && sprint.shouldApplyFovFix(iAttributeInstance) ? attributeValue * 1.300000011920929 : attributeValue;
        } else {
            return attributeValue;
        }
    }

    // ── Cape: the local player wears the cape picked in the module; everyone else is untouched ──
    @Inject(
            method = {"getLocationCape"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void bettermyau$localCape(CallbackInfoReturnable<ResourceLocation> callbackInfoReturnable) {
        if (!((Entity) (Object) this instanceof EntityPlayerSP)) {
            return;
        }
        Cape cape = Cape.instance();
        if (cape == null || !cape.isEnabled()) {
            return;
        }
        ResourceLocation location = cape.getCape();
        if (location != null) {
            callbackInfoReturnable.setReturnValue(location);
        }
    }
}