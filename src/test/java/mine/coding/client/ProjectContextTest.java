package mine.coding.client;

import com.google.gson.JsonObject;
import mine.coding.ai.spatial.BlockBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectContextTest {
    @Test
    void filtersConversationAndAuditFromWorldContext() {
        JsonObject context = new JsonObject();
        context.addProperty("event", "context");
        context.addProperty("dimension", "minecraft:overworld");
        context.addProperty("x", 1); context.addProperty("y", 64); context.addProperty("z", 2);
        context.addProperty("chatHistory", "secret chat");
        context.addProperty("auditLog", "secret audit");
        context.addProperty("memory", "[\"remember\"]");
        context.addProperty("skills", "[]");
        JsonObject project = new JsonObject();
        JsonObject definition = new JsonObject();
        definition.addProperty("id", "p1"); definition.addProperty("name", "塔"); definition.addProperty("goal", "建造");
        definition.addProperty("blueprint", "石头"); definition.addProperty("dimension", "minecraft:overworld");
        JsonObject bounds = new BlockBox(0, 60, 0, 2, 64, 2).toJson(); definition.add("bounds", bounds);
        project.add("project", definition); project.addProperty("revision", 3);
        JsonObject checkpoint = new JsonObject(); checkpoint.addProperty("stage", "地基"); checkpoint.addProperty("summary", "完成"); checkpoint.addProperty("nextTask", "墙"); checkpoint.addProperty("complete", false);
        project.add("checkpoint", checkpoint); context.add("project", project);

        ProjectContext parsed = ProjectContext.fromJson(context);
        assertFalse(parsed.worldContext().contains("secret chat"));
        assertFalse(parsed.worldContext().contains("secret audit"));
        assertTrue(parsed.worldContext().contains("remember"));
        assertEquals("塔 · 建造", parsed.projectDisplay());
        assertEquals(new BlockBox(0, 60, 0, 2, 64, 2), parsed.approvedBounds().orElseThrow());
        assertEquals("地基", parsed.checkpoint().orElseThrow().stage());
    }
}
