package mine.coding.client.screen.components;

import mine.coding.ai.AiProfile;
import mine.coding.config.MineCodeConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Editable client profile state shared by the console and its configuration screen. */
public final class ProfilePanel {
    /** Delegates to the transport's single source of truth so the UI cannot offer an unsendable effort. */
    public static final List<String> EFFORTS = AiProfile.EFFORTS;
    private final MineCodeConfig config;
    public ProfilePanel(MineCodeConfig config) {
        this.config = config;
        if (config.profiles().isEmpty()) {
            config.addProfile(new AiProfile("builder", "建造助手", "协助设计和建造，所有世界修改必须先经用户确认。", config.model(), config.reasoningEffort(), List.of()));
            config.addProfile(new AiProfile("explorer", "世界顾问", "协助探索与解释世界状态，优先给出清楚的建议。", config.model(), "low", List.of()));
        }
        if (config.activeProfile() == null) config.setActiveProfileId(config.profiles().get(0).id());
    }
    public AiProfile active() { return config.activeProfile(); }
    public void next() {
        int index = config.profiles().indexOf(active());
        config.setActiveProfileId(config.profiles().get((index + 1) % config.profiles().size()).id());
    }
    public void create() {
        String id = UUID.randomUUID().toString();
        config.addProfile(new AiProfile(id, "档案 " + (config.profiles().size() + 1), "", config.model(), config.reasoningEffort(), List.of()));
        config.setActiveProfileId(id);
    }
    public void update(String name, String systemPrompt, String model, String effort, String skillsText) {
        AiProfile current = active();
        List<String> skills = splitSkills(skillsText);
        AiProfile changed = new AiProfile(current.id(), name, systemPrompt, model, effort, skills);
        var profiles = new ArrayList<>(config.profiles()); profiles.set(profiles.indexOf(current), changed);
        config.setProfiles(profiles); config.setModel(model); config.setReasoningEffort(effort);
    }
    public AiProfile requestProfile(boolean includeSkills) {
        AiProfile current = active();
        return new AiProfile(current.id(), current.name(), current.systemPrompt(), current.model(), current.reasoningEffort(), includeSkills ? current.skills() : List.of());
    }
    /**
     * One skill per blank-line separated block, so the editor text is the exact inverse of
     * {@link #skillsText(List)}; multi-line instructions stay one skill because saving and
     * formatting again must not change the list.
     */
    public static List<String> splitSkills(String skillsText) {
        if (skillsText == null) return List.of();
        var skills = new ArrayList<String>();
        for (String block : skillsText.split("\n\\s*\n")) {
            String trimmed = block.strip();
            if (!trimmed.isEmpty()) skills.add(trimmed);
        }
        return List.copyOf(skills);
    }
    /** Editor text for the skill list: blocks separated by one blank line, empty entries skipped. */
    public static String skillsText(List<String> skills) {
        if (skills == null || skills.isEmpty()) return "";
        var blocks = new ArrayList<String>();
        for (String skill : skills) {
            String trimmed = skill == null ? "" : skill.strip();
            if (!trimmed.isEmpty()) blocks.add(trimmed);
        }
        return String.join("\n\n", blocks);
    }
}
