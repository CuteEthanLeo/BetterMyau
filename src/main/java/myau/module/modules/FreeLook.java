package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;

/**
 * Look around without turning: while active, the mouse moves a separate camera and the player
 * keeps facing where it did. Only the view changes; nothing is sent to the server.
 * <p>
 * HOLD: active while the bound key is held (enabled from the GUI it stays on until the key has
 * been pressed and released once). TOGGLE: the key turns it on and off like any module.
 */
public class FreeLook extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public static final int MODE_HOLD = 0, MODE_TOGGLE = 1;
    /** Read every frame by the camera mixins. */
    public static FreeLook INSTANCE;

    public final ModeProperty mode = new ModeProperty("mode", MODE_HOLD, new String[]{"HOLD", "TOGGLE"});
    public final BooleanProperty autoF5 = new BooleanProperty("auto-f5", true);

    public float cameraYaw, cameraPitch, prevCameraYaw, prevCameraPitch;
    private boolean active, seenDown, changedView;
    private int savedView;

    public FreeLook() {
        super("FreeLook", false);
        INSTANCE = this;
    }

    /** The camera is detached from the player this frame. */
    public boolean isActive() {
        return active && isEnabled();
    }

    @Override
    public void onEnabled() {
        seenDown = getKey() != 0 && KeyBindUtil.isKeyDown(getKey());
        if (mc.thePlayer != null) start();
    }

    @Override
    public void onDisabled() {
        stop();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) {
            stop();
            return;
        }
        if (mode.getValue() == MODE_HOLD && getKey() != 0) {
            if (KeyBindUtil.isKeyDown(getKey())) seenDown = true;
            else if (seenDown) {
                setEnabled(false);
                return;
            }
        }
        if (!active) start();
        prevCameraYaw = cameraYaw;
        prevCameraPitch = cameraPitch;
    }

    /** Mouse movement while active, in the units {@code Entity.setAngles} gets. */
    public void turn(float yaw, float pitch) {
        float oldYaw = cameraYaw, oldPitch = cameraPitch;
        cameraYaw += yaw * 0.15f;
        cameraPitch = Math.max(-90, Math.min(90, cameraPitch - pitch * 0.15f));
        // Move the previous angles along, as vanilla does, so the frame interpolation stays smooth.
        prevCameraYaw += cameraYaw - oldYaw;
        prevCameraPitch += cameraPitch - oldPitch;
    }

    private void start() {
        active = true;
        cameraYaw = prevCameraYaw = mc.thePlayer.rotationYaw;
        cameraPitch = prevCameraPitch = mc.thePlayer.rotationPitch;
        savedView = mc.gameSettings.thirdPersonView;
        changedView = autoF5.getValue() && savedView == 0;
        if (changedView) mc.gameSettings.thirdPersonView = 1;
    }

    private void stop() {
        if (!active) return;
        active = false;
        if (changedView) mc.gameSettings.thirdPersonView = savedView;
        changedView = false;
    }
}
