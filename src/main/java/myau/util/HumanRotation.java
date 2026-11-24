package myau.util;

import java.util.Random;

/** A continuous mouse stroke: accelerate, follow a shallow arc, then settle on the click point. */
public final class HumanRotation {
    private final Random random = new Random();
    private Object target;
    private float yawSpeed, pitchSpeed;
    private float strokeLength, curve, pace = 1.0f;
    private float lastYaw, lastPitch;
    private boolean active;

    public float[] step(Object target, float yaw, float pitch, float targetYaw, float targetPitch,
                        float speed, float smoothing, float mouseStep) {
        float dx = wrap(targetYaw - yaw);
        float dy = clamp(targetPitch, -90, 90) - pitch;
        float distance = (float) Math.hypot(dx, dy);
        if (!active || Math.abs(wrap(yaw - lastYaw)) > 12 || Math.abs(pitch - lastPitch) > 12) {
            reset();
        }
        if (!active || !java.util.Objects.equals(this.target, target)) {
            this.target = target;
            strokeLength = Math.max(1, distance);
            curve = (random.nextFloat() - 0.5f) * 0.18f;
            pace = 0.92f + random.nextFloat() * 0.16f;
            active = true;
        }
        float smooth = clamp(smoothing, 0, 1);
        float limit = Math.max(2, speed * (1 - 0.45f * smooth) * pace);
        float gain = 0.52f - 0.18f * smooth;
        float arc = (float) Math.sin(Math.PI * Math.min(1, distance / strokeLength)) * curve;
        float wantYaw = (dx - dy * arc) * gain;
        float wantPitch = (dy + dx * arc) * gain;
        float desiredLength = (float) Math.hypot(wantYaw, wantPitch);
        if (desiredLength > limit) {
            wantYaw *= limit / desiredLength;
            wantPitch *= limit / desiredLength;
        }
        float acceleration = Math.max(0.75f, limit * (0.32f - 0.1f * smooth));
        float ax = wantYaw - yawSpeed;
        float ay = wantPitch - pitchSpeed;
        float length = (float) Math.hypot(ax, ay);
        if (length > acceleration) {
            ax *= acceleration / length;
            ay *= acceleration / length;
        }
        yawSpeed = preventOvershoot(dx, yawSpeed + ax);
        pitchSpeed = preventOvershoot(dy, pitchSpeed + ay);
        float nextYaw = yaw + quantize(yawSpeed, dx, mouseStep);
        float nextPitch = clamp(pitch + quantize(pitchSpeed, dy, mouseStep), -90, 90);
        // Feedback uses the movement that was actually sent after mouse quantization.
        yawSpeed = nextYaw - yaw;
        pitchSpeed = nextPitch - pitch;
        lastYaw = nextYaw;
        lastPitch = nextPitch;
        return new float[]{nextYaw, nextPitch};
    }

    public void reset() {
        target = null;
        active = false;
        yawSpeed = pitchSpeed = 0;
    }

    private static float preventOvershoot(float error, float velocity) {
        return error * velocity > 0 && Math.abs(velocity) > Math.abs(error) ? error : velocity;
    }

    private static float quantize(float delta, float error, float step) {
        if (step <= 0) return delta;
        float result = Math.round(delta / step) * step;
        // A slow final correction must not stall between two mouse steps.
        if (result == 0 && Math.abs(error) > step * 0.5f) result = Math.copySign(step, error);
        return result;
    }

    private static float wrap(float angle) {
        angle %= 360;
        return angle >= 180 ? angle - 360 : angle < -180 ? angle + 360 : angle;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
