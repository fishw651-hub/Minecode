package mine.coding.client;

import mine.coding.ai.AiPlan;
import mine.coding.ai.spatial.FocusRequest;
import mine.coding.ai.spatial.StageCheckpoint;

import java.util.List;
import java.util.Locale;

/**
 * Client-side ownership of the one server proposal the console may still act on.
 *
 * <p>Every confirm, reject and stage request carries the server-issued {@code planId}, and a project
 * approval is only valid for the {@code draftId} the server generated. When the server invalidates
 * the preview (authorization change, expiry, superseded plan) or the player cancels the round, the
 * console has to forget those ids: otherwise stale buttons keep sending a dead id and the server
 * answers "动作已经处理" instead of letting the player start over.
 */
public final class ProposalState {
    /** Server messages meaning "the preview you are holding is no longer executable". */
    private static final List<String> INVALIDATING = List.of(
            "请重新生成预览", "unknown or superseded plan", "plan expired", "plan dimension changed");

    private String planId = "";
    private String draftId = "";
    private AiPlan projectPlan;
    private StageCheckpoint checkpoint;
    private List<FocusRequest> focusRequests = List.of();

    public synchronized String planId() { return planId; }
    public synchronized String draftId() { return draftId; }
    public synchronized AiPlan projectPlan() { return projectPlan; }
    public synchronized StageCheckpoint checkpoint() { return checkpoint; }
    public synchronized List<FocusRequest> focusRequests() { return focusRequests; }
    public synchronized boolean hasPlan() { return !planId.isBlank(); }

    /** True while a drafted project is waiting for the player's approval. */
    public synchronized boolean awaitingApproval() { return projectPlan != null; }

    /** Remembers a fresh plan: its attention requests, its checkpoint and (when drafted) its project. */
    public synchronized void offer(AiPlan plan) {
        projectPlan = plan != null && plan.projectDraft() != null ? plan : null;
        checkpoint = plan == null ? null : plan.checkpoint();
        focusRequests = plan == null ? List.of() : List.copyOf(plan.attentionRequests());
        draftId = "";
    }

    /** Records the server draft id that a later approval answer must echo. */
    public synchronized void draft(String draftId) { this.draftId = draftId == null ? "" : draftId; }

    /** True when the server confirms the draft this console is waiting for. */
    public synchronized boolean confirmsDraft(String requestId) {
        return requestId != null && !draftId.isBlank() && draftId.equals(requestId);
    }

    /** Takes the drafted plan the approval applies to and forgets it; {@code null} when none is pending. */
    public synchronized AiPlan takeDraftPlan() {
        AiPlan plan = projectPlan;
        clearProposal();
        return plan;
    }

    /** Records the server plan id of the preview currently on screen. */
    public synchronized void preview(String planId) {
        this.planId = planId == null ? "" : planId;
        projectPlan = null;
    }

    /** Forgets the plan id and every pointer that belongs to it; safe to call repeatedly. */
    public synchronized void clearProposal() {
        planId = "";
        draftId = "";
        projectPlan = null;
        checkpoint = null;
        focusRequests = List.of();
    }

    /** Applies a server error message: forgets the proposal when the error invalidates it. Returns whether it did. */
    public synchronized boolean invalidatedBy(String message) {
        if (!invalidates(message)) return false;
        clearProposal();
        return true;
    }

    /** Pure rule: does this server error message invalidate the current preview? */
    public static boolean invalidates(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        for (String needle : INVALIDATING) if (lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}
