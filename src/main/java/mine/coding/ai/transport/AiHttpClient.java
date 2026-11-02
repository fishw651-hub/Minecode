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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Asynchronous OpenAI-compatible transport with SSE and JSON response support. */
public class AiHttpClient implements AutoCloseable {
    /**
     * U1: hard cap on the assistant text accumulated from a single response.
     *
     * <p>Value rationale: a legitimate plan is a few KiB - the largest realistic response is one
     * plan object with at most 3 delegations, at most 3 attention requests and a few KiB of prose,
     * far below 32 KiB. 512 KiB therefore leaves more than an order of magnitude of headroom for
     * verbose providers that stream long prose around the JSON, while bounding the worst-case
     * retention to roughly 1 MiB of heap (Java strings are UTF-16). It is small enough that a
     * runaway or hostile endpoint cannot use a 60s stream as an unbounded memory sink, and large
     * enough that every existing streaming test and plausible real plan stays untouched.</p>
     */
    static final int MAX_RESPONSE_CHARS = 512 * 1024;

    /**
     * U1: hard cap on one SSE event, applied both to a single physical line (a stream may never
     * emit its blank-line terminator, so {@code BufferedReader.readLine} would otherwise buffer
     * forever) and to the accumulated {@code data:} buffer of one event. One legitimate delta is a
     * few hundred bytes, so 64 KiB is ~100x headroom while still bounding a malformed stream.
     */
    static final int MAX_EVENT_CHARS = 64 * 1024;

    private final HttpClient client;
    private final Duration timeout;
    private final Set<Exchange> active = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private static final ScheduledExecutorService DEADLINES = Executors.newScheduledThreadPool(1, r -> { Thread t = new Thread(r, "minecode-ai-deadline"); t.setDaemon(true); return t; });

    public AiHttpClient() { this(HttpClient.newHttpClient(), Duration.ofSeconds(60)); }
    public AiHttpClient(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client); this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
    }

    public Exchange request(Request request, Consumer<ChatEvent> events) {
        Objects.requireNonNull(request); Objects.requireNonNull(events);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<java.io.InputStream> bodyRef = new AtomicReference<>();
        CompletableFuture<AiPlan> result = new CompletableFuture<>();
        Consumer<ChatEvent> guarded = event -> { if (!cancelled.get() && !result.isDone()) try { events.accept(event); } catch (RuntimeException ignored) {} };
        String endpoint = normalizeEndpoint(request.endpoint());
        JsonObject body = requestBody(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                .header("Content-Type", "application/json");
        if (request.apiKey() != null && !request.apiKey().isBlank()) builder.header("Authorization", "Bearer " + request.apiKey());
        HttpRequest httpRequest = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        CompletableFuture<HttpResponse<java.io.InputStream>> call = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        Exchange exchange = new Exchange(result, () -> { cancelled.set(true); call.cancel(true); close(bodyRef.getAndSet(null)); result.cancel(false); });
        active.add(exchange);
        var deadline = DEADLINES.schedule(() -> { if (result.completeExceptionally(new ApiException("请求超时，请重试"))) { cancelled.set(true); close(bodyRef.getAndSet(null)); call.cancel(true); } }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        result.whenComplete((plan, error) -> { deadline.cancel(false); active.remove(exchange); if (result.isCancelled()) exchange.cancel(); });
        call.whenCompleteAsync((response, failure) -> {
            if (cancelled.get() || result.isCancelled()) { close(response); return; }
            if (failure != null) { result.completeExceptionally(safeFailure(failure)); return; }
            bodyRef.set(response.body());
            if (cancelled.get() || result.isDone()) { close(bodyRef.getAndSet(null)); return; }
            if (response.statusCode() / 100 != 2) { close(response); result.completeExceptionally(new ApiException(statusReason(response.statusCode()))); return; }
            guarded.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.RUNNING, "连接已建立", null));
            try { parse(response, request, guarded, result, cancelled); }
            catch (Exception error) { close(response); if (!cancelled.get()) result.completeExceptionally(error instanceof ApiException ? error : new ApiException("响应解析失败，已保留显示文本，请重试")); }
            finally { bodyRef.set(null); }
        }, readers);
        return exchange;
    }

    private void parse(HttpResponse<java.io.InputStream> response, Request request, Consumer<ChatEvent> events,
                       CompletableFuture<AiPlan> result, AtomicBoolean cancelled) throws IOException {
        boolean streaming = isSse(response);
        // U1: for SSE the raw reader is wrapped so that a single physical line can never grow past
        // MAX_EVENT_CHARS (a stream without its blank-line terminator would otherwise be buffered in
        // full by BufferedReader before the data accumulator is checked at all). Non-SSE bodies are
        // bounded as a whole by readBoundedText.
        try (var in = response.body();
             var reader = new java.io.BufferedReader(streaming
                     ? new LineLimitReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), MAX_EVENT_CHARS)
                     : new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder(); StringBuilder data = new StringBuilder(); boolean done = false;
            if (!streaming) {
                // U1: the old `reader.lines().reduce("", (a, b) -> a + b)` both buffered the whole
                // body without a bound and re-copied the accumulated prefix per line (quadratic).
                String json = readBoundedText(reader);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                    if (message != null) {
                        String reasoning = string(message, "reasoning_content");
                        if (!reasoning.isEmpty()) events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.REASONING, reasoning, null));
                        text.append(string(message, "content"));
                        if (text.length() > MAX_RESPONSE_CHARS) throw new ApiException(oversizedBody());
                    }
                }
                if (!text.isEmpty()) events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.TEXT, text.toString(), null));
            } else {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.get()) return;
                    if (line.isEmpty()) { if (data.length() > 0 && processData(data.toString(), text, request, events)) { done = true; break; } data.setLength(0); continue; }
                    if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        String part = line.substring(5); data.append(part.startsWith(" ") ? part.substring(1) : part);
                        if (data.length() > MAX_EVENT_CHARS) throw new ApiException(oversizedEvent());
                    }
                }
                if (data.length() > 0 && processData(data.toString(), text, request, events)) done = true;
            }
            if (cancelled.get()) return;
            // O5: many OpenAI-compatible endpoints terminate a stream with finish_reason and a
            // closed connection instead of the optional "[DONE]" sentinel. Only treat a missing
            // sentinel as a broken stream when the accumulated text cannot be parsed into a plan.
            String content = extractPlanJson(text.toString());
            AiPlan plan = parsePlan(content);
            if (streaming && !done && plan == null) throw new ApiException("流式响应提前结束，请重试");
            if (plan == null) throw new ApiException("模型返回内容无法解析为计划，已保留文本供查看，请重试");
            events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.COMPLETED, "完成", null));
            result.complete(plan);
        }
    }

    private boolean processData(String raw, StringBuilder text, Request request, Consumer<ChatEvent> events) throws ApiException {
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
        if (!content.isEmpty()) {
            // U1: the chunk that would cross the cap is dropped so the retained buffer never exceeds
            // it; everything already received stays visible through the TEXT events above.
            if (text.length() + content.length() > MAX_RESPONSE_CHARS) throw new ApiException(oversizedBody());
            text.append(content); events.accept(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.TEXT, content, null));
        }
        return false;
    }

    /** Reads a whole (non-SSE) body while enforcing {@link #MAX_RESPONSE_CHARS}. */
    private static String readBoundedText(java.io.Reader reader) throws IOException {
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            body.append(buffer, 0, read);
            if (body.length() > MAX_RESPONSE_CHARS) throw new ApiException(oversizedBody());
        }
        return body.toString();
    }

    private static String oversizedBody() { return "模型响应超过体积上限（" + (MAX_RESPONSE_CHARS / 1024) + " KiB），已中止请求并保留已收到文本，请重试"; }
    private static String oversizedEvent() { return "流式响应单条数据超过上限（" + (MAX_EVENT_CHARS / 1024) + " KiB），已中止请求并保留已收到文本，请重试"; }

    /**
     * U1: bounds one physical SSE line. {@code BufferedReader.readLine()} keeps its internal
     * buffer growing until it sees a terminator, so checking {@code data.length()} after the line
     * arrives is too late for a stream that never sends a blank line. The counter resets on CR/LF.
     */
    private static final class LineLimitReader extends java.io.FilterReader {
        private final int limit;
        private int current;
        LineLimitReader(java.io.Reader source, int limit) { super(source); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = super.read(); if (value >= 0) count((char) value); return value;
        }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            for (int i = 0; i < read; i++) count(buffer[offset + i]);
            return read;
        }
        private void count(char value) throws ApiException {
            if (value == '\r' || value == '\n') { current = 0; return; }
            if (++current > limit) throw new ApiException(oversizedEvent());
        }
    }

    /**
     * O6: models often wrap the JSON object in a Markdown fence and/or add prose around it. Strip
     * ``` / ```json fences, then reduce the text to the first '{' ... last '}' span so that only the
     * plan object itself reaches the parser.
     */
    static String extractPlanJson(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            text = firstLineEnd < 0 ? "" : text.substring(firstLineEnd + 1);
            int closing = text.lastIndexOf("```");
            if (closing >= 0) text = text.substring(0, closing);
            text = text.strip();
        }
        int start = text.indexOf('{'); int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) text = text.substring(start, end + 1);
        return text.strip();
    }

    /** Returns the parsed plan, or {@code null} when the text is not a plan (text stays available to the caller). */
    private static AiPlan parsePlan(String candidate) {
        try { return AiPlan.fromJson(candidate); } catch (RuntimeException error) { return null; }
    }

    private static JsonObject requestBody(Request request) {
        JsonObject root = new JsonObject(); root.addProperty("model", request.profile().model()); root.addProperty("stream", request.stream());
        JsonArray messages = new JsonArray();
        for (ChatMessage message : request.history()) { JsonObject item = new JsonObject(); item.addProperty("role", message.role()); item.addProperty("content", message.content()); messages.add(item); }
        StringBuilder context = new StringBuilder("你是 MineCode AI。档案：").append(request.profile().name()).append('\n').append(request.profile().systemPrompt()).append("\n技能：").append(String.join("\n", request.profile().skills())).append("\n世界上下文：").append(request.worldContext());
        context.append("\nagentId=").append(request.agentId()).append(" role=").append(request.role()).append(" depth=").append(request.allowDelegations() ? 0 : 1)
                .append(" allowDelegations=").append(request.allowDelegations());
        // O7: the parser demands exact key sets for every nested object (BlockBox.requireFields
        // compares the object size), so the prompt must spell out each nested object completely;
        // otherwise one extra or missing field makes the whole package unparseable.
        context.append("\n请仅返回一个 JSON 对象，schema 为 {message:string,thoughtSummary:string,actions:[{id:string,type:PLACE_BLOCK|FILL|RUN_COMMAND,description:string,risk:string,payload:object}],memoryDrafts:string[],skillDrafts:[{id:string,name:string,description:string,instructions:string}],delegations:[{id:string,role:string,task:string,focus?:{id:string,purpose:string,bounds:{x:int,y:int,z:int,x2:int,y2:int,z2:int}}}],projectDraft?:{id:string,name:string,goal:string,blueprint:string,dimension:string,bounds:{x:int,y:int,z:int,x2:int,y2:int,z2:int}},attentionRequests?:[{id:string,purpose:string,bounds:{x:int,y:int,z:int,x2:int,y2:int,z2:int}}],checkpoint?:{stage:string,summary:string,nextTask:string,complete:boolean}}。");
        context.append("嵌套对象必须恰好包含上面列出的字段，既不能少写也不能多写：projectDraft 只能是 id/name/goal/blueprint/dimension/bounds；bounds 只能是 x/y/z/x2/y2/z2；每个 attentionRequests 元素与每个 delegation.focus 只能是 id/purpose/bounds；checkpoint 只能是 stage/summary/nextTask/complete（complete 为布尔值）；skillDrafts 元素只能是 id/name/description/instructions。");
        context.append("PLACE_BLOCK payload 必须含整数 x,y,z 与 block；FILL payload 必须含整数 x,y,z,x2,y2,z2 与 block；RUN_COMMAND payload 必须含 command。不得执行世界操作。");
        context.append("thoughtSummary 仅为简短公开决策摘要，不要输出私有思维链。动作ID必须唯一。返回JSON，不使用Markdown代码围栏。");
        if (request.allowDelegations()) context.append("主 Agent 可提出最多 3 个 delegations，并可提出最多 3 个 attentionRequests；每个 focus 只读且最多4096方块。"); else context.append("当前 Agent 禁止提出 delegations，必须返回空数组；不得递归派遣子 Agent。");
        // U4: a failed focus read is marked in the task context, so the prompt must state that the
        // marked region is unknown rather than letting the model treat the marker as world data.
        context.append("读取结果若标注为 [读取失败:原因]，说明该区域状态未知，禁止凭空推测或虚构该区域的方块、实体与地形。");
        if (!request.task().isBlank()) context.append("\n任务：").append(request.task());
        JsonObject system = new JsonObject(); system.addProperty("role", "system"); system.addProperty("content", context.toString());
        JsonArray ordered = new JsonArray(); ordered.add(system); for (JsonElement message : messages) ordered.add(message); root.add("messages", ordered);
        // O8: the supported effort values live in one place (AiProfile.EFFORTS); unsupported values
        // and "none" are omitted from the request rather than sent.
        String effort = request.profile().apiReasoningEffort(); if (effort != null) root.addProperty("reasoning_effort", effort);
        return root;
    }
    private static String normalizeEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.strip().replaceAll("/+$", ""); if (value.endsWith("/chat/completions")) return value;
        if (value.endsWith("/v1")) return value + "/chat/completions"; return value + "/v1/chat/completions";
    }
    private static String string(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
    private static boolean isSse(HttpResponse<?> response) { return response.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("text/event-stream"); }
    private static void close(HttpResponse<java.io.InputStream> response) { if (response != null) close(response.body()); }
    private static void close(java.io.InputStream input) { if (input != null) try { input.close(); } catch (IOException ignored) {} }
    private static Throwable safeFailure(Throwable failure) { Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure; return new ApiException(cause instanceof java.net.http.HttpTimeoutException ? "请求超时，请重试" : "网络请求失败，请检查网络后重试"); }
    private static String statusReason(int status) {
        String reason = switch (status) { case 401, 403 -> "鉴权失败，请检查API密钥"; case 404 -> "接口不存在，请检查端点"; case 429 -> "请求频率或额度受限，请稍后重试"; default -> status >= 500 ? "服务暂不可用，请稍后重试" : "请求未被接受，请检查模型配置后重试"; };
        return "HTTP " + status + "：" + reason + (status == 401 || status == 403 || status == 404 ? "后重试" : "");
    }
    @Override public void close() { for (Exchange exchange : active) exchange.cancel(); readers.shutdownNow(); }

    public static final class ApiException extends IOException {
        private ApiException(String message) { super(message); }
    }

    public record Request(String endpoint, String apiKey, AiProfile profile, List<ChatMessage> history, String worldContext,
                          String agentId, String role, String task, boolean stream) {
        public Request { history = List.copyOf(history == null ? List.of() : history); worldContext = worldContext == null ? "" : worldContext; agentId = agentId == null ? "main" : agentId; role = role == null ? "主Agent" : role; task = task == null ? "" : task; }
        public boolean allowDelegations() { return !agentId.startsWith("child-") && !agentId.equals("summary"); }
        @Override public String toString() { return "Request[apiKey=<redacted>, agentId=" + agentId + ", role=" + role + "]"; }
    }
    public static final class Exchange {
        private final CompletableFuture<AiPlan> future; private final Runnable cancel;
        Exchange(CompletableFuture<AiPlan> future, Runnable cancel) { this.future = future; this.cancel = cancel; }
        public CompletableFuture<AiPlan> future() { return future; }
        public void cancel() { cancel.run(); }
    }
}
