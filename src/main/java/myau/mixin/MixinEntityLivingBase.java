package myau.mixin;

import myau.Myau;
import myau.event.EventManager;
import myau.event.types.EventType;
import myau.events.JumpEvent;
import myau.events.MoveEvent;
import myau.events.StepConfirmEvent;
import myau.events.StepEvent;
import myau.events.StrafeEvent;
import myau.management.RotationState;
import myau.module.modules.Jesus;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = {EntityLivingBase.class}, priority = 9999)
public abstract class MixinEntityLivingBase extends MixinEntity {
    @Shadow
    protected abstract float getJumpUpwardsMotion();

    private float bettermyau$jumpMotion;

    @Inject(method = {"jump"}, at = {@At("HEAD")}, cancellable = true)
    private void bettermyau$jumpPre(CallbackInfo callbackInfo) {
        if ((Entity) ((Object) this) instanceof EntityPlayerSP) {
            JumpEvent event = new JumpEvent(EventType.PRE, this.getJumpUpwardsMotion());
            EventManager.call(event);
            if (event.isCancelled()) {
                callbackInfo.cancel();
                return;
            }
            this.bettermyau$jumpMotion = event.getMotion();
        }
    }

    @Redirect(
            method = {"jump"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;getJumpUpwardsMotion()F"
            )
    )
    private float bettermyau$jumpMotion(EntityLivingBase entityLivingBase) {
        return (Entity) ((Object) this) instanceof EntityPlayerSP ? this.bettermyau$jumpMotion : this.getJumpUpwardsMotion();
    }

    @Inject(method = {"jump"}, at = {@At("RETURN")})
    private void bettermyau$jumpPost(CallbackInfo callbackInfo) {
        if ((Entity) ((Object) this) instanceof EntityPlayerSP) {
            EventManager.call(new JumpEvent(EventType.POST, (float) this.motionY));
        }
    }

    /**
     * MoveEvent / StepEvent / StepConfirmEvent for the local player. All three moveEntity calls
     * of moveEntityWithHeading (water, lava, normal) go through here.
     */
    @Redirect(
            method = {"moveEntityWithHeading"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;moveEntity(DDD)V"
            )
    )
    private void bettermyau$moveEntity(EntityLivingBase entityLivingBase, double x, double y, double z) {
        if (!((Entity) ((Object) this) instanceof EntityPlayerSP)) {
            entityLivingBase.moveEntity(x, y, z);
            return;
        }
        MoveEvent moveEvent = new MoveEvent(x, y, z);
        EventManager.call(moveEvent);
        if (moveEvent.isCancelled()) {
            return;
        }
        StepEvent stepEvent = new StepEvent(entityLivingBase.stepHeight);
        EventManager.call(stepEvent);
        float stepHeight = entityLivingBase.stepHeight;
        entityLivingBase.stepHeight = stepEvent.getHeight();
        double minY = entityLivingBase.getEntityBoundingBox().minY;
        boolean wasOnGround = this.onGround;
        try {
            entityLivingBase.moveEntity(moveEvent.getX(), moveEvent.getY(), moveEvent.getZ());
        } finally {
            entityLivingBase.stepHeight = stepHeight;
        }
        double lifted = entityLivingBase.getEntityBoundingBox().minY - minY;
        if (wasOnGround && moveEvent.getY() <= 0.0 && lifted > 1.0E-3) {
            EventManager.call(new StepConfirmEvent(lifted));
        }
    }

    @ModifyVariable(
            method = {"jump"},
            at = @At("STORE"),
            ordinal = 0
    )
    private float jump(float float1) {
        return (Entity) ((Object) this) instanceof EntityPlayerSP && RotationState.isActived()
                ? RotationState.getSmoothedYaw() * (float) (Math.PI / 180.0)
                : float1;
    }

    @Redirect(
            method = {"moveEntityWithHeading"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;moveFlying(FFF)V"
            )
    )
    private void moveEntityWithHeading(EntityLivingBase entityLivingBase, float float2, float float3, float float4) {
        if ((Entity) ((Object) this) instanceof EntityPlayerSP) {
            StrafeEvent event = new StrafeEvent(float2, float3, float4);
            EventManager.call(event);
            float2 = event.getStrafe();
            float3 = event.getForward();
            float4 = event.getFriction();
            boolean actived = RotationState.isActived();
            float yaw = this.rotationYaw;
            if (actived) {
                this.rotationYaw = RotationState.getSmoothedYaw();
            }
            entityLivingBase.moveFlying(float2, float3, float4);
            if (actived) {
                this.rotationYaw = yaw;
            }
        } else {
            entityLivingBase.moveFlying(float2, float3, float4);
        }
    }

    @ModifyVariable(
            method = {"moveEntityWithHeading"},
            name = {"f3"},
            at = @At("STORE")
    )
    private float moveEntityWithHeading(float float1) {
        if ((EntityLivingBase) ((Object) this) instanceof EntityPlayerSP && float1 == (float) EnchantmentHelper.getDepthStriderModifier((EntityLivingBase) ((Object) this))) {
            if (Myau.moduleManager == null) {
                return float1;
            }
            Jesus jesus = (Jesus) Myau.moduleManager.modules.get(Jesus.class);
            if (jesus.isEnabled() && (!jesus.groundOnly.getValue() || this.onGround)) {
                return Math.max(float1, jesus.speed.getValue());
            }
        }
        return float1;
    }
}
