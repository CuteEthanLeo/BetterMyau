package myau.events;

import myau.event.events.callables.EventCancellable;

/**
 * Fired at the very start of the local player's tick (before UpdateEvent PRE).
 * Cancelling skips the whole player tick: no movement, no movement packet.
 */
public class PlayerTickEvent extends EventCancellable {
}
