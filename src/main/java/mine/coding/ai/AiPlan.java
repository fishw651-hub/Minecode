package mine.coding.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structured response from the main AI, including proposed memory, skills, and delegations. */
public record AiPlan(
        String message,
        String thoughtSummary,
        List<AiAction> actions,
        List<String> memoryDrafts,
        List<SkillDraft> skillDrafts,
        List<Delegation> delegations
) {
    /** Backwards-compatible constructor for plans that do not declare delegations. */
    public AiPlan(String message, String thoughtSummary, List<AiAction> actions,
                  List<String> memoryDrafts, List<SkillDraft> skillDrafts) {
        this(message, thoughtSummary, actions, memoryDrafts, skillDrafts, List.of());
    }

    public AiPlan {
        message = message == null ? "" : message;
        thoughtSummary = thoughtSummary == null ? "" : thoughtSummary;
        actions = List.copyOf(actions);
        memoryDrafts = List.copyOf(memoryDrafts);
        skillDrafts = List.copyOf(skillDrafts);
        delegations = List.copyOf(delegations);
        validateDelegations(delegations);
    }

    public static AiPlan fromJson(String json) {
        final JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Plan must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException argumentException) {
                throw argumentException;
            }
            throw new IllegalArgumentException("Invalid plan JSON", exception);
        }

        if (!root.has("actions") || !root.get("actions").isJsonArray()) {
            throw new IllegalArgumentException("Plan must contain an actions array");
        }
        List<AiAction> actions = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("actions")) {
            actions.add(parseAction(element));
        }

        List<String> memories = parseStringArray(root, "memoryDrafts");
        List<SkillDraft> skills = parseSkillDrafts(root);
        List<Delegation> delegations = parseDelegations(root);
        return new AiPlan(optionalString(root, "message"), optionalString(root, "thoughtSummary"),
                actions, memories, skills, delegations);
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("message", message);
        root.addProperty("thoughtSummary", thoughtSummary);
        JsonArray actionArray = new JsonArray();
        for (AiAction action : actions) {
            JsonObject item = new JsonObject();
            item.addProperty("id", action.id());
            item.addProperty("type", action.type().name());
            item.addProperty("description", action.description());
            item.addProperty("risk", action.risk());
            item.add("payload", action.payload());
            actionArray.add(item);
        }
        root.add("actions", actionArray);

        JsonArray memoryArray = new JsonArray();
        memoryDrafts.forEach(memoryArray::add);
        root.add("memoryDrafts", memoryArray);

        JsonArray skillArray = new JsonArray();
        for (SkillDraft draft : skillDrafts) {
            JsonObject item = new JsonObject();
            item.addProperty("id", draft.id());
            item.addProperty("name", draft.name());
            item.addProperty("description", draft.description());
            item.addProperty("instructions", draft.instructions());
            skillArray.add(item);
        }
        root.add("skillDrafts", skillArray);

        JsonArray delegationArray = new JsonArray();
        for (Delegation delegation : delegations) {
            JsonObject item = new JsonObject();
            item.addProperty("id", delegation.id());
            item.addProperty("role", delegation.role());
            item.addProperty("task", delegation.task());
            delegationArray.add(item);
        }
        root.add("delegations", delegationArray);
        return root.toString();
    }

    private static AiAction parseAction(JsonElement element) {
        String id = element != null && element.isJsonObject()
                ? optionalString(element.getAsJsonObject(), "id") : "<missing-id>";
        try {
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("Action must be an object");
            }
            JsonObject item = element.getAsJsonObject();
            id = requiredString(item, "id");
            AiAction.Type type;
            try {
                type = AiAction.Type.valueOf(requiredString(item, "type"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown action type", exception);
            }
            String description = requiredString(item, "description");
            String risk = requiredString(item, "risk");
            JsonElement payloadElement = item.get("payload");
            if (payloadElement == null || !payloadElement.isJsonObject()) {
                throw new IllegalArgumentException("payload must be an object");
            }
            JsonObject payload = payloadElement.getAsJsonObject();
            validatePayload(type, payload);
            return new AiAction(id, type, description, risk, payload);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException argumentException
                    && argumentException.getMessage() != null
                    && argumentException.getMessage().startsWith("Invalid action ")) {
                throw argumentException;
            }
            throw new IllegalArgumentException("Invalid action " + id + ": " + exception.getMessage(), exception);
        }
    }

    private static void validatePayload(AiAction.Type type, JsonObject payload) {
        switch (type) {
            case PLACE_BLOCK -> {
                requireCoordinates(payload, "x", "y", "z");
                requiredString(payload, "block");
            }
            case FILL -> {
                requireCoordinates(payload, "x", "y", "z", "x2", "y2", "z2");
                requiredString(payload, "block");
            }
            case RUN_COMMAND -> requiredString(payload, "command");
        }
    }

    private static void requireCoordinates(JsonObject payload, String... fields) {
        for (String field : fields) {
            JsonElement value = payload.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("payload." + field + " must be an integer coordinate");
            }
            String number = value.getAsString();
            if (!number.matches("-?\\d+")) {
                throw new IllegalArgumentException("payload." + field + " must be an integer coordinate");
            }
            try {
                Integer.parseInt(number);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("payload." + field + " is outside the integer range", exception);
            }
        }
    }

    private static List<String> parseStringArray(JsonObject root, String field) {
        JsonElement element = root.get(field);
        if (element == null) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(field + " entries must be strings");
            }
            values.add(value.getAsString());
        }
        return values;
    }

    private static List<SkillDraft> parseSkillDrafts(JsonObject root) {
        JsonElement element = root.get("skillDrafts");
        if (element == null) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("skillDrafts must be an array");
        }
        List<SkillDraft> drafts = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                throw new IllegalArgumentException("skillDrafts entries must be objects");
            }
            JsonObject item = entry.getAsJsonObject();
            drafts.add(new SkillDraft(requiredString(item, "id"), requiredString(item, "name"),
                    requiredString(item, "description"), requiredString(item, "instructions")));
        }
        return drafts;
    }

    private static List<Delegation> parseDelegations(JsonObject root) {
        JsonElement element = root.get("delegations");
        if (element == null) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("delegations must be an array");
        }
        List<Delegation> delegations = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                throw new IllegalArgumentException("delegations entries must be objects");
            }
            JsonObject item = entry.getAsJsonObject();
            delegations.add(new Delegation(requiredString(item, "id"), requiredString(item, "role"),
                    requiredString(item, "task")));
        }
        validateDelegations(delegations);
        return delegations;
    }

    private static void validateDelegations(List<Delegation> delegations) {
        if (delegations.size() > 3) {
            throw new IllegalArgumentException("A plan may contain at most 3 delegations");
        }
        Set<String> ids = new HashSet<>();
        for (Delegation delegation : delegations) {
            if (!ids.add(delegation.id())) {
                throw new IllegalArgumentException("Delegation ids must be unique: " + delegation.id());
            }
        }
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
        return AiAction.requireText(element.getAsString(), field);
    }

    private static String optionalString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : "";
    }

    public record Delegation(String id, String role, String task) {
        public Delegation {
            id = AiAction.requireText(id, "id");
            role = AiAction.requireText(role, "role");
            task = AiAction.requireText(task, "task");
        }
    }
}
