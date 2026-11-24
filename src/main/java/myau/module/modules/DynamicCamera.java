package myau.module.modules;

import myau.event.EventTarget;
import myau.events.LoadWorldEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * Camera movement adapted from AirPlus (GPL-3.0).
 * https://github.com/lmx0721/AirPlus
 */
public class DynamicCamera extends Module {
    private static final int FIRST_PERSON = 0;
    private static final int REAR_VIEW = 1;
    private static final int TILT_FIXED = 1;
    private static final int TILT_DYNAMIC = 2;

    public final FloatProperty distance = new FloatProperty("distance", 3.5F, 1F, 20F);
    public final BooleanProperty action = new BooleanProperty("action", true);
    public final FloatProperty actionSmoothness = new FloatProperty(
            "action-smoothness", 0.3F, 0.1F, 0.95F, () -> action.getValue());
    public final FloatProperty actionMaxDistance = new FloatProperty(
            "action-max-distance", 20F, 1F, 50F, () -> action.getValue());
    public final BooleanProperty viewAnimation = new BooleanProperty("view-animation", true);
    public final FloatProperty viewAnimationSpeed = new FloatProperty(
            "view-animation-speed", 15F, 2F, 50F, () -> viewAnimation.getValue());
    public final ModeProperty tilt = new ModeProperty("tilt", 0, new String[]{"Off", "Fixed", "Dynamic"});
    public final FloatProperty tiltAngle = new FloatProperty(
            "tilt-angle", 12F, -45F, 45F, () -> tilt.getValue() != 0);
    public final FloatProperty tiltSmoothness = new FloatProperty(
            "tilt-smoothness", 10F, 1F, 30F, () -> tilt.getValue() != 0);
    public final BooleanProperty turnLean = new BooleanProperty(
            "turn-lean", true, () -> tilt.getValue() == TILT_DYNAMIC);

    private WorldClient world;
    private EntityPlayerSP player;
    private long lastFrameNanos;
    private Vec3 actionPosition;

    private int view, startView, targetView;
    private float viewDistance, startDistance, targetDistance;
    private double transitionElapsed, transitionDuration;

    private float roll, lastYaw, yawVelocity;

    public DynamicCamera() {
        super("DynamicCamera", false);
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        reset();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        reset();
    }

    private void reset() {
        world = null;
        player = null;
        actionPosition = null;
        roll = yawVelocity = 0F;
        transitionDuration = 0.0;
    }

    public Frame prepareFrame(float partialTicks, boolean viewClip, long nowNanos) {
        if (!isEnabled()) {
            return null;
        }

        EntityPlayerSP currentPlayer = mc.thePlayer;
        WorldClient currentWorld = mc.theWorld;
        if (currentPlayer == null || currentWorld == null || mc.getRenderViewEntity() != currentPlayer
                || currentPlayer.isPlayerSleeping() || mc.gameSettings.debugCamEnable
                || (FreeLook.INSTANCE != null && FreeLook.INSTANCE.isActive())) {
            reset();
            return null;
        }

        int requestedView = mc.gameSettings.thirdPersonView;
        double elapsed = 0.0;
        if (player != currentPlayer || world != currentWorld) {
            reset();
            player = currentPlayer;
            world = currentWorld;
            lastYaw = player.rotationYaw;
            syncView(requestedView);
        } else {
            elapsed = Math.max(0.0, (nowNanos - lastFrameNanos) * 1.0e-9);
            updateView(requestedView, elapsed);
        }
        lastFrameNanos = nowNanos;

        updateRoll((float) Math.min(0.1, elapsed));
        Vec3 translation = updateAction(partialTicks, viewClip);
        return new Frame(view, viewDistance, roll, translation);
    }

    private void syncView(int requestedView) {
        view = targetView = requestedView;
        viewDistance = distanceFor(requestedView);
        transitionDuration = 0.0;
    }

    private float distanceFor(int perspective) {
        return perspective == FIRST_PERSON ? 0F : distance.getValue();
    }

    private void updateView(int requestedView, double elapsed) {
        if (!viewAnimation.getValue()) {
            syncView(requestedView);
            return;
        }
        if (requestedView != targetView) {
            startView = view;
            startDistance = viewDistance;
            targetView = requestedView;
            targetDistance = distanceFor(requestedView);
            transitionElapsed = 0.0;
            transitionDuration = MathHelper.clamp_float(7.5F / viewAnimationSpeed.getValue(), 0.08F, 2F);
        } else if (transitionDuration > 0.0) {
            transitionElapsed += elapsed;
        } else {
            viewDistance += (distanceFor(view) - viewDistance)
                    * (float) -Math.expm1(-viewAnimationSpeed.getValue() * elapsed);
            return;
        }

        if (transitionElapsed >= transitionDuration || (startDistance == 0F && targetView == FIRST_PERSON)) {
            view = targetView;
            viewDistance = targetDistance;
            transitionDuration = 0.0;
            return;
        }

        float t = (float) (transitionElapsed / transitionDuration);
        float eased = t * t * (3F - 2F * t);
        if (startDistance == 0F || startView == targetView) {
            view = targetView;
            viewDistance = startDistance + (targetDistance - startDistance) * eased;
        } else if (targetView == FIRST_PERSON) {
            view = startView;
            viewDistance = startDistance * (1F - eased);
        } else if (eased < 0.5F) {
            view = startView;
            viewDistance = startDistance * (1F - 2F * eased);
        } else {
            view = targetView;
            viewDistance = targetDistance * (2F * eased - 1F);
        }
    }

    private Vec3 updateAction(float partialTicks, boolean viewClip) {
        if (view != REAR_VIEW || !action.getValue()) {
            actionPosition = null;
            return null;
        }

        Vec3 eye = new Vec3(
                player.prevPosX + (player.posX - player.prevPosX) * partialTicks,
                player.prevPosY + (player.posY - player.prevPosY) * partialTicks + player.getEyeHeight(),
                player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks);
        if (actionPosition == null) {
            actionPosition = eye;
            return null;
        }

        double lag = actionPosition.distanceTo(eye);
        double maxDistance = actionMaxDistance.getValue();
        if (lag > maxDistance) {
            actionPosition = eye;
            return null;
        }
        if (lag == 0.0) {
            return null;
        }

        // Keep the AirPlus coefficient per frame so it responds the same way at a given FPS.
        double follow = actionSmoothness.getValue() * (1.0 - Math.exp(-lag / maxDistance));
        actionPosition = actionPosition.addVector(
                (eye.xCoord - actionPosition.xCoord) * follow,
                (eye.yCoord - actionPosition.yCoord) * follow,
                (eye.zCoord - actionPosition.zCoord) * follow);

        float weight = transitionDuration > 0.0 ? Math.min(1F, viewDistance / distance.getValue()) : 1F;
        if (weight == 0F) {
            return null;
        }
        Vec3 translation = new Vec3(
                (eye.xCoord - actionPosition.xCoord) * weight,
                (eye.yCoord - actionPosition.yCoord) * weight,
                (eye.zCoord - actionPosition.zCoord) * weight);
        if (!viewClip && world.rayTraceBlocks(eye, eye.subtract(translation)) != null) {
            actionPosition = eye;
            return null;
        }
        return translation;
    }

    private void updateRoll(float elapsed) {
        if (elapsed <= 0F) {
            return;
        }
        float yaw = player.rotationYaw;
        float yawDelta = MathHelper.wrapAngleTo180_float(yaw - lastYaw);
        lastYaw = yaw;
        float instantaneousVelocity = MathHelper.clamp_float(yawDelta / elapsed, -720F, 720F);
        yawVelocity += (instantaneousVelocity - yawVelocity) * (float) -Math.expm1(-12F * elapsed);

        int mode = tilt.getValue();
        float targetRoll = 0F;
        if (mode == TILT_FIXED) {
            targetRoll = tiltAngle.getValue();
        } else if (mode == TILT_DYNAMIC) {
            float lean = -player.moveStrafing;
            if (turnLean.getValue()) {
                lean += MathHelper.clamp_float(yawVelocity / 360F, -1F, 1F);
            }
            targetRoll = lean * tiltAngle.getValue();
        }
        roll += (targetRoll - roll) * (float) -Math.expm1(-tiltSmoothness.getValue() * elapsed);
        if (Math.abs(roll) < 0.01F) {
            roll = 0F;
        }
    }

    public static final class Frame {
        public final int view;
        public final float distance;
        public final float roll;
        public final Vec3 translation;

        private Frame(int view, float distance, float roll, Vec3 translation) {
            this.view = view;
            this.distance = distance;
            this.roll = roll;
            this.translation = translation;
        }
    }
}
