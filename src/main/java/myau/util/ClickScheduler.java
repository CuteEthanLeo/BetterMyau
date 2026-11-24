package myau.util;

import java.util.Random;

/**
 * Draws the interval to the next click from one of several human-like distributions.
 * The four shapes really differ in the statistics of the intervals:
 * STABLE    - uniform in [1000/maxCps, 1000/minCps].
 * GAUSSIAN  - normally distributed, with a slowly drifting mean (hand speed wanders).
 * BUTTERFLY - two fingers alternating: a short interval then a long one, sometimes a double click.
 * DRAG      - a burst of very fast clicks, a pause, then the next burst.
 */
public class ClickScheduler {
    public static final int STABLE = 0;
    public static final int GAUSSIAN = 1;
    public static final int BUTTERFLY = 2;
    public static final int DRAG = 3;

    private static final double MIN_DELAY_MS = 10.0;

    private final Random random = new Random();
    private double gaussianDrift;
    private boolean butterflyShortNext = true;
    private double butterflyOwedMs;
    private int dragBurstLeft;

    public void reset() {
        this.gaussianDrift = 0.0;
        this.butterflyShortNext = true;
        this.butterflyOwedMs = 0.0;
        this.dragBurstLeft = 0;
    }

    /** True when this click should be skipped (finger slipped). */
    public boolean rollDrop(float chance) {
        return chance > 0.0f && this.random.nextFloat() < chance;
    }

    /** @return milliseconds until the next click */
    public long nextDelay(int mode, double minCps, double maxCps) {
        minCps = Math.max(1.0, minCps);
        maxCps = Math.max(minCps, maxCps);
        double shortest = 1000.0 / maxCps;
        double longest = 1000.0 / minCps;
        double delay;
        switch (mode) {
            case GAUSSIAN: {
                double mean = (shortest + longest) / 2.0;
                // the range ends are +-2 sigma; keep some spread even when min == max
                double sigma = Math.max((longest - shortest) / 4.0, mean * 0.06);
                // AR(1) drift: the average speed over a few dozen clicks slowly shifts
                this.gaussianDrift = this.gaussianDrift * 0.92 + this.random.nextGaussian() * sigma * 0.25;
                delay = mean + this.gaussianDrift + this.random.nextGaussian() * sigma;
                delay = Math.max(shortest * 0.6, Math.min(longest * 1.6, delay));
                break;
            }
            case BUTTERFLY: {
                double average = shortest + this.random.nextDouble() * (longest - shortest);
                if (this.butterflyShortNext) {
                    this.butterflyShortNext = false;
                    // the second finger follows closely; now and then both land almost together
                    double shortDelay = this.random.nextFloat() < 0.15f
                            ? 15.0 + this.random.nextDouble() * 20.0
                            : average * (0.5 + this.random.nextDouble() * 0.3);
                    this.butterflyOwedMs = average - shortDelay;
                    delay = shortDelay;
                } else {
                    this.butterflyShortNext = true;
                    // the long interval pays back the time the short one saved
                    delay = average + this.butterflyOwedMs;
                }
                break;
            }
            case DRAG: {
                if (this.dragBurstLeft > 0) {
                    this.dragBurstLeft--;
                    delay = shortest * (0.3 + this.random.nextDouble() * 0.25);
                } else {
                    this.dragBurstLeft = 4 + this.random.nextInt(7);
                    delay = longest * (1.5 + this.random.nextDouble() * 2.0);
                }
                break;
            }
            default:
                delay = shortest + this.random.nextDouble() * (longest - shortest);
        }
        return Math.round(Math.max(MIN_DELAY_MS, delay));
    }
}
