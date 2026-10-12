package mine.coding.ai;

/** A proposed skill that can be reviewed before being added to a profile. */
public record SkillDraft(String id, String name, String description, String instructions) {
    public SkillDraft {
        id = AiAction.requireText(id, "id");
        name = AiAction.requireText(name, "name");
        description = AiAction.requireText(description, "description");
        instructions = AiAction.requireText(instructions, "instructions");
    }
}
