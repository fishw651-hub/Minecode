package mine.coding.client;

import mine.coding.ai.AiProfile;
import mine.coding.client.screen.components.ProfilePanel;
import mine.coding.config.MineCodeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** C5: the skills editor must round-trip the list instead of merging it into one entry. */
class ProfilePanelTest {
    private static ProfilePanel panel() { return new ProfilePanel(new MineCodeConfig()); }

    @Test void savingTheSkillsEditorKeepsEverySkill() {
        ProfilePanel panel = panel();
        panel.update("建造助手", "提示", "gpt-4o-mini", "medium", "先查看地面\n再查看地基\n\n最后汇报");
        assertEquals(List.of("先查看地面\n再查看地基", "最后汇报"), panel.active().skills(),
                "N skills must not be flattened into one entry");
    }

    @Test void savingWhatTheEditorShowsIsIdempotent() {
        ProfilePanel panel = panel();
        List<String> skills = List.of("先查看地面\n再查看地基", "最后汇报");
        panel.update("建造助手", "提示", "gpt-4o-mini", "medium", ProfilePanel.skillsText(skills));
        assertEquals(skills, panel.active().skills());
        for (int round = 0; round < 3; round++) {
            panel.update("建造助手", "提示", "gpt-4o-mini", "medium", ProfilePanel.skillsText(panel.active().skills()));
            assertEquals(skills, panel.active().skills(), "round " + round + " changed the skill list");
        }
    }

    @Test void blankSkillTextClearsTheList() {
        ProfilePanel panel = panel();
        panel.update("建造助手", "提示", "gpt-4o-mini", "medium", "一个技能");
        assertEquals(List.of("一个技能"), panel.active().skills());
        panel.update("建造助手", "提示", "gpt-4o-mini", "medium", "  \n\n \n");
        assertEquals(List.of(), panel.active().skills());
    }

    @Test void blankLinesSeparateSkillsAndEmptyBlocksAreDropped() {
        assertEquals(List.of("a", "b"), ProfilePanel.splitSkills("a\n\n\nb\n\n"));
        assertEquals(List.of(), ProfilePanel.splitSkills(""));
        assertEquals(List.of(), ProfilePanel.splitSkills(null));
        assertEquals(List.of("a\nb"), ProfilePanel.splitSkills("a\nb"),
                "a single multi-line skill must stay one skill");
    }

    @Test void formattingAHalfFilledListSkipsEmptyEntries() {
        assertEquals("a\n\nb", ProfilePanel.skillsText(List.of("a", " ", "b")));
        assertEquals("", ProfilePanel.skillsText(List.of()));
        assertEquals("", ProfilePanel.skillsText(null));
    }

    @Test void theEditedProfileIsWhatTheRequestUses() {
        ProfilePanel panel = panel();
        panel.update("建造助手", "提示", "gpt-4o-mini", "high", "技能一\n\n技能二");
        assertEquals(List.of("技能一", "技能二"), panel.requestProfile(true).skills());
        assertEquals(List.of(), panel.requestProfile(false).skills());
        assertEquals("high", panel.requestProfile(true).reasoningEffort());
    }

    @Test void theEffortCycleIsTheTransportsSingleSourceOfTruth() {
        // The UI must offer exactly the efforts the transport can send, not a private copy.
        assertEquals(AiProfile.EFFORTS, ProfilePanel.EFFORTS);
        assertTrue(ProfilePanel.EFFORTS.contains("medium"));
    }
}
