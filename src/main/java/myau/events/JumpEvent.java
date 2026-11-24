package myau.events;

import myau.event.events.callables.EventCancellable;
import myau.event.types.EventType;

/**
 * Fired when the local player jumps. PRE: cancellable, upward motion can be changed.
 * POST: the jump has been applied.
 */
public class JumpEvent extends EventCancellable {
    private final EventType type;
    private float motion;

    public JumpEvent(EventType type, float motion) {
        this.type = type;
        this.motion = motion;
    }

    public EventType getType() {
        return this.type;
    }

    public float getMotion() {
        return this.motion;
    }

    public void setMotion(float motion) {
        this.motion = motion;
    }
}
