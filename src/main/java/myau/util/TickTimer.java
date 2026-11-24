package myau.util;

/** Counts ticks by hand: call {@link #update()} once per tick. */
public class TickTimer {
    private int tick;

    public void update() {
        this.tick++;
    }

    public void reset() {
        this.tick = 0;
    }

    public boolean hasTimePassed(int ticks) {
        return this.tick >= ticks;
    }

    public int getTick() {
        return this.tick;
    }
}
