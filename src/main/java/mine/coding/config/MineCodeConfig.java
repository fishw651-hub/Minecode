package mine.coding.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mine.coding.ai.AiProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistent client configuration, stored at config/minecode.json. */
public final class MineCodeConfig {
    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_REASONING_EFFORT = "medium";
    public static final Path CONFIG_PATH = Path.of("config", "minecode.json");

    private String endpoint;
    private String apiKey;
    private String model;
    private String reasoningEffort;
    private List<AiProfile> profiles;
    private String activeProfileId;

    public MineCodeConfig() {
        this(DEFAULT_ENDPOINT, "", DEFAULT_MODEL, DEFAULT_REASONING_EFFORT, List.of(), "");
    }

    public MineCodeConfig(String endpoint, String apiKey, String model, String reasoningEffort) {
        this(endpoint, apiKey, model, reasoningEffort, List.of(), "");
    }

    public MineCodeConfig(String endpoint, String apiKey, String model, String reasoningEffort,
                          List<AiProfile> profiles, String activeProfileId) {
        this.endpoint = requireText(endpoint, "endpoint");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = requireText(model, "model");
        this.reasoningEffort = requireText(reasoningEffort, "reasoningEffort");
        this.profiles = List.copyOf(profiles == null ? List.of() : profiles);
        this.activeProfileId = activeProfileId == null ? "" : activeProfileId;
        validateProfiles(this.profiles, this.activeProfileId);
    }

    public static MineCodeConfig load() {
        return load(CONFIG_PATH);
    }

    public static MineCodeConfig load(Path path) {
        if (!Files.exists(path)) {
            return new MineCodeConfig();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("MineCode config must be a JSON object");
            }
            JsonObject json = parsed.getAsJsonObject();
            return new MineCodeConfig(
                    stringOr(json, "endpoint", DEFAULT_ENDPOINT),
                    stringOr(json, "apiKey", ""),
                    stringOr(json, "model", DEFAULT_MODEL),
                    stringOr(json, "reasoningEffort", DEFAULT_REASONING_EFFORT),
                    readProfiles(json),
                    stringOr(json, "activeProfileId", ""));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read MineCode config at " + path, exception);
        }
    }

    public void save() {
        save(CONFIG_PATH);
    }

    public void save(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Write beside the destination and replace it in one filesystem operation so a
            // crash cannot leave a partially-written configuration file behind.
            temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
            Files.writeString(temporary, toJson());
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write MineCode config at " + path, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the original write exception, if any.
                }
            }
        }
    }

    public String endpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = requireText(endpoint, "endpoint");
    }

    public String apiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String model() {
        return model;
    }

    public void setModel(String model) {
        this.model = requireText(model, "model");
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = requireText(reasoningEffort, "reasoningEffort");
    }

    public List<AiProfile> profiles() {
        return profiles;
    }

    public void setProfiles(List<AiProfile> profiles) {
        List<AiProfile> copy = List.copyOf(profiles == null ? List.of() : profiles);
        validateProfiles(copy, activeProfileId);
        this.profiles = copy;
    }

    public void addProfile(AiProfile profile) {
        List<AiProfile> updated = new ArrayList<>(profiles);
        updated.add(profile);
        setProfiles(updated);
    }

    public String activeProfileId() {
        return activeProfileId;
    }

    public void setActiveProfileId(String activeProfileId) {
        String id = activeProfileId == null ? "" : activeProfileId;
        validateProfiles(profiles, id);
        this.activeProfileId = id;
    }

    /** Returns the selected profile, or null when no profile is active. */
    public AiProfile activeProfile() {
        if (activeProfileId.isBlank()) {
            return null;
        }
        return profiles.stream().filter(profile -> profile.id().equals(activeProfileId)).findFirst().orElse(null);
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("endpoint", endpoint);
        root.addProperty("apiKey", apiKey);
        root.addProperty("model", model);
        root.addProperty("reasoningEffort", reasoningEffort);
        root.addProperty("activeProfileId", activeProfileId);
        JsonArray profileArray = new JsonArray();
        for (AiProfile profile : profiles) {
            JsonObject item = new JsonObject();
            item.addProperty("id", profile.id());
            item.addProperty("name", profile.name());
            item.addProperty("systemPrompt", profile.systemPrompt());
            item.addProperty("model", profile.model());
            item.addProperty("reasoningEffort", profile.reasoningEffort());
            JsonArray skillArray = new JsonArray();
            profile.skills().forEach(skillArray::add);
            item.add("skills", skillArray);
            profileArray.add(item);
        }
        root.add("profiles", profileArray);
        return root.toString();
    }

    public String toRedactedString() {
        return "MineCodeConfig{endpoint='" + endpoint + "', apiKey='" + redact(apiKey)
                + "', model='" + model + "', reasoningEffort='" + reasoningEffort
                + "', profiles=" + profiles.size() + ", activeProfileId='" + activeProfileId + "'}";
    }

    private static List<AiProfile> readProfiles(JsonObject root) {
        JsonElement element = root.get("profiles");
        if (element == null) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("profiles must be an array");
        }
        List<AiProfile> profiles = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException("profiles entries must be objects");
            }
            JsonObject item = entry.getAsJsonObject();
            profiles.add(new AiProfile(
                    requiredString(item, "id"),
                    requiredString(item, "name"),
                    stringOr(item, "systemPrompt", ""),
                    requiredString(item, "model"),
                    requiredString(item, "reasoningEffort"),
                    stringArray(item, "skills")));
        }
        return profiles;
    }

    private static List<String> stringArray(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(field + " entries must be strings");
            }
            values.add(value.getAsString());
        }
        return values;
    }

    private static String stringOr(JsonObject object, String field, String fallback) {
        JsonElement element = object.get(field);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }

    private static String requiredString(JsonObject object, String field) {
        return requireText(stringOr(object, field, ""), field);
    }

    private static void validateProfiles(List<AiProfile> profiles, String activeProfileId) {
        Set<String> ids = new HashSet<>();
        for (AiProfile profile : profiles) {
            if (!ids.add(profile.id())) {
                throw new IllegalArgumentException("Profile ids must be unique: " + profile.id());
            }
        }
        if (activeProfileId != null && !activeProfileId.isBlank()
                && profiles.stream().noneMatch(profile -> profile.id().equals(activeProfileId))) {
            throw new IllegalArgumentException("Active profile does not exist: " + activeProfileId);
        }
    }

    private static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 4) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 2) + "*".repeat(value.length() - 4) + value.substring(value.length() - 2);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing or blank " + field);
        }
        return value;
    }
}
