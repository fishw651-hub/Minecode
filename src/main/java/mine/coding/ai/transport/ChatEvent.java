package mine.coding.ai.transport;

import mine.coding.ai.AiPlan;

/** Safe, UI-facing progress event. A plan is present only on the final PLAN event. */
public record ChatEvent(String agentId, String role, Type type, String text, AiPlan plan) {
    public enum Type { QUEUED, RUNNING, TEXT, REASONING, COMPLETED, FAILED, CANCELLED, PLAN }
    public ChatEvent {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
        if (role == null || role.isBlank()) role = "agent";
        if (type == null) throw new NullPointerException("type");
        if (text == null) text = "";
    }
}
