package mine.coding.ai;

import com.google.gson.JsonObject;

import java.util.Objects;

/** A single validated operation requested by an AI plan. */
public record AiAction(String id, Type type, String description, String risk, JsonObject payload) {
    public AiAction {
        id = requireText(id, "id");
        Objects.requireNonNull(type, "type");
        description = requireText(description, "description");
        risk = requireText(risk, "risk");
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
    }

    @Override
    public JsonObject payload() {
        return payload.deepCopy();
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing or blank " + field);
        }
        return value;
    }

    public enum Type {
        PLACE_BLOCK,
        FILL,
        RUN_COMMAND
    }
}
