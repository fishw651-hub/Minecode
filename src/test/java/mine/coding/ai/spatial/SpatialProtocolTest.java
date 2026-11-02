package mine.coding.ai.spatial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpatialProtocolTest {
    @Test
    void blockBoxNormalizesCornersAndRoundTrips() {
        BlockBox box = BlockBox.ofCorners(4, 8, -2, 1, 6, 3);
        assertEquals(1, box.x()); assertEquals(6, box.y()); assertEquals(-2, box.z());
        assertEquals(4, box.x2()); assertEquals(8, box.y2()); assertEquals(3, box.z2());
        // x spans 1..4 (4), y spans 6..8 (3), z spans -2..3 (6) -> 4 * 3 * 6.
        assertEquals(4 * 3 * 6, box.volume());
        assertEquals(box, BlockBox.fromJson(box.toJson()));
    }

    @Test
    void containmentUsesInclusiveBounds() {
        BlockBox outer = BlockBox.ofCorners(0, 0, 0, 15, 15, 15);
        assertTrue(outer.contains(BlockBox.ofCorners(0, 0, 0, 15, 15, 15)));
        assertTrue(outer.contains(15, 15, 15));
        assertFalse(outer.contains(BlockBox.ofCorners(0, 0, 0, 16, 0, 0)));
        assertFalse(outer.contains(16, 0, 0));
    }

    @Test
    void rejectsInvalidBoxesAndOverflow() {
        assertDoesNotThrow(() -> BlockBox.ofCorners(1, 1, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockBox.ofCorners(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0));
        // BlockBox only bounds each axis (<= 4096); the total-volume budget belongs to
        // FocusRequest (<= 4096) while ProjectDefinition allows up to 512 per axis and
        // therefore needs BlockBox to accept very large volumes.
        assertDoesNotThrow(() -> BlockBox.ofCorners(0, 0, 0, 16, 16, 16));
        assertThrows(IllegalArgumentException.class, () -> BlockBox.ofCorners(0, 0, 0, 4096, 0, 0));
        JsonObject malformed = JsonParser.parseString("{\"x\":0,\"y\":0,\"z\":0,\"x2\":0,\"y2\":0,\"z2\":0,\"extra\":1}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> BlockBox.fromJson(malformed));
        JsonObject fractional = JsonParser.parseString("{\"x\":1.5,\"y\":0,\"z\":0,\"x2\":1,\"y2\":0,\"z2\":0}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> BlockBox.fromJson(fractional));
    }

    @Test
    void roundTripsRequestsDefinitionsAndCheckpoint() {
        FocusRequest request = new FocusRequest("r1", "inspect entrance", BlockBox.ofCorners(0, 1, 2, 3, 4, 5));
        assertEquals(request, FocusRequest.fromJson(request.toJson()));
        ProjectDefinition project = new ProjectDefinition("p1", "Tower", "Build a tower", "layers", "minecraft:overworld", BlockBox.ofCorners(0, 0, 0, 511, 511, 511));
        assertEquals(project, ProjectDefinition.fromJson(project.toJson()));
        StageCheckpoint checkpoint = new StageCheckpoint("foundation", "Base complete", "Build walls", false);
        assertEquals(checkpoint, StageCheckpoint.fromJson(checkpoint.toJson()));
    }

    @Test
    void rejectsUnknownNullAndOverlongFields() {
        JsonObject unknown = JsonParser.parseString("{\"id\":\"r\",\"purpose\":\"p\",\"bounds\":{\"x\":0,\"y\":0,\"z\":0,\"x2\":0,\"y2\":0,\"z2\":0},\"extra\":true}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> FocusRequest.fromJson(unknown));
        assertThrows(NullPointerException.class, () -> new FocusRequest(null, "p", BlockBox.ofCorners(0,0,0,0,0,0)));
        assertThrows(IllegalArgumentException.class, () -> new FocusRequest("r", "x".repeat(1025), BlockBox.ofCorners(0,0,0,0,0,0)));
        JsonObject missing = new JsonObject(); missing.addProperty("id", "r");
        assertThrows(IllegalArgumentException.class, () -> FocusRequest.fromJson(missing));
    }
}
