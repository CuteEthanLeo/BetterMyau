package myau.mixin;

import myau.Myau;
import myau.data.Box;
import myau.event.EventManager;
import myau.events.PickEvent;
import myau.events.RaytraceEvent;
import myau.events.Render3DEvent;
import myau.module.modules.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.List;

@SideOnly(Side.CLIENT)
@Mixin(value = {EntityRenderer.class}, priority = 9999)
public abstract class MixinEntityRenderer {
    @Unique
    private Box<Integer> slot = null;
    @Unique
    private Box<ItemStack> using = null;
    @Unique
    private Box<Integer> useCount = null;
    @Shadow
    private Minecraft mc;
    @Shadow
    private float thirdPersonDistance;
    @Shadow
    private float thirdPersonDistanceTemp;
    @Unique
    private DynamicCamera.Frame bettermyau$cameraFrame;

    @Inject(
            method = {"updateCameraAndRender"},
            at = {@At("HEAD")}
    )
    private void updateCameraAndRender(float float1, long long2, CallbackInfo callbackInfo) {
        myau.ui.glass.GlassBlur.invalidate();
        myau.ui.GlassTheme.beginFrame();
        myau.ui.glass.ScreenTransition.frameStart();
        // Music player housekeeping (volume, auto-skip), every frame, menus included.
        if (myau.music.MusicService.running()) myau.music.MusicService.get().frame();
        if (myau.music.ui.MusicDevCheck.ON) myau.music.ui.MusicDevCheck.frame();
        if (this.mc.thePlayer != null) {
            int slot = myau.util.ItemUtil.spoofedDisplaySlot();
            if (slot >= 0) {
                this.slot = new Box<>(this.mc.thePlayer.inventory.currentItem);
                this.mc.thePlayer.inventory.currentItem = slot;
            }
            KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
            if (killAura.isEnabled() && killAura.isBlocking()) {
                this.using = new Box<>(myau.util.MixinBridge.itemInUse(this.mc.thePlayer));
                myau.util.MixinBridge.setItemInUse(this.mc.thePlayer, this.mc.thePlayer.inventory.getCurrentItem());
                this.useCount = new Box<>(myau.util.MixinBridge.itemInUseCount(this.mc.thePlayer));
                myau.util.MixinBridge.setItemInUseCount(this.mc.thePlayer, 69000);
            }
        }
    }

    @Inject(
            method = {"updateCameraAndRender"},
            at = {@At("RETURN")}
    )
    private void postUpdateCameraAndRender(float float1, long long2, CallbackInfo callbackInfo) {
        myau.ui.glass.ScreenTransition.frameEnd();
        myau.util.InputFixes.endFrame();
        if (this.slot != null) {
            this.mc.thePlayer.inventory.currentItem = this.slot.value;
            this.slot = null;
        }
        if (this.using != null) {
            myau.util.MixinBridge.setItemInUse(this.mc.thePlayer, this.using.value);
            this.using = null;
        }
        if (this.useCount != null) {
            myau.util.MixinBridge.setItemInUseCount(this.mc.thePlayer, this.useCount.value);
            this.useCount = null;
        }
    }

    // ── Screen transitions: bracket the current screen's draw, and get one hook after the HUD.
    @Inject(
            method = {"updateCameraAndRender"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/client/ForgeHooksClient;drawScreen(Lnet/minecraft/client/gui/GuiScreen;IIF)V",
                    remap = false
            )},
            require = 0
    )
    private void bettermyau$beforeScreen(float float1, long long2, CallbackInfo callbackInfo) {
        myau.ui.glass.ScreenTransition.beforeScreen(this.mc.currentScreen);
    }

    @Inject(
            method = {"updateCameraAndRender"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/client/ForgeHooksClient;drawScreen(Lnet/minecraft/client/gui/GuiScreen;IIF)V",
                    shift = At.Shift.AFTER,
                    remap = false
            )},
            require = 0
    )
    private void bettermyau$afterScreen(float float1, long long2, CallbackInfo callbackInfo) {
        myau.ui.glass.ScreenTransition.afterScreen();
        HUD.renderDeferredIsland();
    }

    @Inject(
            method = {"updateCameraAndRender"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiIngame;renderGameOverlay(F)V",
                    shift = At.Shift.AFTER
            )},
            require = 0
    )
    private void bettermyau$afterOverlay(float float1, long long2, CallbackInfo callbackInfo) {
        myau.ui.glass.ScreenTransition.afterOverlay();
    }

    @Inject(
            method = {"updateRenderer"},
            at = {@At("HEAD")}
    )
    private void updateRenderer(CallbackInfo callbackInfo) {
        if (this.mc.thePlayer == null) return;
        int slot = myau.util.ItemUtil.spoofedDisplaySlot();
        if (slot >= 0) {
            this.slot = new Box<>(this.mc.thePlayer.inventory.currentItem);
            this.mc.thePlayer.inventory.currentItem = slot;
        }
    }

    @Inject(
            method = {"updateRenderer"},
            at = {@At("RETURN")}
    )
    private void postUpdateRenderer(CallbackInfo callbackInfo) {
        if (this.slot != null) {
            this.mc.thePlayer.inventory.currentItem = this.slot.value;
            this.slot = null;
        }
    }

    // ── Motion blur entity mask: snapshot depth around the main (pass 0) entity draw. Forge's second
    //    entity pass after translucent terrain is ordinal 1 and deliberately left out.
    @Inject(
            method = {"renderWorldPass"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                    ordinal = 0
            )},
            require = 0
    )
    private void bettermyau$beforeEntities(int integer, float float2, long long3, CallbackInfo callbackInfo) {
        bettermyau$captureEntityDepth(false);
    }

    @Inject(
            method = {"renderWorldPass"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )},
            require = 0
    )
    private void bettermyau$afterEntities(int integer, float float2, long long3, CallbackInfo callbackInfo) {
        bettermyau$captureEntityDepth(true);
    }

    @Unique
    private static void bettermyau$captureEntityDepth(boolean after) {
        if (Myau.moduleManager == null) return;
        MotionBlur motionBlur = (MotionBlur) Myau.moduleManager.modules.get(MotionBlur.class);
        if (motionBlur != null && motionBlur.isEnabled() && motionBlur.excludeEntities.getValue()) {
            myau.ui.glass.MotionBlurRenderer.captureEntityDepth(after);
        }
    }

    @Inject(
            method = {"renderWorldPass"},
            at = {@At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;renderHand:Z",
                    shift = At.Shift.BEFORE
            )}
    )
    private void renderWorldPass(int integer, float float2, long long3, CallbackInfo callbackInfo) {
        // Motion blur goes first, so only the world is blurred: ESP, the hand and the HUD stay sharp.
        if (Myau.moduleManager != null) {
            MotionBlur motionBlur = (MotionBlur) Myau.moduleManager.modules.get(MotionBlur.class);
            if (motionBlur != null) motionBlur.render();
        }
        EventManager.call(new Render3DEvent(float2));
        // Whatever the modules drew into the glow mask during the event goes on screen now.
        myau.ui.glass.WorldGlow.flush();
    }

    @ModifyConstant(
            method = {"hurtCameraEffect"},
            constant = {@Constant(
                    floatValue = 14.0F,
                    ordinal = 0
            )}
    )
    private float hurtCameraEffect(float float1) {
        if (Myau.moduleManager == null) {
            return float1;
        } else {
            NoHurtCam noHurtCam = (NoHurtCam) Myau.moduleManager.modules.get(NoHurtCam.class);
            return noHurtCam.isEnabled() ? float1 * (float) noHurtCam.multiplier.getValue().intValue() / 100.0F : float1;
        }
    }

    @ModifyConstant(
            method = {"getMouseOver"},
            constant = {@Constant(
                    doubleValue = 3.0,
                    ordinal = 1
            )}
    )
    private double getMouseOver(double range) {
        PickEvent event = new PickEvent(range);
        EventManager.call(event);
        return event.getRange();
    }

    @ModifyVariable(
            method = {"getMouseOver"},
            at = @At("STORE"),
            name = {"d0"}
    )
    private double storeMouseOver(double range) {
        RaytraceEvent event = new RaytraceEvent(range);
        EventManager.call(event);
        return event.getRange();
    }

    @Inject(
            method = {"getMouseOver"},
            at = {@At(
                    value = "INVOKE",
                    target = "Ljava/util/List;size()I",
                    ordinal = 0
            )},
            locals = LocalCapture.CAPTURE_FAILSOFT
    )
    private void a(
            float float1,
            CallbackInfo callbackInfo,
            Entity entity,
            double double4,
            double double5,
            Vec3 vec36,
            boolean boolean7,
            int integer8,
            Vec3 vec39,
            Vec3 vec310,
            Vec3 vec311,
            float float12,
            List<Entity> list,
            double double14,
            int integer15
    ) {
        if (Myau.moduleManager != null) {
            GhostHand event = (GhostHand) Myau.moduleManager.modules.get(GhostHand.class);
            if (event.isEnabled()) {
                list.removeIf(event::shouldSkip);
            }
        }
    }

    @Redirect(
            method = {"setupFog"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/Block;getMaterial()Lnet/minecraft/block/material/Material;"
            )
    )
    private Material x(Block block) {
        if (Myau.moduleManager == null) {
            return block.getMaterial();
        } else {
            return Myau.moduleManager.modules.get(ViewClip.class).isEnabled() ? Material.air : block.getMaterial();
        }
    }

    @Redirect(
            method = {"updateFogColor"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;isPotionActive(Lnet/minecraft/potion/Potion;)Z"
            )
    )
    private boolean y(EntityLivingBase entityLivingBase, Potion potion) {
        if (potion == Potion.blindness && Myau.moduleManager != null) {
            AntiDebuff antiDebuff = (AntiDebuff) Myau.moduleManager.modules.get(AntiDebuff.class);
            if (antiDebuff.isEnabled() && antiDebuff.blindness.getValue()) {
                return false;
            }
        }
        return myau.util.MixinBridge.hasPotion(entityLivingBase, potion);
    }

    @Redirect(
            method = {"setupFog"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;isPotionActive(Lnet/minecraft/potion/Potion;)Z"
            )
    )
    private boolean q(EntityLivingBase entityLivingBase, Potion potion) {
        if (potion == Potion.blindness && Myau.moduleManager != null) {
            AntiDebuff antiDebuff = (AntiDebuff) Myau.moduleManager.modules.get(AntiDebuff.class);
            if (antiDebuff.isEnabled() && antiDebuff.blindness.getValue()) {
                return false;
            }
        }
        return myau.util.MixinBridge.hasPotion(entityLivingBase, potion);
    }

    @Redirect(
            method = {"setupCameraTransform"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;isPotionActive(Lnet/minecraft/potion/Potion;)Z"
            )
    )
    private boolean c(EntityPlayerSP entityPlayerSP, Potion potion) {
        if (potion == Potion.confusion && Myau.moduleManager != null) {
            AntiDebuff antiDebuff = (AntiDebuff) Myau.moduleManager.modules.get(AntiDebuff.class);
            if (antiDebuff.isEnabled() && antiDebuff.nausea.getValue()) {
                return false;
            }
        }
        return myau.util.MixinBridge.hasPotion(entityPlayerSP, potion);
    }

    // ── FreeLook: the camera reads its own angles instead of the player's ──
    @Unique
    private boolean bettermyau$freeLook(Entity entity) {
        FreeLook freeLook = FreeLook.INSTANCE;
        return freeLook != null && freeLook.isActive() && entity == this.mc.getRenderViewEntity();
    }

    @Redirect(
            method = {"orientCamera"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;rotationYaw:F", opcode = 180)
    )
    private float bettermyau$cameraYaw(Entity entity) {
        return bettermyau$freeLook(entity) ? FreeLook.INSTANCE.cameraYaw : entity.rotationYaw;
    }

    @Redirect(
            method = {"orientCamera"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;prevRotationYaw:F", opcode = 180)
    )
    private float bettermyau$cameraPrevYaw(Entity entity) {
        return bettermyau$freeLook(entity) ? FreeLook.INSTANCE.prevCameraYaw : entity.prevRotationYaw;
    }

    @Redirect(
            method = {"orientCamera"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;rotationPitch:F", opcode = 180)
    )
    private float bettermyau$cameraPitch(Entity entity) {
        return bettermyau$freeLook(entity) ? FreeLook.INSTANCE.cameraPitch : entity.rotationPitch;
    }

    @Redirect(
            method = {"orientCamera"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;prevRotationPitch:F", opcode = 180)
    )
    private float bettermyau$cameraPrevPitch(Entity entity) {
        return bettermyau$freeLook(entity) ? FreeLook.INSTANCE.prevCameraPitch : entity.prevRotationPitch;
    }

    @Redirect(method = "updateCameraAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EntityRenderer;renderWorld(FJ)V"))
    private void bettermyau$renderWorld(EntityRenderer renderer, float partialTicks, long finishTimeNano) {
        DynamicCamera camera = Myau.moduleManager == null ? null :
                (DynamicCamera) Myau.moduleManager.getModule(DynamicCamera.class);
        DynamicCamera.Frame frame = camera == null || !camera.isEnabled() ? null :
                camera.prepareFrame(partialTicks, bettermyau$viewClipEnabled(), System.nanoTime());
        if (frame == null) {
            renderer.renderWorld(partialTicks, finishTimeNano);
            return;
        }

        // Tick input uses the requested view. All world passes see one interpolated camera pose.
        int requestedView = this.mc.gameSettings.thirdPersonView;
        this.bettermyau$cameraFrame = frame;
        this.mc.gameSettings.thirdPersonView = frame.view;
        try {
            renderer.renderWorld(partialTicks, finishTimeNano);
        } finally {
            if (this.mc.gameSettings.thirdPersonView == frame.view) {
                this.mc.gameSettings.thirdPersonView = requestedView;
            }
            this.bettermyau$cameraFrame = null;
        }
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/EntityRenderer;thirdPersonDistance:F"))
    private float bettermyau$cameraDistance(EntityRenderer renderer) {
        return this.bettermyau$cameraFrame == null ? this.thirdPersonDistance : this.bettermyau$cameraFrame.distance;
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/EntityRenderer;thirdPersonDistanceTemp:F"))
    private float bettermyau$previousCameraDistance(EntityRenderer renderer) {
        return this.bettermyau$cameraFrame == null ? this.thirdPersonDistanceTemp : this.bettermyau$cameraFrame.distance;
    }

    @Unique
    private boolean bettermyau$viewClipEnabled() {
        return Myau.moduleManager != null && Myau.moduleManager.getModule(ViewClip.class).isEnabled();
    }

    @ModifyConstant(method = "orientCamera", constant = @Constant(intValue = 8))
    private int bettermyau$cameraCollisionSamples(int samples) {
        return bettermyau$viewClipEnabled() ? 0 : samples;
    }

    @Redirect(method = "orientCamera", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;rayTraceBlocks(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;)Lnet/minecraft/util/MovingObjectPosition;"))
    private MovingObjectPosition bettermyau$traceCameraCollision(WorldClient world, Vec3 start, Vec3 end) {
        Vec3 translation = bettermyau$cameraTranslation();
        return translation == null ? world.rayTraceBlocks(start, end) :
                world.rayTraceBlocks(start.subtract(translation), end.subtract(translation));
    }

    @Redirect(method = "orientCamera", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Vec3;distanceTo(Lnet/minecraft/util/Vec3;)D"))
    private double bettermyau$cameraCollisionDistance(Vec3 hit, Vec3 eye) {
        Vec3 translation = bettermyau$cameraTranslation();
        return hit.distanceTo(translation == null ? eye : eye.subtract(translation));
    }

    @Redirect(method = "orientCamera", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, remap = false,
            target = "Lnet/minecraftforge/client/event/EntityViewRenderEvent$CameraSetup;roll:F"))
    private float bettermyau$cameraRoll(EntityViewRenderEvent.CameraSetup event) {
        return this.bettermyau$cameraFrame == null ? event.roll : event.roll + this.bettermyau$cameraFrame.roll;
    }

    @Inject(method = "orientCamera", at = @At("RETURN"))
    private void bettermyau$translateActionCamera(float partialTicks, CallbackInfo ci) {
        Vec3 translation = bettermyau$cameraTranslation();
        if (translation != null) {
            GlStateManager.translate(translation.xCoord, translation.yCoord, translation.zCoord);
        }
    }

    @Unique
    private Vec3 bettermyau$cameraTranslation() {
        return this.bettermyau$cameraFrame == null ? null : this.bettermyau$cameraFrame.translation;
    }
}
