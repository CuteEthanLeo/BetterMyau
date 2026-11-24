package myau.ui.glass;

import myau.module.modules.FreeLook;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Mouse;

/**
 * Where the light that Better liquid glass reflects sits: above the top left of the window. It
 * swings when the camera turns and settles back, and leans towards the pointer while a screen is
 * open, so the highlights on a panel answer to motion. It stays put when animations are off.
 */
public final class GlassLight {
    private GlassLight() { }

    private static final Minecraft mc = Minecraft.getMinecraft();
    // Screen heights from the bottom-left corner, y up; REST_X is multiplied by the aspect ratio.
    private static final float REST_X = 0.20f, REST_Y = 1.25f, HEIGHT = 0.40f;
    // Screen heights per degree turned, the furthest the light swings, and how fast it comes back.
    private static final float TURN_GAIN = 0.004f, TURN_MAX = 0.35f, TURN_DECAY = 5f;
    // A turn of more than this in one frame is a teleport, a respawn or a change of camera.
    private static final float TURN_JUMP = 45f;
    private static final float POINTER_REACH = 0.22f;

    private static final Anim.Smooth pointerX = new Anim.Smooth(0, 7), pointerY = new Anim.Smooth(0, 7);
    private static Entity lastView;
    private static boolean lastFree, tracking;
    private static float lastYaw, lastPitch, turnX, turnY;
    private static float x = REST_X * 16f / 9f, y = REST_Y;

    /** Once per rendered frame, right after {@link Anim#beginFrame}. */
    public static void frame() {
        float aspect = mc.displayHeight > 0 ? mc.displayWidth / (float) mc.displayHeight : 16f / 9f;
        if (Anim.reduced()) {
            turnX = turnY = 0;
            pointerX.snap(0);
            pointerY.snap(0);
            tracking = false;
            x = REST_X * aspect;
            y = REST_Y;
            return;
        }
        float dt = Anim.dt();
        Entity view = mc.theWorld == null ? null : mc.getRenderViewEntity();
        if (view == null) {
            tracking = false;
            turnX = turnY = 0;
        } else {
            FreeLook look = FreeLook.INSTANCE;
            boolean free = look != null && look.isActive();
            float yaw = free ? look.cameraYaw : view.rotationYaw;
            float pitch = free ? look.cameraPitch : view.rotationPitch;
            if (tracking && view == lastView && free == lastFree) {
                float turned = MathHelper.wrapAngleTo180_float(yaw - lastYaw), tilted = pitch - lastPitch;
                if (Math.abs(turned) < TURN_JUMP && Math.abs(tilted) < TURN_JUMP) {
                    // Turning right moves the world, and a light fixed in it, to the left; looking down moves it up.
                    turnX -= turned * TURN_GAIN;
                    turnY += tilted * TURN_GAIN;
                }
            }
            tracking = true;
            lastView = view;
            lastFree = free;
            lastYaw = yaw;
            lastPitch = pitch;
            float keep = (float) Math.exp(-TURN_DECAY * dt);
            turnX = clamp(turnX * keep);
            turnY = clamp(turnY * keep);
        }
        float tx = 0, ty = 0;
        if (mc.currentScreen != null && mc.displayWidth > 0 && mc.displayHeight > 0 && Mouse.isInsideWindow()) {
            tx = (Draw.clamp01(Mouse.getX() / (float) mc.displayWidth) * 2 - 1) * POINTER_REACH * aspect;
            ty = (Draw.clamp01(Mouse.getY() / (float) mc.displayHeight) * 2 - 1) * POINTER_REACH;
        }
        x = REST_X * aspect + turnX + pointerX.update(tx, dt);
        y = REST_Y + turnY + pointerY.update(ty, dt);
    }

    private static float clamp(float value) {
        return Math.max(-TURN_MAX, Math.min(TURN_MAX, value));
    }

    /** Light position in screen heights from the bottom-left corner of the window, y up. */
    public static float x() { return x; }
    public static float y() { return y; }
    /** Height of the light above the screen, in screen heights. */
    public static float z() { return HEIGHT; }
}
