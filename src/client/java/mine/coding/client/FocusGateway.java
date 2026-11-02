package mine.coding.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mine.coding.ai.spatial.FocusRequest;
import mine.coding.ai.transport.FocusReader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Client bridge for bounded, request-correlated server focus observations.
 *
 * <p>Reads are issued from the AI orchestrator thread but {@code ClientPlayNetworking.send} must run
 * on the client thread, so packets are handed to an injectable {@link #dispatcher dispatcher}
 * (the game thread by default). Every read is bounded by {@link #DEFAULT_TIMEOUT}: a server that
 * never answers used to leave the join on {@code allOf} unfinished and the console busy forever.
 */
public final class FocusGateway implements FocusReader, AutoCloseable {
    private static final int DEFAULT_MAX_CONTEXT = 8192;
    /** Upper bound for one bounded read. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);
    private final Consumer<JsonObject> sender;
    private final Consumer<Runnable> dispatcher;
    private final Duration timeout;
    private final ConcurrentMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    public FocusGateway() { this(ClientNetworking::send, ClientNetworking::execute, DEFAULT_TIMEOUT); }

    /** Sends inline; kept for tests and for callers that already run on the client thread. */
    public FocusGateway(Consumer<JsonObject> sender) { this(sender, Runnable::run, DEFAULT_TIMEOUT); }

    public FocusGateway(Consumer<JsonObject> sender, Consumer<Runnable> dispatcher) {
        this(sender, dispatcher, DEFAULT_TIMEOUT);
    }

    public FocusGateway(Consumer<JsonObject> sender, Consumer<Runnable> dispatcher, Duration timeout) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    @Override
    public CompletableFuture<String> read(FocusRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> previous = pending.put(request.id(), future);
        if (previous != null) previous.cancel(false);
        future.whenComplete((ignored, failure) -> pending.remove(request.id(), future));
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        JsonObject packet = new JsonObject();
        packet.addProperty("op", "focus");
        packet.addProperty("requestId", request.id());
        packet.addProperty("bounds", request.bounds().toJson().toString());
        packet.addProperty("purpose", request.purpose());
        Runnable delivery = () -> {
            try {
                sender.accept(packet);
            } catch (RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        };
        try {
            dispatcher.accept(delivery);
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    /** Number of reads still waiting for an answer; used to prove that timeouts clean up. */
    public int pendingReads() { return pending.size(); }

    /** Delivers a server response. Unmatched or stale responses are ignored. */
    public void accept(JsonObject response) {
        if (response == null) return;
        String requestId = text(response, "requestId");
        if (requestId.isBlank()) return;
        CompletableFuture<String> future = pending.get(requestId);
        if (future == null || future.isDone()) return;
        if ("error".equals(text(response, "event"))) {
            future.completeExceptionally(new IllegalStateException(text(response, "message")));
            return;
        }
        if (!"focus".equals(text(response, "event"))) return;
        JsonElement snapshot = response.get("snapshot");
        if (snapshot == null || snapshot.isJsonNull()) {
            future.completeExceptionally(new IllegalArgumentException("focus response missing snapshot"));
            return;
        }
        try {
            JsonObject object = snapshot.isJsonObject()
                    ? snapshot.getAsJsonObject()
                    : JsonParser.parseString(snapshot.getAsString()).getAsJsonObject();
            future.complete(snapshotText(object, DEFAULT_MAX_CONTEXT));
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
    }

    public void cancel(String requestId) {
        CompletableFuture<String> future = pending.remove(requestId);
        if (future != null) future.cancel(false);
    }

    public void cancelAll() {
        for (String requestId : List.copyOf(pending.keySet())) cancel(requestId);
    }

    @Override public void close() { cancelAll(); }

    /** Converts a server snapshot into compact bounded model context. */
    public static String snapshotText(JsonObject snapshot, int maxChars) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (maxChars < 1) throw new IllegalArgumentException("maxChars must be positive");
        StringBuilder out = new StringBuilder();
        append(out, "dimension", text(snapshot, "dimension"));
        append(out, "bounds", value(snapshot, "bounds"));
        append(out, "loadedChunks", value(snapshot, "loadedChunkCount"));
        append(out, "palette", compactArray(snapshot.get("palette")));
        append(out, "runs", compactArray(snapshot.get("runs")));
        append(out, "snapshotHash", text(snapshot, "snapshotHash"));
        String result = out.toString();
        return result.length() <= maxChars ? result : result.substring(0, maxChars);
    }

    private static void append(StringBuilder out, String key, String value) {
        if (value != null && !value.isBlank()) {
            if (out.length() > 0) out.append('\n');
            out.append(key).append('=').append(value);
        }
    }

    private static String compactArray(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonArray()) return compact(element.getAsJsonArray());
        String raw = element.getAsString();
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonArray() ? compact(parsed.getAsJsonArray()) : raw;
        } catch (RuntimeException ignored) { return raw; }
    }

    private static String compact(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement item : array) values.add(item.toString());
        return String.join(",", values);
    }

    private static String value(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? "" : element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static String text(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }
}
