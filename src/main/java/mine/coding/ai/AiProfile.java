package mine.coding.ai;

import java.util.List;

/** User-selectable AI identity and its model-specific settings. */
public record AiProfile(
        String id,
        String name,
        String systemPrompt,
        String model,
        String reasoningEffort,
        List<String> skills
) {
    public AiProfile {
        id = AiAction.requireText(id, "id");
        name = AiAction.requireText(name, "name");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        model = AiAction.requireText(model, "model");
        reasoningEffort = AiAction.requireText(reasoningEffort, "reasoningEffort");
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
