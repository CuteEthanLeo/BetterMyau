package myau.events;

import myau.event.events.Event;

/** Fired after a move in which the local player was lifted onto a block (stepped up). */
public class StepConfirmEvent implements Event {
    private final double height;

    public StepConfirmEvent(double height) {
        this.height = height;
    }

    /** How far the player was lifted, in blocks. */
    public double getHeight() {
        return this.height;
    }
}
