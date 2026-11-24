package myau.events;

import myau.event.events.callables.EventCancellable;

/**
 * Fired for the local player right before {@code moveEntity(x, y, z)} is applied in
 * {@code moveEntityWithHeading}. The motion that is actually applied can be rewritten.
 */
public class MoveEvent extends EventCancellable {
    private double x;
    private double y;
    private double z;

    public MoveEvent(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() {
        return this.x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return this.y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return this.z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public void zero() {
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    public void zeroXZ() {
        this.x = 0.0;
        this.z = 0.0;
    }
}
