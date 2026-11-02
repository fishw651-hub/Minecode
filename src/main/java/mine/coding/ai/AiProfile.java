package mine.coding.ai;

import java.util.List;

/**
 * User-selectable AI identity and its model-specific settings.
 *
 * <p>{@link #EFFORTS} is the single source of truth for the {@code reasoning_effort} values this
 * project understands. Everything that offers or sends an effort (the transport, the config UI and
 * its profile panel) must read it from here instead of keeping a private copy: the copies drifted
 * apart before (a client-side list and an inline transport list), so an effort could be selectable
 * in the UI yet silently dropped on the wire.</p>
 */
public record AiProfile(
        String id,
        String name,
        String systemPrompt,
        String model,
        String reasoningEffort,
        List<String> skills
) {
    /** Every effort the UI may offer, in the order the UI cycles through them. {@code none} means "do not send the field". */
    public static final List<String> EFFORTS = List.of("none", "low", "medium", "high");

    public AiProfile {
        id = AiAction.requireText(id, "id");
        name = AiAction.requireText(name, "name");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        model = AiAction.requireText(model, "model");
        // Deliberately not restricted to EFFORTS: a config file written by another client (or a
        // provider that adds a value later) must not make the profile unloadable. Unsupported
        // values degrade to "do not send the field" through apiReasoningEffort().
        reasoningEffort = AiAction.requireText(reasoningEffort, "reasoningEffort");
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /** True when {@code effort} is a value this build knows how to send. */
    public static boolean isSupportedEffort(String effort) {
        return effort != null && EFFORTS.contains(effort);
    }

    /**
     * The value to put into the {@code reasoning_effort} request field, or {@code null} when the
     * field must be omitted: unsupported values and {@code none} both degrade to omission rather
     * than failing the request.
     */
    public String apiReasoningEffort() {
        return isSupportedEffort(reasoningEffort) && !"none".equals(reasoningEffort) ? reasoningEffort : null;
    }
}
