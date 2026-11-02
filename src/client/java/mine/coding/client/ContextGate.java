package mine.coding.client;

import java.util.Objects;

/**
 * Serialises world-context refreshes and defers one action until the freshest answer arrives.
 *
 * <p>Context answers carry no correlation id, so the only way to know which answer a deferred
 * action must wait for is to never let two requests be in flight: {@link #request()} coalesces
 * every refresh issued while one is outstanding into a single follow-up. The deferred action then
 * runs against the newest context and nothing else, without relying on answer ordering.
 *
 * <p>Used by the console as: {@code if (gate.request()) sendContext();} and then on every answer
 * {@code Step step = gate.settle();} followed by either {@code sendContext()} when
 * {@link Step#sendAgain()} or {@code step.action().run()} otherwise. Cancelling a task, closing
 * the screen or switching dimension calls {@link #clear()} so the deferred action can never run in
 * a world it was not prepared for.
 */
public final class ContextGate {
    private boolean inFlight;
    private boolean stale;
    private Runnable deferred;

    /**
     * Records a refresh wish (first open, "刷新世界", after a result, after an approval).
     *
     * @return {@code true} when the caller must send a context request now; {@code false} when a
     *         request is already in flight and this wish has been coalesced into one follow-up
     */
    public synchronized boolean request() {
        if (inFlight) {
            stale = true;
            return false;
        }
        inFlight = true;
        return true;
    }

    /** Queues the action to run once the newest pending context has been applied; newer wins. */
    public synchronized void defer(Runnable action) {
        deferred = Objects.requireNonNull(action, "action");
    }

    public synchronized boolean deferred() { return deferred != null; }

    /** Whether a refresh request is currently in flight. */
    public synchronized boolean inFlight() { return inFlight; }

    /**
     * Records that one context answer has been applied and reports what to do next: send the
     * coalesced follow-up request, or run the deferred action (handed out at most once).
     */
    public synchronized Step settle() {
        inFlight = false;
        if (stale) {
            stale = false;
            inFlight = true;
            return new Step(true, null);
        }
        Runnable action = deferred;
        deferred = null;
        return new Step(false, action);
    }

    /** Drops the deferred action and every pending flag (cancel, close, world switch). */
    public synchronized void clear() {
        deferred = null;
        inFlight = false;
        stale = false;
    }

    /** What the caller must do after an answer: send another request, or run the action. */
    public record Step(boolean sendAgain, Runnable action) {}
}
