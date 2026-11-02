package mine.coding.ai.transport;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import mine.coding.ai.AiProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiHttpClientTest {
    private HttpServer server;
    private static final String PLAN = "{\"message\":\"ok\",\"actions\":[]}";

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void parsesSplitSseAndPublicReasoningWithCrLfAndMultilineData() throws Exception {
        AtomicReference<JsonObject> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        serve(200, "text/event-stream", exchange -> {
            body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = "data: {\"choices\":[\r\ndata: {\"delta\":{\"reasoning_content\":\"公开摘要\"}}]}\r\n\r\n"
                    + data(PLAN.substring(0, 12)) + data(PLAN.substring(12)) + "data: [DONE]\r\n\r\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < bytes.length; i += 7) {
                exchange.getResponseBody().write(bytes, i, Math.min(7, bytes.length - i));
                exchange.getResponseBody().flush();
            }
        });
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            var plan = client.request(request("high"), events::add).future().get(5, TimeUnit.SECONDS);
            assertEquals("ok", plan.message());
        }
        assertEquals("Bearer secret", authorization.get());
        assertTrue(body.get().get("stream").getAsBoolean());
        assertEquals("high", body.get().get("reasoning_effort").getAsString());
        assertFalse(body.get().has("response_format"));
        assertTrue(body.get().toString().contains("坐标 1 64 2"));
        assertTrue(body.get().toString().contains("建筑技能"));
        String system = body.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(system.contains("PLACE_BLOCK|FILL|RUN_COMMAND"));
        assertTrue(system.contains("depth=0"));
        assertTrue(system.contains("allowDelegations=true"));
        assertTrue(system.contains("x2,y2,z2"));
        assertEquals(PLAN, events.stream().filter(e -> e.type() == ChatEvent.Type.TEXT).map(ChatEvent::text).reduce("", String::concat));
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.REASONING && e.text().equals("公开摘要")));
    }

    @Test void fallsBackToJsonAndOmitsUnsupportedReasoning() throws Exception {
        AtomicReference<JsonObject> body = new AtomicReference<>();
        serve(200, "application/json", exchange -> {
            body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject root = JsonParser.parseString("{\"choices\":[{\"message\":{}}]}").getAsJsonObject();
            root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").addProperty("content", PLAN);
            root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").addProperty("reasoning_content", "公开非流式摘要");
            exchange.getResponseBody().write(root.toString().getBytes(StandardCharsets.UTF_8));
        });
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            assertEquals("ok", client.request(request("xhigh"), events::add).future().get(5, TimeUnit.SECONDS).message());
        }
        assertFalse(body.get().has("reasoning_effort"));
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.REASONING && e.text().equals("公开非流式摘要")));
    }

    @Test void hidesRemoteErrorBodyAndApiKey() throws Exception {
        serve(401, "application/json", exchange -> exchange.getResponseBody().write("secret private-provider-diagnostic".getBytes(StandardCharsets.UTF_8)));
        try (AiHttpClient client = new AiHttpClient()) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> client.request(request("none"), e -> {}).future().get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("401"));
            assertFalse(error.getCause().getMessage().contains("secret"));
            assertFalse(error.getCause().getMessage().contains("private-provider"));
        }
    }

    /**
     * O5 contract change: a stream that ends with finish_reason + closed connection (no "[DONE]"
     * sentinel) is a normal completion for many OpenAI-compatible providers. The old code rejected
     * it; the new contract accepts EOF whenever the accumulated text parses into a plan.
     */
    @Test void acceptsEofWithoutDoneWhenStreamedTextParses() throws Exception {
        serve(200, "text/event-stream", exchange -> exchange.getResponseBody().write(data(PLAN).getBytes(StandardCharsets.UTF_8)));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            assertEquals("ok", client.request(request("none"), events::add).future().get(5, TimeUnit.SECONDS).message());
        }
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.TEXT));
    }

    @Test void rejectsPrematureEofWhenStreamedTextIsNotAPlan() throws Exception {
        serve(200, "text/event-stream", exchange -> exchange.getResponseBody().write(data("partial answer").getBytes(StandardCharsets.UTF_8)));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            var error = assertThrows(ExecutionException.class, () -> client.request(request("none"), events::add).future().get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("提前结束"));
        }
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.TEXT && e.text().equals("partial answer")));
        assertTrue(events.stream().noneMatch(e -> e.plan() != null));
    }

    @Test void parsesPlanWrappedInMarkdownFence() throws Exception {
        String fenced = "```json\n" + PLAN + "\n```";
        serve(200, "text/event-stream", exchange -> exchange.getResponseBody()
                .write((data(fenced.substring(0, 8)) + data(fenced.substring(8)) + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            assertEquals("ok", client.request(request("none"), events::add).future().get(5, TimeUnit.SECONDS).message());
        }
        assertEquals(fenced, events.stream().filter(e -> e.type() == ChatEvent.Type.TEXT).map(ChatEvent::text).reduce("", String::concat));
    }

    @Test void parsesFencedPlanWithSurroundingProseAndNoDoneSentinel() throws Exception {
        String answer = "这是计划：\n```json\n" + PLAN + "\n```\n请确认。";
        serve(200, "text/event-stream", exchange -> exchange.getResponseBody().write(data(answer).getBytes(StandardCharsets.UTF_8)));
        try (AiHttpClient client = new AiHttpClient()) {
            assertEquals("ok", client.request(request("none"), e -> {}).future().get(5, TimeUnit.SECONDS).message());
        }
    }

    /**
     * O7: the parser rejects any nested object whose key set is not exactly the expected one
     * (BlockBox.requireFields), so every nested schema must be spelled out in the prompt.
     */
    @Test void promptDeclaresEveryNestedSchemaField() throws Exception {
        AtomicReference<JsonObject> body = new AtomicReference<>();
        serve(200, "application/json", exchange -> {
            body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject message = new JsonObject(); message.addProperty("content", PLAN);
            JsonObject choice = new JsonObject(); choice.add("message", message);
            JsonObject root = new JsonObject(); JsonArray choices = new JsonArray(); choices.add(choice); root.add("choices", choices);
            exchange.getResponseBody().write(root.toString().getBytes(StandardCharsets.UTF_8));
        });
        try (AiHttpClient client = new AiHttpClient()) { client.request(request("none"), e -> {}).future().get(5, TimeUnit.SECONDS); }
        String system = body.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(system.contains("projectDraft?:{id:string,name:string,goal:string,blueprint:string,dimension:string,bounds:{x:int,y:int,z:int,x2:int,y2:int,z2:int}}"), system);
        assertTrue(system.contains("attentionRequests?:[{id:string,purpose:string,bounds:{x:int,y:int,z:int,x2:int,y2:int,z2:int}}]"), system);
        assertTrue(system.contains("checkpoint?:{stage:string,summary:string,nextTask:string,complete:boolean}"), system);
        assertTrue(system.contains("focus?:{id:string,purpose:string,bounds:{x:int,y:int,z:int,x2:int,y2:int,z2:int}}"), system);
        assertTrue(system.contains("嵌套对象必须恰好包含上面列出的字段"), system);
    }

    @Test void cancellationClosesStreamAndSuppressesFurtherEvents() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        serve(200, "text/event-stream", exchange -> {
            exchange.getResponseBody().write(data("partial").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.getResponseBody().write(data(PLAN).getBytes(StandardCharsets.UTF_8));
        });
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            var exchange = client.request(request("none"), e -> { events.add(e); if (e.type() == ChatEvent.Type.TEXT) received.countDown(); });
            assertTrue(received.await(3, TimeUnit.SECONDS));
            exchange.cancel();
            int count = events.size();
            release.countDown();
            assertTrue(exchange.future().isCancelled());
            Thread.sleep(100);
            assertEquals(count, events.size());
        } finally { release.countDown(); }
    }

    @Test void deadlineStopsStalledStream() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        serve(200, "text/event-stream", exchange -> {
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try (AiHttpClient client = new AiHttpClient(HttpClient.newHttpClient(), Duration.ofMillis(150))) {
            var error = assertThrows(ExecutionException.class, () -> client.request(request("none"), e -> {}).future().get(3, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("超时"));
        } finally { release.countDown(); }
    }

    @Test void malformedPlanKeepsTextWithoutPlanEvent() throws Exception {
        serve(200, "text/event-stream", exchange -> exchange.getResponseBody().write((data("plain answer") + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            assertThrows(ExecutionException.class, () -> client.request(request("none"), events::add).future().get(5, TimeUnit.SECONDS));
        }
        assertEquals("plain answer", events.stream().filter(e -> e.type() == ChatEvent.Type.TEXT).findFirst().orElseThrow().text());
        assertTrue(events.stream().noneMatch(e -> e.plan() != null));
    }

    @Test void normalizesTrailingSlashAndRedactsRequestKey() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        serve(200, "application/json", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            JsonObject message = new JsonObject(); message.addProperty("content", PLAN);
            JsonObject choice = new JsonObject(); choice.add("message", message);
            JsonObject root = new JsonObject(); JsonArray choices = new JsonArray(); choices.add(choice); root.add("choices", choices);
            String json = root.toString();
            exchange.getResponseBody().write(json.getBytes(StandardCharsets.UTF_8));
        });
        AiHttpClient.Request request = new AiHttpClient.Request("http://localhost:" + server.getAddress().getPort() + "/v1/", "secret-key", request("none").profile(), List.of(), "", "main", "主Agent", "", true);
        try (AiHttpClient client = new AiHttpClient()) { client.request(request, e -> {}).future().get(5, TimeUnit.SECONDS); }
        assertEquals("/v1/chat/completions", path.get());
        assertFalse(request.toString().contains("secret-key"));
    }

    /**
     * U1: a stream that keeps producing text past {@link AiHttpClient#MAX_RESPONSE_CHARS} must abort
     * with a curated Chinese error instead of buffering without bound, and the TEXT events already
     * delivered must stay available to the UI ("已保留文本" semantics).
     */
    @Test void abortsStreamPastResponseCapAndKeepsAlreadyReceivedText() throws Exception {
        String chunk = "x".repeat(2048);
        serve(200, "text/event-stream", exchange -> {
            try {
                for (int i = 0; i < 400; i++) {
                    exchange.getResponseBody().write(data(chunk).getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                }
            } catch (java.io.IOException ignored) {
                // The client aborts mid-stream by design; the server side write fails after that.
            }
        });
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> client.request(request("none"), events::add).future().get(10, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("体积"), error.getCause().getMessage());
        }
        String received = events.stream().filter(e -> e.type() == ChatEvent.Type.TEXT).map(ChatEvent::text).reduce("", String::concat);
        assertFalse(received.isEmpty(), "already received text must stay visible");
        assertTrue(received.length() <= AiHttpClient.MAX_RESPONSE_CHARS, "retained text stays within the cap");
        assertTrue(events.stream().noneMatch(e -> e.plan() != null));
    }

    /**
     * U1: a stream that never emits its blank-line terminator must not grow one line without bound.
     * The line guard aborts before the whole malformed event is buffered.
     */
    @Test void abortsNeverTerminatedSseEventWithoutBufferingItWhole() throws Exception {
        String huge = "x".repeat(AiHttpClient.MAX_EVENT_CHARS * 4);
        serve(200, "text/event-stream", exchange -> {
            try {
                exchange.getResponseBody().write(("data: " + huge).getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (java.io.IOException ignored) {
                // Aborted by the client once the line guard fires.
            }
        });
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> client.request(request("none"), events::add).future().get(10, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("单条"), error.getCause().getMessage());
        }
        assertTrue(events.stream().noneMatch(e -> e.type() == ChatEvent.Type.TEXT));
    }

    /** U1: the non-SSE JSON fallback used to concatenate the whole body unbounded (and quadratically). */
    @Test void abortsOversizedJsonResponse() throws Exception {
        String huge = "{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(AiHttpClient.MAX_RESPONSE_CHARS + 64 * 1024) + "\"}}]}";
        serve(200, "application/json", exchange -> {
            try {
                exchange.getResponseBody().write(huge.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (java.io.IOException ignored) {
                // Client aborted after the body cap was crossed.
            }
        });
        try (AiHttpClient client = new AiHttpClient()) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> client.request(request("none"), e -> {}).future().get(10, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("体积"), error.getCause().getMessage());
        }
    }

    /**
     * U1 guard against over-tightening: a response that is large but below both caps must parse
     * exactly as before. 128 KiB of message text is ~4x the largest realistic plan.
     */
    @Test void largeButLegalStreamWithinCapsStillParses() throws Exception {
        String padding = "a".repeat(128 * 1024);
        String bigPlan = "{\"message\":\"" + padding + "\",\"actions\":[]}";
        serve(200, "text/event-stream", exchange -> {
            try {
                for (int offset = 0; offset < bigPlan.length(); offset += 1024) {
                    exchange.getResponseBody().write(data(bigPlan.substring(offset, Math.min(bigPlan.length(), offset + 1024))).getBytes(StandardCharsets.UTF_8));
                }
                exchange.getResponseBody().write("data: [DONE]\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (java.io.IOException ignored) {
                // Not expected here; the client accepts this stream.
            }
        });
        try (AiHttpClient client = new AiHttpClient()) {
            var plan = client.request(request("none"), e -> {}).future().get(10, TimeUnit.SECONDS);
            assertEquals(padding, plan.message());
        }
    }

    /** U4: the prompt must forbid inventing world state for a region whose read failed. */
    @Test void promptForbidsSpeculatingAboutFailedReadRegions() throws Exception {
        AtomicReference<JsonObject> body = new AtomicReference<>();
        serve(200, "application/json", exchange -> {
            body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject message = new JsonObject(); message.addProperty("content", PLAN);
            JsonObject choice = new JsonObject(); choice.add("message", message);
            JsonObject root = new JsonObject(); JsonArray choices = new JsonArray(); choices.add(choice); root.add("choices", choices);
            exchange.getResponseBody().write(root.toString().getBytes(StandardCharsets.UTF_8));
        });
        try (AiHttpClient client = new AiHttpClient()) { client.request(request("none"), e -> {}).future().get(5, TimeUnit.SECONDS); }
        String system = body.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(system.contains("[读取失败:"), system);
        assertTrue(system.contains("禁止凭空推测"), system);
    }

    private AiHttpClient.Request request(String effort) {
        return new AiHttpClient.Request("http://localhost:" + server.getAddress().getPort() + "/v1", "secret",
                new AiProfile("p", "Builder", "Build carefully", "test-model", effort, List.of("建筑技能\n对称")),
                List.of(new ChatMessage("user", "盖房子")), "坐标 1 64 2", "main", "主Agent", "", true);
    }

    private static String data(String text) {
        JsonObject delta = new JsonObject(); delta.addProperty("content", text);
        return "data: {\"choices\":[{\"delta\":" + delta + "}]}\r\n\r\n";
    }

    private void serve(int status, String contentType, Writer writer) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, 0);
            try { writer.write(exchange); } finally { exchange.close(); }
        });
        server.start();
    }
    @FunctionalInterface private interface Writer { void write(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException; }
}
