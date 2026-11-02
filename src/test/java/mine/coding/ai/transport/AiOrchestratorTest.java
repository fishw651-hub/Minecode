package mine.coding.ai.transport;

import mine.coding.ai.AiPlan;
import mine.coding.ai.AiProfile;
import mine.coding.ai.spatial.BlockBox;
import mine.coding.ai.spatial.FocusRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AiOrchestratorTest {
    private static final AiProfile PROFILE = new AiProfile("p", "主档案", "system", "model", "medium", List.of("skill"));
    private static final AiPlan EMPTY = AiPlan.fromJson("{\"message\":\"done\",\"actions\":[]}");

    @Test void modelReadsFocusThenRequestsSameAgentAgainBeforeFinalPlan() throws Exception {
        FakeTransport client = new FakeTransport();
        FocusRequest focus = new FocusRequest("f1", "检查窗口", BlockBox.ofCorners(0, 64, 0, 0, 64, 0));
        FocusReader reader = request -> CompletableFuture.completedFuture("stone at 0 64 0");
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", reader, e -> {});

        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"attentionRequests\":[" + focus.toJson() + "]}"));
        assertEquals(2, client.calls.size());
        assertTrue(client.calls.get(1).request.task().contains("stone at 0 64 0"));
        client.calls.get(1).future.complete(EMPTY);

        assertEquals(EMPTY, handle.future().get(3, TimeUnit.SECONDS));
        // The attention follow-up deliberately reuses the main agent id so the timeline keeps a
        // single entry for the main agent; both dispatches are therefore "main".
        assertEquals(2, client.calls.stream().filter(c -> c.request.agentId().equals("main")).count());
        assertEquals(0, client.calls.stream().filter(c -> c.request.agentId().equals("summary")).count());
    }

    @Test void fansOutOnlyThreeChildrenThenSummarizesWithRecursionDisabled() throws Exception {
        FakeTransport client = new FakeTransport();
        AiOrchestrator orchestrator = new AiOrchestrator(client);
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = orchestrator.start("url", "key", PROFILE, List.of(new ChatMessage("user", "build")), "world", events::add);
        client.calls.getFirst().future.complete(AiPlan.fromJson("""
                {"actions":[],"delegations":[{"id":"a","role":"设计","task":"task-a"},{"id":"b","role":"检查","task":"task-b"},{"id":"c","role":"材料","task":"task-c"}]}
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

    @Test void onlyFinalOrchestrationPlanReachesUi() throws Exception {
        FakeTransport client = new FakeTransport();
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", events::add);
        client.calls.getFirst().events.accept(new ChatEvent("main", "主Agent", ChatEvent.Type.PLAN, "transport plan", EMPTY));
        client.calls.getFirst().future.complete(EMPTY);
        assertEquals(EMPTY, handle.future().get(3, TimeUnit.SECONDS));
        assertEquals(1, events.stream().filter(e -> e.type() == ChatEvent.Type.PLAN).count());
    }

    @Test void summaryReceivesEntireChildPlan() {
        FakeTransport client = new FakeTransport();
        new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", e -> {});
        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"delegations\":[{\"id\":\"a\",\"role\":\"a\",\"task\":\"a\"}]}"));
        AiPlan child = AiPlan.fromJson("""
                {"message":"child","thoughtSummary":"public-summary","actions":[{"id":"a1","type":"RUN_COMMAND","description":"Set time","risk":"low","payload":{"command":"time set day"}}],"memoryDrafts":["remember"],"skillDrafts":[{"id":"s1","name":"Skill","description":"description","instructions":"instructions"}]}
                """);
        client.calls.get(1).future.complete(child);
        assertTrue(client.calls.getLast().request.task().contains(child.toJson()));
    }

    @Test void synchronousTransportFailureCompletesRequest() {
        AiHttpClient client = new AiHttpClient() {
            @Override public Exchange request(Request request, Consumer<ChatEvent> events) { throw new IllegalArgumentException("secret endpoint"); }
        };
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", events::add);
        assertTrue(handle.future().isCompletedExceptionally());
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.FAILED));
        assertTrue(events.stream().noneMatch(e -> e.text().contains("secret")));
    }

    @Test void cancellationDuringChildQueuedEventPreventsItsRequest() {
        FakeTransport client = new FakeTransport();
        AiOrchestrator orchestrator = new AiOrchestrator(client);
        var current = new java.util.concurrent.atomic.AtomicReference<AiOrchestrator.TaskHandle>();
        var handle = orchestrator.start("url", "key", PROFILE, List.of(), "world", e -> {
            if (e.agentId().startsWith("child-") && e.type() == ChatEvent.Type.QUEUED) current.get().cancel();
        });
        current.set(handle);
        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"delegations\":[{\"id\":\"a\",\"role\":\"a\",\"task\":\"a\"}]}"));
        assertTrue(handle.future().isCancelled());
        assertEquals(1, client.calls.size());
    }

    @Test void moreThanThreeDelegationsRejectedBeforeDispatch() {
        assertThrows(IllegalArgumentException.class, () -> AiPlan.fromJson("""
                {"actions":[],"delegations":[{"id":"a","role":"a","task":"a"},{"id":"b","role":"b","task":"b"},{"id":"c","role":"c","task":"c"},{"id":"d","role":"d","task":"d"}]}
                """));
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
        for (Call child : client.calls.subList(1, 3)) {
            assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.CANCELLED && e.agentId().equals(child.request.agentId())));
        }
    }

    @Test void childLifecycleHasQueuedRunningAndCompletedEvents() {
        FakeTransport client = new FakeTransport();
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", events::add);
        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"delegations\":[{\"id\":\"a\",\"role\":\"designer\",\"task\":\"a\"}]}"));
        Call child = client.calls.get(1);
        child.future.complete(EMPTY);
        for (ChatEvent.Type type : List.of(ChatEvent.Type.QUEUED, ChatEvent.Type.RUNNING, ChatEvent.Type.COMPLETED)) {
            assertTrue(events.stream().anyMatch(e -> e.agentId().equals(child.request.agentId()) && e.type() == type));
        }
    }

    /**
     * O4 contract change: a summary that fails synchronously used to fail the whole turn and throw
     * away the already-successful main plan. It now degrades to {@code sanitize(mainPlan)}.
     */
    @Test void synchronousSummaryFailureFallsBackToMainPlanWithoutLeakingError() throws Exception {
        FakeTransport client = new FakeTransport() {
            @Override public Exchange request(Request request, Consumer<ChatEvent> events) {
                if (request.agentId().equals("summary")) throw new IllegalArgumentException("secret provider error");
                return super.request(request, events);
            }
        };
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", events::add);
        client.calls.getFirst().future.complete(AiPlan.fromJson("""
                {"message":"main-plan","actions":[],"delegations":[{"id":"a","role":"a","task":"a"}]}
                """));
        client.calls.get(1).future.complete(EMPTY);

        AiPlan result = handle.future().get(5, TimeUnit.SECONDS);
        assertEquals("main-plan", result.message());
        assertTrue(result.delegations().isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.agentId().equals("summary") && e.type() == ChatEvent.Type.FAILED));
        assertTrue(events.stream().anyMatch(e -> e.text().contains("回落")));
        assertTrue(events.stream().noneMatch(e -> e.text().contains("secret")));
        assertEquals(1, events.stream().filter(e -> e.type() == ChatEvent.Type.PLAN).count());
    }

    @Test void cancellingSummaryQueuedEventPreventsSummaryRequest() {
        FakeTransport client = new FakeTransport();
        var current = new java.util.concurrent.atomic.AtomicReference<AiOrchestrator.TaskHandle>();
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", e -> {
            if (e.agentId().equals("summary") && e.type() == ChatEvent.Type.QUEUED) current.get().cancel();
        });
        current.set(handle);
        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"delegations\":[{\"id\":\"a\",\"role\":\"a\",\"task\":\"a\"}]}"));
        client.calls.get(1).future.complete(EMPTY);
        assertTrue(handle.future().isCancelled());
        assertEquals(2, client.calls.size());
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

    /**
     * O1 + O2 regression: the theoretical worst case is 17 model requests
     * (main 1 + 3 attention follow-ups, 3 children x (1 + 3 attention follow-ups), 1 summary).
     * The old hard budget of 16 made the 17th dispatch return null, which the summary call site
     * turned into a silent "return" that never completed {@code handle.future()} - a permanent hang.
     * This test drives exactly that worst case and only requires that the turn converges in time.
     */
    @Test void worstCaseSeventeenRequestsConvergeInsteadOfHanging() throws Exception {
        FakeTransport client = new FakeTransport();
        FocusRequest focus = new FocusRequest("f1", "检查窗口", BlockBox.ofCorners(0, 64, 0, 0, 64, 0));
        FocusReader reader = request -> CompletableFuture.completedFuture("stone at 0 64 0");
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", reader, events::add);

        driveWorstCase(client, handle);

        AiPlan result = handle.future().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(17, client.calls.size(), "main 4 + children 12 + summary 1");
        assertEquals(4, client.calls.stream().filter(c -> c.request.agentId().equals("main")).count());
        assertEquals(12, client.calls.stream().filter(c -> c.request.agentId().startsWith("child-")).count());
        assertEquals(1, client.calls.stream().filter(c -> c.request.agentId().equals("summary")).count());
        assertTrue(events.stream().noneMatch(e -> e.text().contains("预算已用尽")));
    }

    /** Answers every new transport call until the turn converges or the driver gives up. */
    /**
     * O1: when the hard budget really is exhausted (a lowered budget here), every dispatch site
     * must converge the turn instead of returning silently. Main plan is still delivered, with
     * delegations stripped.
     */
    @Test void budgetExhaustionDegradesToMainPlanInsteadOfHanging() throws Exception {
        FakeTransport client = new FakeTransport();
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var limits = new AiOrchestrator.Limits(2, Duration.ofMillis(300), Duration.ofSeconds(10));
        var handle = new AiOrchestrator(client, limits).start("url", "key", PROFILE, List.of(), "world", events::add);

        client.calls.getFirst().future.complete(AiPlan.fromJson("""
                {"message":"main-plan","actions":[],"delegations":[{"id":"a","role":"a","task":"a"},{"id":"b","role":"b","task":"b"},{"id":"c","role":"c","task":"c"}]}
                """));
        assertEquals(2, client.calls.size(), "only the main request and the first child fit in the budget");

        AiPlan result = handle.future().get(5, TimeUnit.SECONDS);
        assertEquals("main-plan", result.message());
        assertTrue(result.delegations().isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.text().contains("预算已用尽")));
        assertEquals(1, events.stream().filter(e -> e.type() == ChatEvent.Type.PLAN).count());
    }

    /** O3: a child that never answers must not hold the turn; the stage times out and the turn finishes. */
    @Test void childStageTimeoutStillCompletesTheTurn() throws Exception {
        FakeTransport client = new FakeTransport();
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var limits = new AiOrchestrator.Limits(20, Duration.ofMillis(200), Duration.ofSeconds(10));
        var handle = new AiOrchestrator(client, limits).start("url", "key", PROFILE, List.of(), "world", events::add);
        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"delegations\":[{\"id\":\"a\",\"role\":\"设计\",\"task\":\"a\"}]}"));

        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (client.calls.size() < 3 && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(3, client.calls.size(), "summary is dispatched even though the child stalled");
        assertTrue(client.calls.get(2).request.task().contains("失败"));
        assertTrue(client.calls.get(1).cancelled, "the stalled child request is dropped");
        client.calls.get(2).future.complete(EMPTY);

        assertEquals(EMPTY, handle.future().get(5, TimeUnit.SECONDS));
        assertTrue(events.stream().anyMatch(e -> e.text().contains("超时")));
    }

    /** O3: a stalled focus read must fall back to "读取失败" and still ask the agent again. */
    @Test void focusReadTimeoutStillProducesFollowUpRequest() throws Exception {
        FakeTransport client = new FakeTransport();
        FocusReader reader = request -> new CompletableFuture<>();
        var limits = new AiOrchestrator.Limits(20, Duration.ofMillis(200), Duration.ofSeconds(10));
        var handle = new AiOrchestrator(client, limits).start("url", "key", PROFILE, List.of(), "world", reader, e -> {});
        FocusRequest focus = new FocusRequest("f1", "检查", BlockBox.ofCorners(0, 64, 0, 0, 64, 0));
        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"attentionRequests\":[" + focus.toJson() + "]}"));

        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (client.calls.size() < 2 && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(2, client.calls.size());
        assertTrue(client.calls.get(1).request.task().contains("读取失败"));
        client.calls.get(1).future.complete(EMPTY);
        assertEquals(EMPTY, handle.future().get(5, TimeUnit.SECONDS));
    }

    /** O3: without any fallback plan the turn-level deadline must still resolve the future. */
    @Test void turnDeadlineWithoutMainPlanCompletesExceptionally() {
        FakeTransport client = new FakeTransport();
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var limits = new AiOrchestrator.Limits(20, Duration.ofSeconds(5), Duration.ofMillis(150));
        var handle = new AiOrchestrator(client, limits).start("url", "key", PROFILE, List.of(), "world", events::add);

        ExecutionException error = assertThrows(ExecutionException.class, () -> handle.future().get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("超时"));
        assertTrue(client.calls.getFirst().cancelled);
        assertTrue(events.stream().anyMatch(e -> e.text().contains("编排超时")));
    }

    /** O3: once the main plan exists the deadline degrades to it instead of reporting a failure. */
    @Test void turnDeadlineDegradesToMainPlan() throws Exception {
        FakeTransport client = new FakeTransport();
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var limits = new AiOrchestrator.Limits(20, Duration.ofSeconds(10), Duration.ofMillis(250));
        var handle = new AiOrchestrator(client, limits).start("url", "key", PROFILE, List.of(), "world", events::add);
        client.calls.getFirst().future.complete(AiPlan.fromJson("""
                {"message":"main-plan","actions":[],"delegations":[{"id":"a","role":"a","task":"a"}]}
                """));

        AiPlan result = handle.future().get(5, TimeUnit.SECONDS);
        assertEquals("main-plan", result.message());
        assertTrue(result.delegations().isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.text().contains("编排超时")));
        assertEquals(1, events.stream().filter(e -> e.type() == ChatEvent.Type.PLAN).count());
    }

    /** O4: an asynchronously failed summary must fall back to the main plan, not discard the turn. */
    @Test void asynchronousSummaryFailureFallsBackToMainPlan() throws Exception {
        FakeTransport client = new FakeTransport();
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", events::add);
        client.calls.getFirst().future.complete(AiPlan.fromJson("""
                {"message":"main-plan","actions":[],"delegations":[{"id":"a","role":"a","task":"a"}]}
                """));
        client.calls.get(1).future.complete(EMPTY);
        client.calls.get(2).future.completeExceptionally(new IllegalStateException("secret provider error"));

        AiPlan result = handle.future().get(5, TimeUnit.SECONDS);
        assertEquals("main-plan", result.message());
        assertTrue(result.delegations().isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.agentId().equals("summary") && e.type() == ChatEvent.Type.FAILED));
        assertTrue(events.stream().noneMatch(e -> e.text().contains("secret")));
    }

    /**
     * U2: when the attention follow-up request itself fails, the old plan still carries its
     * attentionRequests, so the old code re-read the same windows and issued another model request
     * (up to three times) while the user was never told. The failed follow-up is now consumed: the
     * loop stops, the best plan so far is delivered, and the read count no longer grows.
     */
    @Test void failedAttentionFollowUpIsConsumedInsteadOfRereadingOldPlan() throws Exception {
        FakeTransport client = new FakeTransport();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        FocusReader reader = request -> { reads.incrementAndGet(); return CompletableFuture.completedFuture("stone at 0 64 0"); };
        FocusRequest focus = new FocusRequest("f1", "检查窗口", BlockBox.ofCorners(0, 64, 0, 0, 64, 0));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", reader, events::add);

        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"message\":\"kept\",\"actions\":[],\"attentionRequests\":[" + focus.toJson() + "]}"));
        assertEquals(2, client.calls.size());
        assertEquals(1, reads.get());
        client.calls.get(1).future.completeExceptionally(new IllegalStateException("provider dropped the follow-up"));

        AiPlan result = handle.future().get(5, TimeUnit.SECONDS);
        assertEquals("kept", result.message());
        assertEquals(2, client.calls.size(), "no extra model request after the failed follow-up");
        assertEquals(1, reads.get(), "the old plan's attention requests must not be read again");
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.FAILED && e.text().contains("跟进请求失败")));
        assertTrue(events.stream().noneMatch(e -> e.text().contains("provider dropped")), "provider details must not leak");
    }

    /**
     * U4: a failed read used to be flattened into the literal "读取失败", indistinguishable from
     * real block data. Each reason now gets its own explicit marker plus a do-not-speculate note,
     * while the read still produces a follow-up request and a final plan (existing semantics).
     */
    @Test void readFailuresAreLabelledWithReasonAndForbidSpeculation() throws Exception {
        String denied = followUpTaskForFailedRead(request -> CompletableFuture.failedFuture(new SecurityException("focus reader denied")));
        assertTrue(denied.contains("[f1]"), denied);
        assertTrue(denied.contains("[读取失败:被拒绝]"), denied);
        assertTrue(denied.contains("禁止凭空推测"), denied);

        String timeout = followUpTaskForFailedRead(request -> new CompletableFuture<>());
        assertTrue(timeout.contains("[读取失败:超时]"), timeout);

        String network = followUpTaskForFailedRead(request -> CompletableFuture.failedFuture(new java.io.IOException("connection reset")));
        assertTrue(network.contains("[读取失败:网络异常]"), network);
    }

    /**
     * U3: the global read budget may truncate one round. That used to be a silent break, so the
     * user and the model never learned that fewer windows were observed. A FAILED event now states
     * the truncation (the existing non-fatal-degradation channel; no new ChatEvent.Type).
     */
    @Test void readBudgetTruncationIsReportedInsteadOfSilentlySkipped() throws Exception {
        FakeTransport client = new FakeTransport();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        FocusReader reader = request -> { reads.incrementAndGet(); return CompletableFuture.completedFuture("stone at 0 64 0"); };
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        var handle = new AiOrchestrator(client).start("url", "key", PROFILE, List.of(), "world", reader, events::add);

        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!handle.future().isDone() && System.nanoTime() < giveUp) {
            Call next = client.calls.stream().filter(call -> !call.answered).findFirst().orElse(null);
            if (next == null) { Thread.sleep(2); continue; }
            next.answered = true;
            next.future.complete(readBudgetAnswer(client, next));
        }

        AiPlan result = handle.future().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(AiOrchestrator.TaskHandle.READ_BUDGET, reads.get(), "reads stop at the global budget");
        assertTrue(events.stream().anyMatch(e -> e.type() == ChatEvent.Type.FAILED && e.text().contains("注意力读取已达")),
                () -> events.toString());
        assertEquals(9, client.calls.size(), "main 4 + first child 4 + summary 1");
    }

    /** U4 helper: drives one failed focus read and returns the follow-up task text. */
    private static String followUpTaskForFailedRead(FocusReader reader) throws Exception {
        FakeTransport client = new FakeTransport();
        var limits = new AiOrchestrator.Limits(20, Duration.ofMillis(200), Duration.ofSeconds(10));
        var handle = new AiOrchestrator(client, limits).start("url", "key", PROFILE, List.of(), "world", reader, e -> {});
        FocusRequest focus = new FocusRequest("f1", "检查", BlockBox.ofCorners(0, 64, 0, 0, 64, 0));
        client.calls.getFirst().future.complete(AiPlan.fromJson("{\"actions\":[],\"attentionRequests\":[" + focus.toJson() + "]}"));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (client.calls.size() < 2 && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(2, client.calls.size(), "a failed read must still produce the follow-up request");
        String task = client.calls.get(1).request.task();
        client.calls.get(1).future.complete(EMPTY);
        assertEquals(EMPTY, handle.future().get(5, TimeUnit.SECONDS));
        return task;
    }

    /**
     * U3 driver: main and child answers carry three attention requests until the agent has been
     * answered three times, which consumes the shared 16-read budget inside the first child.
     */
    private static AiPlan readBudgetAnswer(FakeTransport client, Call call) {
        String agentId = call.request.agentId();
        if (agentId.equals("summary")) return EMPTY;
        int answered = 0;
        for (Call previous : client.calls) {
            if (previous == call) break;
            if (previous.request.agentId().equals(agentId)) answered++;
        }
        StringBuilder json = new StringBuilder("{\"message\":\"").append(agentId).append('-').append(answered).append("\",\"actions\":[]");
        if (answered < 3) {
            json.append(",\"attentionRequests\":[");
            for (int i = 0; i < 3; i++) {
                if (i > 0) json.append(',');
                json.append(new FocusRequest("f" + i, "检查", BlockBox.ofCorners(0, 64, 0, 0, 64, 0)).toJson());
            }
            json.append(']');
        }
        if (agentId.equals("main")) json.append(",\"delegations\":[{\"id\":\"a\",\"role\":\"设计\",\"task\":\"task-a\"}]");
        return AiPlan.fromJson(json.append('}').toString());
    }

    private static void driveWorstCase(FakeTransport client, AiOrchestrator.TaskHandle handle) throws Exception {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!handle.future().isDone() && System.nanoTime() < giveUp) {
            Call next = client.calls.stream().filter(call -> !call.answered).findFirst().orElse(null);
            if (next == null) { Thread.sleep(2); continue; }
            next.answered = true;
            next.future.complete(worstCaseAnswer(client, next));
        }
    }

    /**
     * Each agent (main and every child) burns its full attention allowance: the first three
     * responses per agent id carry one attention request, the fourth settles the agent.
     */
    private static AiPlan worstCaseAnswer(FakeTransport client, Call call) {
        String agentId = call.request.agentId();
        if (agentId.equals("summary")) return EMPTY;
        int answered = 0;
        for (Call previous : client.calls) {
            if (previous == call) break;
            if (previous.request.agentId().equals(agentId)) answered++;
        }
        boolean settled = answered >= 3;
        String focus = new FocusRequest("f" + answered, "检查", BlockBox.ofCorners(0, 64, 0, 0, 64, 0)).toJson().toString();
        StringBuilder json = new StringBuilder("{\"message\":\"").append(agentId).append('-').append(answered)
                .append("\",\"actions\":[]");
        if (!settled) json.append(",\"attentionRequests\":[").append(focus).append(']');
        if (agentId.equals("main")) {
            json.append(",\"delegations\":[{\"id\":\"a\",\"role\":\"设计\",\"task\":\"task-a\"},"
                    + "{\"id\":\"b\",\"role\":\"检查\",\"task\":\"task-b\"},{\"id\":\"c\",\"role\":\"材料\",\"task\":\"task-c\"}]");
        }
        return AiPlan.fromJson(json.append('}').toString());
    }

    private static class FakeTransport extends AiHttpClient {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        @Override public Exchange request(Request request, Consumer<ChatEvent> events) {
            Call call = new Call(request, events); calls.add(call);
            return new Exchange(call.future, () -> { call.cancelled = true; call.future.cancel(true); });
        }
    }
    private static final class Call {
        final AiHttpClient.Request request; final Consumer<ChatEvent> events;
        final CompletableFuture<AiPlan> future = new CompletableFuture<>(); boolean cancelled; boolean answered;
        Call(AiHttpClient.Request request, Consumer<ChatEvent> events) { this.request = request; this.events = events; }
    }
}
