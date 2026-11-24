package myau.events;

import myau.event.events.Event;

/**
 * Fired for the local player before every move; the step height used for that move can be changed.
 * Whether a step really happened is reported by {@link StepConfirmEvent}.
 */
public class StepEvent implements Event {
    private float height;

    public StepEvent(float height) {
        this.height = height;
    }

    public float getHeight() {
        return this.height;
    }

    public void setHeight(float height) {
        this.height = height;
    }
}
