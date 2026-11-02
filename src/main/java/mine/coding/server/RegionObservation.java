package mine.coding.server;

import com.google.gson.*;
import mine.coding.ai.spatial.BlockBox;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Read-only compact block-state observation. It never includes block entities or inventories. */
public final class RegionObservation {
    public static final int MAX_VOLUME = 4096;
    public static final int MAX_RESPONSE_BYTES = 48 * 1024;
    private RegionObservation() {}

    public static JsonObject encode(BlockBox bounds, String dimension, List<String> states,
                                    int loadedChunkCount, long readTimestamp) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(states, "states");
        if (bounds.volume() > MAX_VOLUME || states.size() != bounds.volume()) throw new IllegalArgumentException("observation volume mismatch or exceeds limit");
        if (loadedChunkCount < 0) throw new IllegalArgumentException("invalid loaded chunk count");
        LinkedHashMap<String, Integer> paletteMap = new LinkedHashMap<>();
        List<Integer> indexes = new ArrayList<>(states.size());
        for (String raw : states) {
            String state = normalize(raw);
            indexes.add(paletteMap.computeIfAbsent(state, ignored -> paletteMap.size()));
        }
        JsonArray palette = new JsonArray(); paletteMap.keySet().forEach(palette::add);
        JsonArray runs = new JsonArray();
        for (int i = 0; i < indexes.size();) {
            int state = indexes.get(i), length = 1;
            while (i + length < indexes.size() && indexes.get(i + length) == state) length++;
            JsonObject run = new JsonObject(); run.addProperty("state", state); run.addProperty("length", length); runs.add(run); i += length;
        }
        JsonObject result = new JsonObject();
        result.add("bounds", bounds.toJson()); result.addProperty("dimension", dimension); result.addProperty("readTimestamp", readTimestamp);
        result.addProperty("snapshotHash", hash(states)); result.addProperty("loadedChunkCount", loadedChunkCount);
        result.add("palette", palette); result.add("runs", runs);
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("observation exceeds response byte limit; shrink focus window");
        return result;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("<unknown>")) return "unknown";
        return raw.length() > 1024 ? raw.substring(0, 1024) : raw;
    }
    private static String hash(List<String> states) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String state : states) { byte[] bytes = normalize(state).getBytes(StandardCharsets.UTF_8); digest.update(bytes); digest.update((byte) 0); }
            StringBuilder out = new StringBuilder(64); for (byte b : digest.digest()) out.append(String.format("%02x", b)); return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
