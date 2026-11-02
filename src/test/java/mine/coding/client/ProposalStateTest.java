package mine.coding.client;

import mine.coding.ai.AiPlan;
import mine.coding.ai.spatial.BlockBox;
import mine.coding.ai.spatial.FocusRequest;
import mine.coding.ai.spatial.ProjectDefinition;
import mine.coding.ai.spatial.StageCheckpoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** C7/C8: what the console must forget once a proposal is cancelled or invalidated. */
class ProposalStateTest {
    private static BlockBox bounds() { return new BlockBox(0, 64, 0, 8, 70, 8); }

    private static AiPlan projectDraftPlan() {
        ProjectDefinition definition = new ProjectDefinition("p-1", "石屋", "盖一间石屋", "蓝图",
                "minecraft:overworld", bounds());
        StageCheckpoint checkpoint = new StageCheckpoint("planning", "等待批准项目范围", "开始读取第一个 focus", false);
        return new AiPlan("开始规划", "思考", List.of(), List.of(), List.of(), List.of(), definition,
                List.of(new FocusRequest("focus-1", "查看地面", new BlockBox(0, 64, 0, 1, 64, 1))), checkpoint);
    }

    private static AiPlan plainPlan() {
        return new AiPlan("查看一下", "思考", List.of(), List.of(), List.of(), List.of(), null,
                List.of(new FocusRequest("focus-2", "查看地面", new BlockBox(0, 64, 0, 1, 64, 1))), null);
    }

    @Test void approvedPlanIsHeldUntilTheServerEchoesItsDraftId() {
        ProposalState state = new ProposalState();
        state.offer(projectDraftPlan());
        state.draft("draft-1");
        assertTrue(state.awaitingApproval());
        assertFalse(state.confirmsDraft("draft-2"), "an unrelated draft id must not approve anything");
        assertTrue(state.confirmsDraft("draft-1"));
        assertEquals("开始规划", state.takeDraftPlan().message());
        assertFalse(state.awaitingApproval(), "the drafted plan must not be reused");
        assertNull(state.takeDraftPlan());
    }

    @Test void previewPublishesTheServerPlanIdAndKeepsTheAttentionPointers() {
        ProposalState state = new ProposalState();
        state.offer(projectDraftPlan());
        state.preview("plan-1");
        assertEquals("plan-1", state.planId());
        assertEquals(1, state.focusRequests().size(), "the attention panel needs the requested focus boxes");
        assertNotNull(state.checkpoint(), "the stage checkpoint must stay confirmable after the preview");
        assertFalse(state.awaitingApproval(), "showing a preview ends the approval wait");
    }

    @Test void clearProposalForgetsThePlanIdSoStaleButtonsCannotSendIt() {
        ProposalState state = new ProposalState();
        state.offer(projectDraftPlan());
        state.draft("draft-1");
        state.preview("plan-1");
        state.clearProposal();
        assertEquals("", state.planId(), "a cancelled round must not leave a planId behind");
        assertEquals("", state.draftId());
        assertNull(state.checkpoint());
        assertTrue(state.focusRequests().isEmpty());
        assertFalse(state.hasPlan());
    }

    @Test void invalidatingServerErrorClearsThePreviewAndReportsIt() {
        ProposalState state = new ProposalState();
        state.offer(projectDraftPlan());
        state.preview("plan-1");
        assertTrue(state.invalidatedBy("请求被拒绝：项目授权已变化，请重新生成预览"));
        assertEquals("", state.planId(), "a dead planId must not stay on screen");
        assertNull(state.checkpoint());
    }

    @Test void unrelatedServerErrorKeepsThePreviewClickable() {
        ProposalState state = new ProposalState();
        state.preview("plan-1");
        assertFalse(state.invalidatedBy("请求被拒绝：需要命令权限等级 2 才能改变或撤销世界"));
        assertFalse(state.invalidatedBy("请求被拒绝：动作已经处理"));
        assertEquals("plan-1", state.planId());
    }

    @Test void pureRuleRecognisesEveryDeadPreviewMessage() {
        assertTrue(ProposalState.invalidates("请求被拒绝：项目授权已变化，请重新生成预览"));
        assertTrue(ProposalState.invalidates("请求被拒绝：世界已变化，请重新生成预览"));
        assertTrue(ProposalState.invalidates("请求被拒绝：unknown or superseded plan"));
        assertTrue(ProposalState.invalidates("请求被拒绝：plan expired"));
        assertTrue(ProposalState.invalidates("请求被拒绝：plan dimension changed"));
        assertFalse(ProposalState.invalidates("请求被拒绝：动作已经处理"));
        assertFalse(ProposalState.invalidates(""));
        assertFalse(ProposalState.invalidates(null));
    }

    @Test void planWithoutProjectDraftNeverBlocksOnApproval() {
        ProposalState state = new ProposalState();
        state.offer(plainPlan());
        assertFalse(state.awaitingApproval());
        assertEquals(1, state.focusRequests().size());
    }
}
