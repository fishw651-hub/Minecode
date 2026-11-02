package mine.coding.ai;

import mine.coding.config.MineCodeConfig;
import mine.coding.ai.spatial.BlockBox;
import mine.coding.ai.spatial.FocusRequest;
import mine.coding.ai.spatial.ProjectDefinition;
import mine.coding.ai.spatial.StageCheckpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPlanTest {
    @Test
    void roundTripsOptionalProjectAttentionAndCheckpointWhileOldJsonStaysValid() {
        ProjectDefinition project = new ProjectDefinition("p1", "塔", "目标", "blueprint", "minecraft:overworld",
                BlockBox.ofCorners(0, 64, 0, 3, 67, 3));
        FocusRequest focus = new FocusRequest("f1", "检查地基", BlockBox.ofCorners(0, 64, 0, 1, 65, 1));
        StageCheckpoint checkpoint = new StageCheckpoint("foundation", "已完成地基", "建墙", false);
        AiPlan plan = new AiPlan("ok", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(new AiPlan.Delegation("d", "builder", "build", focus)), project,
                java.util.List.of(focus), checkpoint);

        AiPlan reparsed = AiPlan.fromJson(plan.toJson());

        assertEquals(project, reparsed.projectDraft());
        assertEquals(java.util.List.of(focus), reparsed.attentionRequests());
        assertEquals(checkpoint, reparsed.checkpoint());
        assertEquals(focus, reparsed.delegations().getFirst().focus());
        assertTrue(AiPlan.fromJson("{\"actions\":[]}").attentionRequests().isEmpty());
    }
    @Test
    void roundTripsThreeActionTypesAndOptionalDelegations() {
        String json = """
                {
                  "message": "Done",
                  "thoughtSummary": "Place a block, fill an area, then run a command.",
                  "actions": [
                    {"id":"a1","type":"PLACE_BLOCK","description":"Place stone","risk":"low","payload":{"x":1,"y":64,"z":-2,"block":"minecraft:stone"}},
                    {"id":"a2","type":"FILL","description":"Fill a room","risk":"medium","payload":{"x":1,"y":60,"z":-2,"x2":4,"y2":63,"z2":2,"block":"minecraft:glass"}},
                    {"id":"a3","type":"RUN_COMMAND","description":"Set time","risk":"high","payload":{"command":"time set day"}}
                  ],
                  "memoryDrafts": ["The player prefers stone."],
                  "skillDrafts": [{"id":"s1","name":"Builder","description":"Build neatly","instructions":"Use symmetry."}],
                  "delegations": [{"id":"d1","role":"planner","task":"Check the room dimensions."}]
                }
                """;

        AiPlan plan = AiPlan.fromJson(json);
        AiPlan reparsed = AiPlan.fromJson(plan.toJson());

        assertEquals("Done", reparsed.message());
        assertEquals(3, reparsed.actions().size());
        assertEquals(AiAction.Type.PLACE_BLOCK, reparsed.actions().get(0).type());
        assertEquals(AiAction.Type.FILL, reparsed.actions().get(1).type());
        assertEquals(AiAction.Type.RUN_COMMAND, reparsed.actions().get(2).type());
        assertEquals("minecraft:stone", reparsed.actions().get(0).payload().get("block").getAsString());
        assertEquals(1, reparsed.memoryDrafts().size());
        assertEquals("Builder", reparsed.skillDrafts().get(0).name());
        assertEquals("planner", reparsed.delegations().get(0).role());
    }

    @Test
    void rejectsMissingActions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AiPlan.fromJson("{\"message\":\"No actions\"}"));

        assertTrue(error.getMessage().contains("actions"));
    }

    @Test
    void rejectsUnknownActionTypeWithActionId() {
        String json = """
                {"actions":[{"id":"bad-action","type":"TELEPORT","payload":{}}]}
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AiPlan.fromJson(json));

        assertTrue(error.getMessage().contains("bad-action"));
    }

    @Test
    void rejectsMissingBlockCoordinatesWithActionId() {
        String json = """
                {"actions":[{"id":"missing-z","type":"PLACE_BLOCK","description":"放置石头","risk":"low","payload":{"x":1,"y":64,"block":"minecraft:stone"}}]}
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AiPlan.fromJson(json));

        assertTrue(error.getMessage().contains("missing-z"));
        assertTrue(error.getMessage().contains("z"));
    }

    @Test
    void redactsApiKeyButKeepsItsFirstAndLastTwoCharacters() {
        String apiKey = "sk-fixture-dummy-secret";
        MineCodeConfig config = new MineCodeConfig(
                "https://api.openai.com/v1/chat/completions", apiKey, "gpt-4o-mini", "medium");

        String redacted = config.toRedactedString();

        assertFalse(redacted.contains(apiKey));
        assertTrue(redacted.contains("sk"));
        assertTrue(redacted.contains("et"));
    }
}
