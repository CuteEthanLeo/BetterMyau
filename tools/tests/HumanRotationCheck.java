import myau.util.HumanRotation;

/** Standalone trajectory checks; needs only HumanRotation.java and a JDK. */
public final class HumanRotationCheck {
    private static float wrap(float value) {
        value %= 360;
        return value >= 180 ? value - 360 : value < -180 ? value + 360 : value;
    }

    public static void main(String[] args) {
        int trajectories = 0;
        for (float sensitivity : new float[]{0, 0.5f, 1}) {
            float base = sensitivity * 0.6f + 0.2f;
            float gcd = base * base * base * 1.2f;
            for (float speed : new float[]{5, 20, 100}) {
                for (float smooth : new float[]{0, 0.65f, 1}) {
                    for (int repeat = 0; repeat < 20; repeat++) {
                        HumanRotation controller = new HumanRotation();
                        float yaw = 179, pitch = -75;
                        for (int phase = 0; phase < 3; phase++) {
                            float targetYaw = phase == 0 ? -179 : phase == 1 ? -1 : 179;
                            float targetPitch = phase == 0 ? 85 : phase == 1 ? -85 : 0;
                            for (int tick = 0; tick < 180; tick++) {
                                float[] next = controller.step(phase, yaw, pitch, targetYaw, targetPitch, speed, smooth, gcd);
                                float dx = wrap(next[0] - yaw), dy = next[1] - pitch;
                                check(Float.isFinite(next[0]) && Float.isFinite(next[1]), "finite angles");
                                check(next[1] >= -90 && next[1] <= 90, "pitch bounds");
                                check(Math.hypot(dx, dy) <= Math.max(2, speed * (1 - 0.45f * smooth) * 1.08f) + gcd * 1.5f, "bounded turn speed");
                                check(Math.abs(dx - Math.round(dx / gcd) * gcd) <= Math.ulp(yaw) * 2 + gcd * 0.001f, "mouse yaw steps");
                                // A pitch clamped at the physical limit may use a partial step.
                                if (Math.abs(next[1]) < 90) check(Math.abs(dy - Math.round(dy / gcd) * gcd) <= Math.ulp(pitch) * 2 + gcd * 0.001f, "mouse pitch steps");
                                yaw = next[0]; pitch = next[1];
                            }
                            check(Math.abs(wrap(targetYaw - yaw)) <= gcd * 0.51f + 0.001f, "yaw converges");
                            check(Math.abs(targetPitch - pitch) <= gcd * 0.51f + 0.001f, "pitch converges");
                            trajectories++;
                        }
                    }
                }
            }
        }
        System.out.println("PASS: " + trajectories + " trajectories (wrap, retarget, settle, speed, pitch and mouse-step checks)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
