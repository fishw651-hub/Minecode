package mine.coding.server;

import com.google.gson.JsonObject;
import mine.coding.ai.spatial.BlockBox;
import mine.coding.ai.spatial.ProjectDefinition;
import mine.coding.ai.spatial.StageCheckpoint;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProjectStoreTest {
    private static ProjectDefinition definition(String dimension) {
        return new ProjectDefinition("p", "桥", "goal", "blueprint", dimension, BlockBox.ofCorners(0, 0, 0, 3, 3, 3));
    }

    @Test void approvedProjectsAreIsolatedPerPlayer() throws Exception {
        Path root = Files.createTempDirectory("minecode-projects-");
        ProjectStore store = new ProjectStore(root);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.saveApproved(a, definition("minecraft:overworld"), 4,
                new StageCheckpoint("s1", "done", "next", false));
        JsonObject saved = store.load(a);
        assertEquals("p", saved.getAsJsonObject("project").get("id").getAsString());
        assertEquals(4, saved.get("revision").getAsInt());
        assertEquals(4, ProjectStore.revision(saved));
        assertTrue(store.load(b).get("project").isJsonNull());
    }

    @Test void emptyProjectStartsAtRevisionZero() throws Exception {
        Path root = Files.createTempDirectory("minecode-project-revision-");
        assertEquals(0, ProjectStore.revision(new ProjectStore(root).load(UUID.randomUUID())));
    }

    @Test void corruptProjectRefusesLoadAndSave() throws Exception {
        Path root = Files.createTempDirectory("minecode-project-corrupt-");
        ProjectStore store = new ProjectStore(root);
        UUID id = UUID.randomUUID();
        Files.createDirectories(store.path(id).getParent());
        Files.writeString(store.path(id), "{\"revision\":\"bad\"}");
        assertThrows(java.io.IOException.class, () -> store.load(id));
        assertThrows(java.io.IOException.class, () -> store.saveApproved(id, definition("minecraft:overworld"), 1,
                new StageCheckpoint("s", "summary", "next", false)));
        assertEquals("{\"revision\":\"bad\"}", Files.readString(store.path(id)));
    }
}
