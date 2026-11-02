package mine.coding.server;

import mine.coding.ai.spatial.BlockBox;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionObservationTest {
    @Test void paletteRunsIncludeBoundsHashAndStayWithinBudget() {
        var snapshot = RegionObservation.encode(BlockBox.ofCorners(0, 0, 0, 3, 0, 0),
                "minecraft:overworld", List.of("minecraft:air", "minecraft:stone", "minecraft:stone", "minecraft:air"), 1, 123L);
        assertEquals("minecraft:overworld", snapshot.get("dimension").getAsString());
        assertTrue(snapshot.has("bounds"));
        assertTrue(snapshot.has("snapshotHash"));
        assertTrue(snapshot.has("palette"));
        assertTrue(snapshot.has("runs"));
        assertTrue(snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= RegionObservation.MAX_RESPONSE_BYTES);
    }

    @Test void oversizedObservationIsRejectedBeforeSending() {
        var states = java.util.stream.IntStream.range(0, 4096).mapToObj(i -> "state-" + i + "-" + "x".repeat(64)).toList();
        assertThrows(IllegalArgumentException.class, () -> RegionObservation.encode(BlockBox.ofCorners(0, 0, 0, 4095, 0, 0),
                "minecraft:overworld", states, 256, 123L));
    }

    @Test void unknownStatesAreRepresentedWithoutNbtOrInventory() {
        var snapshot = RegionObservation.encode(BlockBox.ofCorners(0, 0, 0, 0, 0, 0),
                "minecraft:overworld", List.of("<unknown>"), 1, 123L);
        assertFalse(snapshot.has("nbt"));
        assertFalse(snapshot.has("inventory"));
        assertEquals("unknown", snapshot.getAsJsonArray("palette").get(0).getAsString());
    }
}
