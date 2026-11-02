package mine.coding.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mine.coding.ai.spatial.BlockBox;
import mine.coding.ai.spatial.ProjectDefinition;
import mine.coding.ai.spatial.StageCheckpoint;

import java.util.Objects;
import java.util.Optional;

/** Pure projection of a server context used by the client console and AI prompt. */
public record ProjectContext(
        String worldContext,
        String dimension,
        double x,
        double y,
        double z,
        String projectDisplay,
        Optional<ProjectDefinition> project,
        Optional<BlockBox> approvedBounds,
        Optional<StageCheckpoint> checkpoint,
        JsonElement memory,
        JsonElement skills,
        JsonElement limits) {

    public ProjectContext {
        worldContext = Objects.requireNonNullElse(worldContext, "{}");
        dimension = Objects.requireNonNullElse(dimension, "");
        projectDisplay = Objects.requireNonNullElse(projectDisplay, "");
        project = project == null ? Optional.empty() : project;
        approvedBounds = approvedBounds == null ? Optional.empty() : approvedBounds;
        checkpoint = checkpoint == null ? Optional.empty() : checkpoint;
        memory = memory == null ? com.google.gson.JsonNull.INSTANCE : memory.deepCopy();
        skills = skills == null ? com.google.gson.JsonNull.INSTANCE : skills.deepCopy();
        limits = limits == null ? com.google.gson.JsonNull.INSTANCE : limits.deepCopy();
    }

    public static ProjectContext fromJson(JsonObject source) {
        Objects.requireNonNull(source, "source");
        JsonObject filtered = source.deepCopy();
        filtered.remove("chatHistory");
        filtered.remove("auditLog");
        filtered.remove("event");
        filtered.remove("message");

        String dimension = primitiveText(source, "dimension");
        double x = number(source, "x"), y = number(source, "y"), z = number(source, "z");
        JsonElement memory = source.get("memory");
        JsonElement skills = source.get("skills");
        JsonElement limits = source.get("limits");

        ProjectDefinition definition = null;
        StageCheckpoint checkpoint = null;
        String display = "无已批准项目";
        JsonElement projectValue = source.get("project");
        if (projectValue != null && projectValue.isJsonObject()) {
            JsonObject projectContainer = projectValue.getAsJsonObject();
            JsonElement definitionValue = projectContainer.get("project");
            if (definitionValue != null && definitionValue.isJsonObject()) {
                try {
                    definition = ProjectDefinition.fromJson(definitionValue.getAsJsonObject());
                    display = definition.name() + " · " + definition.goal();
                } catch (RuntimeException ignored) {
                    display = "项目数据无效";
                }
            }
            JsonElement checkpointValue = projectContainer.get("checkpoint");
            if (checkpointValue != null && checkpointValue.isJsonObject()) {
                try { checkpoint = StageCheckpoint.fromJson(checkpointValue.getAsJsonObject()); }
                catch (RuntimeException ignored) { checkpoint = null; }
            }
        }
        if (definition != null) filtered.add("project", projectValue.deepCopy());
        return new ProjectContext(filtered.toString(), dimension, x, y, z, display,
                Optional.ofNullable(definition), definition == null ? Optional.empty() : Optional.of(definition.bounds()),
                Optional.ofNullable(checkpoint), memory, skills, limits);
    }

    public static ProjectContext fromJson(String raw) {
        return fromJson(JsonParser.parseString(raw).getAsJsonObject());
    }

    private static String primitiveText(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static double number(JsonObject object, String field) {
        try { return object.get(field).getAsDouble(); }
        catch (RuntimeException ignored) { return 0; }
    }
}
