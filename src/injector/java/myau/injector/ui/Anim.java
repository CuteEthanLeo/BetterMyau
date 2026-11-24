package myau.injector.ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个平滑逼近目标值的动画量（指数缓动）。所有动画共用一个 60fps 的 Swing 定时器，而且只在有东西在动时运行：
 * 静止的界面不占 CPU、也不重绘。另外提供「持续动画」（转圈、呼吸点）：登记期间每帧重绘一次指定组件。
 */
public final class Anim {
    private static final List<Anim> active = new ArrayList<>();
    private static final Map<Component, Integer> continuous = new IdentityHashMap<>();
    private static final Timer timer = new Timer(16, e -> tick());
    private static long lastNanos;

    private final Component owner;
    private final float speed;
    private float value, target;
    private boolean running;
    private Runnable onChange;

    /** 每一帧数值变化后额外调用（比如抽屉高度变了要 revalidate）。 */
    public Anim onChange(Runnable r) {
        this.onChange = r;
        return this;
    }

    /** @param speed 每秒的逼近速率；12 左右是干脆的悬停反馈，6 左右更柔和 */
    public Anim(Component owner, float initial, float speed) {
        this.owner = owner;
        this.speed = speed;
        this.value = this.target = initial;
    }

    public float get() {
        return value;
    }

    public float target() {
        return target;
    }

    public void to(float t) {
        if (t == target && !running && value == t) return;
        target = t;
        if (!running) {
            running = true;
            active.add(this);
            ensureTimer();
        }
    }

    public void snap(float v) {
        value = target = v;
        if (owner != null) owner.repaint();
    }

    private boolean step(float dt) {
        float k = 1f - (float) Math.exp(-speed * dt);
        value += (target - value) * k;
        if (Math.abs(target - value) < 0.002f) {
            value = target;
            return false;
        }
        return true;
    }

    /** 登记一个需要每帧重绘的组件（比如正在转的加载圈）。 */
    public static void startContinuous(Component c) {
        continuous.merge(c, 1, Integer::sum);
        ensureTimer();
    }

    public static void stopContinuous(Component c) {
        Integer n = continuous.get(c);
        if (n == null) return;
        if (n <= 1) continuous.remove(c); else continuous.put(c, n - 1);
    }

    /** 当前时间（秒），持续动画用它算相位。 */
    public static double seconds() {
        return System.nanoTime() / 1e9;
    }

    private static void ensureTimer() {
        if (!timer.isRunning()) {
            lastNanos = System.nanoTime();
            timer.start();
        }
    }

    private static void tick() {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        List<Component> dirty = new ArrayList<>();
        for (int i = active.size() - 1; i >= 0; i--) {
            Anim a = active.get(i);
            boolean more = a.step(dt);
            if (a.owner != null) dirty.add(a.owner);
            if (a.onChange != null) a.onChange.run();
            if (!more) {
                a.running = false;
                active.remove(i);
            }
        }
        dirty.addAll(continuous.keySet());
        for (Component c : dirty) c.repaint();
        if (active.isEmpty() && continuous.isEmpty()) timer.stop();
    }
}
