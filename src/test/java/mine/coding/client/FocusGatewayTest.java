package mine.coding.client;

import com.google.gson.JsonObject;
import mine.coding.ai.spatial.BlockBox;
import mine.coding.ai.spatial.FocusRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class FocusGatewayTest {
    @Test
    void correlatesResponsesByRequestId() {
        List<JsonObject> sent = new ArrayList<>();
        FocusGateway gateway = new FocusGateway(sent::add);
        CompletableFuture<String> future = gateway.read(new FocusRequest("focus-1", "查看地面", new BlockBox(0, 64, 0, 1, 64, 1)));
        assertEquals("focus", sent.getFirst().get("op").getAsString());
        assertEquals("focus-1", sent.getFirst().get("requestId").getAsString());

        JsonObject response = new JsonObject();
        response.addProperty("event", "focus");
        response.addProperty("requestId", "focus-1");
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("dimension", "minecraft:overworld");
        snapshot.addProperty("bounds", "0,64,0..1,64,1");
        snapshot.addProperty("palette", "stone");
        response.addProperty("snapshot", snapshot.toString());
        gateway.accept(response);

        // The gateway hands the model a compact key=value projection of the snapshot,
        // not a single field (see boundsSnapshotTextIsStableAndBounded below).
        String text = future.join();
        assertTrue(text.contains("dimension=minecraft:overworld"), text);
        assertTrue(text.contains("bounds=0,64,0..1,64,1"), text);
        assertTrue(text.contains("palette=stone"), text);
    }

    @Test
    void cancelsOlderPendingRequestWithSameId() {
        FocusGateway gateway = new FocusGateway(ignored -> {});
        CompletableFuture<String> old = gateway.read(new FocusRequest("same", "旧", new BlockBox(0, 0, 0, 0, 0, 0)));
        CompletableFuture<String> current = gateway.read(new FocusRequest("same", "新", new BlockBox(0, 0, 0, 0, 0, 0)));
        assertTrue(old.isCancelled() || old.isCompletedExceptionally());
        gateway.cancelAll();
        assertTrue(current.isCancelled() || current.isCompletedExceptionally());
    }

    @Test
    void sendsThroughTheInjectedDispatcherInsteadOfTheCallerThread() {
        List<Runnable> queued = new ArrayList<>();
        List<JsonObject> sent = new ArrayList<>();
        FocusGateway gateway = new FocusGateway(sent::add, queued::add);
        gateway.read(new FocusRequest("focus-thread", "查看地面", new BlockBox(0, 64, 0, 1, 64, 1)));
        assertTrue(sent.isEmpty(), "the packet must be handed to the client dispatcher, not sent from the AI thread");
        assertEquals(1, queued.size());
        queued.getFirst().run();
        assertEquals(1, sent.size());
        assertEquals("focus", sent.getFirst().get("op").getAsString());
    }

    @Test
    void timesOutSoTheConsoleCannotStayBusyForever() throws Exception {
        FocusGateway gateway = new FocusGateway(ignored -> {}, Runnable::run, Duration.ofMillis(50));
        CompletableFuture<String> future = gateway.read(new FocusRequest("focus-slow", "查看地面",
                new BlockBox(0, 64, 0, 1, 64, 1)));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        awaitNoPendingReads(gateway);
        assertEquals(0, gateway.pendingReads(), "a timed out read must not stay pending");
        gateway.accept(focusAnswer("focus-slow"));
        assertTrue(future.isCompletedExceptionally(), "a late answer must not resurrect a timed out read");
    }

    @Test
    void answeredReadsNeverTimeOutAndLeaveNothingPending() {
        List<JsonObject> sent = new ArrayList<>();
        FocusGateway gateway = new FocusGateway(sent::add, Runnable::run, Duration.ofMillis(50));
        CompletableFuture<String> future = gateway.read(new FocusRequest("focus-fast", "查看地面",
                new BlockBox(0, 64, 0, 1, 64, 1)));
        gateway.accept(focusAnswer("focus-fast"));
        assertEquals("dimension=minecraft:overworld", future.join());
        assertEquals(0, gateway.pendingReads());
    }

    private static JsonObject focusAnswer(String requestId) {
        JsonObject response = new JsonObject();
        response.addProperty("event", "focus");
        response.addProperty("requestId", requestId);
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("dimension", "minecraft:overworld");
        response.addProperty("snapshot", snapshot.toString());
        return response;
    }

    private static void awaitNoPendingReads(FocusGateway gateway) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (gateway.pendingReads() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
    }

    @Test
    void boundsSnapshotTextIsStableAndBounded() {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("dimension", "minecraft:overworld");
        snapshot.addProperty("readTimestamp", 12);
        snapshot.addProperty("bounds", "0,0,0..3,0,0");
        snapshot.addProperty("snapshotHash", "hash");
        snapshot.addProperty("loadedChunkCount", 1);
        snapshot.addProperty("palette", "[\"minecraft:stone\",\"minecraft:dirt\"]");
        snapshot.addProperty("runs", "[{\"state\":0,\"length\":3},{\"state\":1,\"length\":1}]");
        String text = FocusGateway.snapshotText(snapshot, 120);
        assertTrue(text.length() <= 120);
        assertTrue(text.contains("minecraft:overworld"));
        assertTrue(text.contains("bounds"));
    }
}
