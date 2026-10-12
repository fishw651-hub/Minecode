package mine.coding.ai.transport;

import mine.coding.ai.AiPlan;
import mine.coding.ai.AiProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AiOrchestratorTest {
    private static final AiProfile PROFILE = new AiProfile("p", "主档案", "system", "model", "medium", List.of("skill"));
    private static final AiPlan EMPTY = AiPlan.fromJson("{\"message\":\"done\",\"actions\":[]}");

    @Test void fansOutOnlyThreeChildrenThenSummarizesWithRecursionDisabled() throws Exception {
        FakeTransport client = new FakeTransport();
        AiOrchestrator orchestrator = new AiOrchestrator(client);
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = orchestrator.start("url", "key", PROFILE, List.of(new ChatMessage("user", "build")), "world", events::add);
        client.calls.getFirst().future.complete(AiPlan.fromJson("""
                {"actions":[],"delegations":[{"id":"a","role":"设计","task":"task-a"},{"id":"b","role":"检查","task":"task-b"},{"id":"c","role":"材料","task":"task-c"},{"id":"d","role":"其他","task":"task-d"}]}
                """));
        assertEquals(4, client.calls.size());
        var children = List.copyOf(client.calls.subList(1, 4));
        assertEquals(3, children.stream().map(c -> c.request.agentId()).distinct().count());
        for (Call child : children) {
            assertFalse(child.request.allowDelegations());
            assertEquals(PROFILE, child.request.profile());
            assertEquals("world", child.request.worldContext());
        }
        children.get(0).future.complete(AiPlan.fromJson("{\"actions\":[],\"delegations\":[{\"id\":\"nested\",\"role\":\"nested\",\"task\":\"never\"}]}"));
        children.get(1).future.completeExceptionally(new IllegalStateException("secret error"));
        children.get(2).future.complete(EMPTY);
        assertEquals(5, client.calls.size());
        Call summary = client.calls.getLast();
        assertFalse(summary.request.allowDelegations());
        assertTrue(summary.request.task().contains("失败"));
        assertTrue(summary.request.task().contains("done"));
        assertFalse(summary.request.task().contains("secret error"));
        summary.future.complete(EMPTY);
        assertEquals(EMPTY, handle.future().get(3, TimeUnit.SECONDS));
        assertEquals(1, events.stream().filter(e -> e.type() == ChatEvent.Type.PLAN).count());
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.FAILED));
        assertTrue(events.stream().anyMatch(e -> e.text().contains("再次派遣")));
        assertTrue(events.stream().filter(e -> e.type() != ChatEvent.Type.PLAN).allMatch(e -> e.plan() == null));
    }

    @Test void cancelStopsAllChildrenAndPreventsStaleSummary() {
        FakeTransport client = new FakeTransport();
        AiOrchestrator orchestrator = new AiOrchestrator(client);
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = orchestrator.start("url", "key", PROFILE, List.of(), "world", events::add);
        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"delegations\":[{\"id\":\"a\",\"role\":\"a\",\"task\":\"a\"},{\"id\":\"b\",\"role\":\"b\",\"task\":\"b\"}]}"));
        handle.cancel();
        assertTrue(handle.future().isCancelled());
        assertTrue(client.calls.stream().allMatch(c -> c.cancelled || c.future.isDone()));
        int count = events.size();
        client.calls.get(1).events.accept(new ChatEvent("stale", "a", ChatEvent.Type.TEXT, "late", null));
        assertEquals(count, events.size());
        assertEquals(3, client.calls.size());
    }

    @Test void replacementCancelsPreviousRequest() {
        FakeTransport client = new FakeTransport();
        AiOrchestrator orchestrator = new AiOrchestrator(client);
        var first = orchestrator.start("url", "key", PROFILE, List.of(), "world", e -> {});
        var second = orchestrator.start("url", "key", PROFILE, List.of(), "world", e -> {});
        assertTrue(first.future().isCancelled());
        assertTrue(client.calls.getFirst().cancelled);
        assertFalse(second.future().isDone());
        second.future().cancel(true);
        assertTrue(client.calls.getLast().cancelled);
    }

    @Test void finalPlanDeduplicatesActionIdsAndStripsDelegations() throws Exception {
        FakeTransport client = new FakeTransport();
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", e -> {});
        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"delegations\":[{\"id\":\"a\",\"role\":\"a\",\"task\":\"a\"}]}"));
        client.calls.get(1).future.complete(EMPTY);
        client.calls.getLast().future.complete(AiPlan.fromJson("""
                {"actions":[{"id":"x","type":"RUN_COMMAND","description":"a","risk":"high","payload":{"command":"time set day"}},{"id":"x","type":"RUN_COMMAND","description":"b","risk":"high","payload":{"command":"time set night"}}],"delegations":[{"id":"illegal","role":"bad","task":"never"}]}
                """));
        var result = handle.future().get(3, TimeUnit.SECONDS);
        assertEquals(1, result.actions().size());
        assertTrue(result.delegations().isEmpty());
        assertEquals(3, client.calls.size());
    }

    private static final class FakeTransport extends AiHttpClient {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        @Override public Exchange request(Request request, Consumer<ChatEvent> events) {
            Call call = new Call(request, events); calls.add(call);
            return new Exchange(call.future, () -> { call.cancelled = true; call.future.cancel(true); });
        }
    }
    private static final class Call {
        final AiHttpClient.Request request; final Consumer<ChatEvent> events;
        final CompletableFuture<AiPlan> future = new CompletableFuture<>(); boolean cancelled;
        Call(AiHttpClient.Request request, Consumer<ChatEvent> events) { this.request = request; this.events = events; }
    }
}
