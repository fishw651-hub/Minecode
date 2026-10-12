package mine.coding.ai.transport;

/** A role/content message exchanged with the model. */
public record ChatMessage(String role, String content) {
    public ChatMessage {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role is required");
        if (content == null) throw new IllegalArgumentException("content is required");
    }
}
