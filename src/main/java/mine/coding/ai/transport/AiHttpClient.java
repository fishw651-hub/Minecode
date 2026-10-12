package mine.coding.ai.transport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mine.coding.ai.AiPlan;
import mine.coding.ai.AiProfile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Asynchronous OpenAI-compatible transport with SSE and JSON response support. */
public class AiHttpClient implements AutoCloseable {
    private final HttpClient client;
    private final Duration timeout;

    public AiHttpClient() { this(HttpClient.newHttpClient(), Duration.ofSeconds(60)); }
    public AiHttpClient(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client); this.timeout = Objects.requireNonNull(timeout);
    }

    public Exchange request(Request request, Consumer<ChatEvent> events) {
        Objects.requireNonNull(request); Objects.requireNonNull(events);
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<AiPlan> result = new CompletableFuture<>();
        String endpoint = normalizeEndpoint(request.endpoint());
        JsonObject body = requestBody(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                .header("Content-Type", "application/json");
        if (request.apiKey() != null && !request.apiKey().isBlank()) builder.header("Authorization", "Bearer " + request.apiKey());
        HttpRequest httpRequest = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        CompletableFuture<HttpResponse<java.io.InputStream>> call = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        Exchange exchange = new Exchange(result, () -> { cancelled.set(true); call.cancel(true); result.cancel(false); });
        call.whenComplete((response, failure) -> {
            if (cancelled.get() || result.isCancelled()) { close(response); return; }
            if (failure != null) { result.completeExceptionally(safeFailure(failure)); return; }
            if (response.statusCode() / 100 != 2) { close(response); result.completeExceptionally(new IOException("服务端返回 HTTP " + response.statusCode() + "，请检查配置后重试")); return; }
            events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.RUNNING, "连接已建立", null));
            try { parse(response, request, events, result, cancelled); }
            catch (Throwable error) { close(response); if (!cancelled.get()) result.completeExceptionally(error); }
        });
        return exchange;
    }

    private void parse(HttpResponse<java.io.InputStream> response, Request request, Consumer<ChatEvent> events,
                       CompletableFuture<AiPlan> result, AtomicBoolean cancelled) throws IOException {
        try (var in = response.body(); var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder(); StringBuilder data = new StringBuilder(); boolean done = false; String line;
            if (!isSse(response)) {
                String json = reader.lines().reduce("", (a, b) -> a + b);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                    if (message != null) text.append(string(message, "content"));
                }
            }
            if (!text.isEmpty()) events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.TEXT, text.toString(), null));
            while ((line = reader.readLine()) != null) {
                if (cancelled.get()) return;
                if (line.isEmpty()) { if (data.length() > 0 && processData(data.toString(), text, request, events)) { done = true; break; } data.setLength(0); continue; }
                if (line.startsWith("data:")) { data.append(line.substring(5).stripLeading()); }
            }
            if (data.length() > 0 && processData(data.toString(), text, request, events)) done = true;
            if (isSse(response) && !done) throw new IOException("流式响应提前结束，请重试");
            if (cancelled.get()) return;
            String content = text.toString();
            try {
                AiPlan plan = AiPlan.fromJson(content);
                events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.PLAN, "计划已解析", plan));
                events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.COMPLETED, "完成", null));
                result.complete(plan);
            } catch (RuntimeException error) {
                throw new IOException("模型返回内容无法解析为计划，已保留文本供查看", error);
            }
        }
    }

    private boolean processData(String raw, StringBuilder text, Request request, Consumer<ChatEvent> events) {
        String value = raw.strip();
        if (value.equals("[DONE]")) return true;
        JsonObject root;
        try { root = JsonParser.parseString(value).getAsJsonObject(); } catch (RuntimeException ignored) { return false; }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) return false;
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject() ? choice.getAsJsonObject("delta") : choice.getAsJsonObject("message");
        if (delta == null) return false;
        String reasoning = string(delta, "reasoning_content");
        if (reasoning.isEmpty() && delta.has("reasoning") && delta.get("reasoning").isJsonPrimitive()) reasoning = delta.get("reasoning").getAsString();
        if (!reasoning.isEmpty()) events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.REASONING, reasoning, null));
        String content = string(delta, "content");
        if (!content.isEmpty()) { text.append(content); events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.TEXT, content, null)); }
        return false;
    }

    private static JsonObject requestBody(Request request) {
        JsonObject root = new JsonObject(); root.addProperty("model", request.profile().model()); root.addProperty("stream", request.stream());
        JsonArray messages = new JsonArray();
        for (ChatMessage message : request.history()) { JsonObject item = new JsonObject(); item.addProperty("role", message.role()); item.addProperty("content", message.content()); messages.add(item); }
        StringBuilder context = new StringBuilder("档案：").append(request.profile().name()).append('\n').append(request.profile().systemPrompt()).append("\n技能：").append(String.join("\n", request.profile().skills())).append("\n世界上下文：").append(request.worldContext());
        if (!request.task().isBlank()) context.append("\n任务：").append(request.task());
        JsonObject system = new JsonObject(); system.addProperty("role", "system"); system.addProperty("content", context.toString());
        JsonArray ordered = new JsonArray(); ordered.add(system); for (JsonElement message : messages) ordered.add(message); root.add("messages", ordered);
        String effort = request.profile().reasoningEffort(); if (effort != null && List.of("none", "low", "medium", "high").contains(effort) && !effort.equals("none")) root.addProperty("reasoning_effort", effort);
        return root;
    }
    private static String normalizeEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.strip(); if (value.endsWith("/chat/completions")) return value;
        if (value.endsWith("/v1")) return value + "/chat/completions"; return value.replaceAll("/$", "") + "/v1/chat/completions";
    }
    private static String string(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
    private static boolean isSse(HttpResponse<?> response) { return response.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("text/event-stream"); }
    private static void close(HttpResponse<java.io.InputStream> response) { if (response != null) try { response.body().close(); } catch (IOException ignored) {} }
    private static Throwable safeFailure(Throwable failure) { Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure; return new IOException(cause instanceof java.net.http.HttpTimeoutException ? "请求超时，请重试" : "网络请求失败，请检查网络后重试"); }
    @Override public void close() {}

    public record Request(String endpoint, String apiKey, AiProfile profile, List<ChatMessage> history, String worldContext,
                          String agentId, String role, String task, boolean stream) {
        public Request { history = List.copyOf(history == null ? List.of() : history); worldContext = worldContext == null ? "" : worldContext; agentId = agentId == null ? "main" : agentId; role = role == null ? "主Agent" : role; task = task == null ? "" : task; }
        public boolean allowDelegations() { return !agentId.startsWith("child-") && !agentId.equals("summary"); }
    }
    public static final class Exchange {
        private final CompletableFuture<AiPlan> future; private final Runnable cancel;
        Exchange(CompletableFuture<AiPlan> future, Runnable cancel) { this.future = future; this.cancel = cancel; }
        public CompletableFuture<AiPlan> future() { return future; }
        public void cancel() { cancel.run(); }
    }
}
