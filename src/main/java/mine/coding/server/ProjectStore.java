package mine.coding.server;

import com.google.gson.*;
import mine.coding.ai.spatial.ProjectDefinition;
import mine.coding.ai.spatial.StageCheckpoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Atomic per-player project authorization store. */
public final class ProjectStore {
    public static final int MAX_FILE_BYTES = 1_048_576;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private final Path directory;

    public ProjectStore(Path worldRoot) { directory = worldRoot.resolve("data").resolve("minecode").resolve("projects"); }
    public Path path(UUID player) { return directory.resolve(player + ".json"); }

    public synchronized JsonObject load(UUID player) throws IOException {
        Objects.requireNonNull(player, "player");
        Path file = path(player);
        if (!Files.exists(file)) return empty();
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("project file exceeds size limit");
            JsonObject value = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            validate(value);
            return value;
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("corrupt project; refusing to overwrite", e); }
    }

    public synchronized void saveApproved(UUID player, ProjectDefinition project, int revision,
                                           StageCheckpoint checkpoint) throws IOException {
        Objects.requireNonNull(project, "project");
        if (revision < 0) throw new IllegalArgumentException("negative revision");
        JsonObject value = empty();
        value.add("project", project.toJson());
        value.addProperty("revision", revision);
        value.add("checkpoint", checkpoint == null ? JsonNull.INSTANCE : checkpoint.toJson());
        save(player, value);
    }

    public synchronized int updateCheckpoint(UUID player, StageCheckpoint checkpoint) throws IOException {
        Objects.requireNonNull(checkpoint, "checkpoint");
        JsonObject value = load(player);
        if (value.get("project").isJsonNull()) throw new IllegalArgumentException("no approved project");
        int next = revision(value) + 1;
        value.add("checkpoint", checkpoint.toJson());
        value.addProperty("revision", next);
        save(player, value);
        return next;
    }

    /** Returns the validated authorization revision stored with a player project. */
    public static int revision(JsonObject value) {
        Objects.requireNonNull(value, "value");
        JsonElement element = value.get("revision");
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("invalid project revision");
        }
        return element.getAsInt();
    }

    public synchronized void save(UUID player, JsonObject value) throws IOException {
        Objects.requireNonNull(player, "player");
        if (Files.exists(path(player))) load(player);
        validate(value);
        byte[] bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("project file exceeds size limit");
        Files.createDirectories(directory);
        Path temp = directory.resolve(player + ".json.tmp-" + UUID.randomUUID());
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (var channel = FileChannelHolder.open(temp)) { channel.force(true); }
            Files.move(temp, path(player), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    public static JsonObject empty() {
        JsonObject value = new JsonObject();
        value.add("project", JsonNull.INSTANCE); value.addProperty("revision", 0); value.add("checkpoint", JsonNull.INSTANCE);
        return value;
    }

    private static void validate(JsonObject value) throws IOException {
        try {
            if (value == null || !value.keySet().equals(Set.of("project", "revision", "checkpoint"))) throw new IllegalArgumentException("project schema mismatch");
            JsonElement revision = value.get("revision");
            if (!revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber() || !revision.getAsString().matches("[0-9]+")) throw new IllegalArgumentException("invalid revision");
            Integer.parseInt(revision.getAsString());
            if (!value.get("project").isJsonNull()) ProjectDefinition.fromJson(value.getAsJsonObject("project"));
            if (!value.get("checkpoint").isJsonNull()) StageCheckpoint.fromJson(value.getAsJsonObject("checkpoint"));
        } catch (RuntimeException e) { throw new IOException("invalid project schema", e); }
    }

    private static final class FileChannelHolder implements AutoCloseable {
        private final java.nio.channels.FileChannel channel;
        private FileChannelHolder(java.nio.channels.FileChannel channel) { this.channel = channel; }
        static FileChannelHolder open(Path path) throws IOException { return new FileChannelHolder(java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE)); }
        void force(boolean metadata) throws IOException { channel.force(metadata); }
        public void close() throws IOException { channel.close(); }
    }
}
