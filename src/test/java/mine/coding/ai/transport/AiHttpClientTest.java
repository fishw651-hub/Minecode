package mine.coding.ai.transport;

import com.google.gson.JsonObject;
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
        assertEquals(PLAN, events.stream().filter(e -> e.type() == ChatEvent.Type.TEXT).map(ChatEvent::text).reduce("", String::concat));
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.REASONING && e.text().equals("公开摘要")));
    }

    @Test void fallsBackToJsonAndOmitsUnsupportedReasoning() throws Exception {
        AtomicReference<JsonObject> body = new AtomicReference<>();
        serve(200, "application/json", exchange -> {
            body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject root = JsonParser.parseString("{\"choices\":[{\"message\":{}}]}").getAsJsonObject();
            root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").addProperty("content", PLAN);
            exchange.getResponseBody().write(root.toString().getBytes(StandardCharsets.UTF_8));
        });
        try (AiHttpClient client = new AiHttpClient()) {
            assertEquals("ok", client.request(request("xhigh"), e -> {}).future().get(5, TimeUnit.SECONDS).message());
        }
        assertFalse(body.get().has("reasoning_effort"));
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

    @Test void rejectsPrematureEofButKeepsStreamedText() throws Exception {
        serve(200, "text/event-stream", exchange -> exchange.getResponseBody().write(data(PLAN).getBytes(StandardCharsets.UTF_8)));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        try (AiHttpClient client = new AiHttpClient()) {
            assertThrows(ExecutionException.class, () -> client.request(request("none"), events::add).future().get(5, TimeUnit.SECONDS));
        }
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.TEXT));
        assertTrue(events.stream().noneMatch(e -> e.type() == ChatEvent.Type.PLAN));
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
            var exchange = client.request(request("none"), e -> { events.add(e); received.countDown(); });
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
        assertEquals("plain answer", events.getFirst().text());
        assertTrue(events.stream().noneMatch(e -> e.plan() != null));
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
