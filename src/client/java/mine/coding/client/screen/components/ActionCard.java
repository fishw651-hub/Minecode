package mine.coding.client.screen.components;

import java.util.function.Consumer;

/**
 * One server-preview action together with its independent confirm/reject/undo callbacks.
 *
 * <p>Deliberately free of Minecraft types: the console renders the text lines and the buttons, so
 * this class stays pure state and can be unit tested like the rest of the client logic.
 */
public final class ActionCard {
    public record Action(String id, String description, String command, boolean allowed,
                         String reason, int blockCount, boolean reversible) {}

    private final Action action;
    private final Consumer<Action> confirm;
    private final Consumer<Action> reject;
    private final Consumer<Action> undo;
    private boolean resolved;

    public ActionCard(Action action, Consumer<Action> confirm, Consumer<Action> reject, Consumer<Action> undo) {
        this.action = action;
        this.confirm = confirm == null ? ignored -> {} : confirm;
        this.reject = reject == null ? ignored -> {} : reject;
        this.undo = undo == null ? ignored -> {} : undo;
    }

    public Action action() { return action; }
    public boolean resolved() { return resolved; }

    /** Confirming is possible once, and only for an action the server flagged as allowed. */
    public void confirm() { if (!resolved && action.allowed()) { resolved = true; confirm.accept(action); } }
    /** Rejecting is possible once, for allowed and forbidden actions alike. */
    public void reject() { if (!resolved) { resolved = true; reject.accept(action); } }
    /** Undo stays available: a resolved action may still be rolled back. */
    public void undo() { if (action.reversible()) undo.accept(action); }
}
