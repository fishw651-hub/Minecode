package mine.coding.ai.transport;

import mine.coding.ai.AiAction;
import mine.coding.ai.AiPlan;
import mine.coding.ai.AiProfile;
import mine.coding.ai.spatial.FocusRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Coordinates one main request, up to three depth-one children, and one summary request.
 *
 * <p>Convergence contract: every path that can no longer make progress must resolve
 * {@link TaskHandle#future()} - either with the best plan available ({@link #degrade}) or
 * exceptionally. No dispatch outcome may end in a silent {@code return} that leaves the future
 * pending, and every stage is bounded by a timeout so a stalled provider cannot hang the turn.</p>
 */
public final class AiOrchestrator {
    /**
     * Hard per-turn model-request budget.
     *
     * <p>Worst case, counted from the invariants that are enforced elsewhere
     * ({@link AiPlan} rejects more than 3 delegations and more than 3 attention requests per plan;
     * the attention loop stops at 3 rounds):
     * <ul>
     *   <li>main agent: 1 initial request + 3 attention follow-ups = 4</li>
     *   <li>children: 3 x (1 initial + 3 attention follow-ups) = 12</li>
     *   <li>summary: 1</li>
     * </ul>
     * Total 17. The budget is therefore 20, not 16: with 16 the legal worst case exceeded the cap,
     * which turned a legitimate turn into a permanent hang (the rejected dispatch returned null and
     * the summary call site returned without completing the turn).</p>
     *
     * <p>Exhaustion is still possible if a caller configures a lower budget or a future change adds
     * a stage, so every dispatch site degrades explicitly instead of failing silently.</p>
     */
    public static final int MAX_MODEL_REQUESTS = 20;

    private final AiHttpClient transport;
    private final Limits limits;
    private final AtomicReference<TaskHandle> active = new AtomicReference<>();
    private static final ScheduledExecutorService DEADLINES = Executors.newScheduledThreadPool(1, r -> {
        Thread thread = new Thread(r, "minecode-orchestrator-deadline"); thread.setDaemon(true); return thread;
    });

    public AiOrchestrator(AiHttpClient transport) { this(transport, Limits.defaults()); }

    public AiOrchestrator(AiHttpClient transport, Limits limits) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Turn-level safety limits. Defaults are sized above the worst case of the 60s transport
     * deadline per request (main stage 4x60s, children stage 4x60s, summary 60s), so the normal
     * path never trips them; they exist so a stalled provider degrades instead of hanging.
     */
    public record Limits(int modelRequests, Duration stageTimeout, Duration turnDeadline) {
        public Limits {
            if (modelRequests < 1) throw new IllegalArgumentException("modelRequests must be positive");
            if (stageTimeout == null || stageTimeout.isNegative() || stageTimeout.isZero()) throw new IllegalArgumentException("stageTimeout must be positive");
            if (turnDeadline == null || turnDeadline.isNegative() || turnDeadline.isZero()) throw new IllegalArgumentException("turnDeadline must be positive");
        }
        public static Limits defaults() {
            return new Limits(MAX_MODEL_REQUESTS, Duration.ofMinutes(5), Duration.ofMinutes(15));
        }
    }

    public TaskHandle start(String endpoint, String key, AiProfile profile, List<ChatMessage> history,
                                         String worldContext, Consumer<ChatEvent> callback) {
        return start(endpoint, key, profile, history, worldContext, null, callback);
    }

    public TaskHandle start(String endpoint, String key, AiProfile profile, List<ChatMessage> history,
                            String worldContext, FocusReader focusReader, Consumer<ChatEvent> callback) {
        TaskHandle handle = new TaskHandle(this, callback);
        handle.future.whenComplete((ignored, failure) -> { if (handle.future.isCancelled()) handle.cancel(); });
        TaskHandle old = active.getAndSet(handle); if (old != null) old.cancel();
        handle.armTurnDeadline();
        handle.emit(new ChatEvent("main", "主Agent", ChatEvent.Type.QUEUED, "主Agent排队中", null));
        AiHttpClient.Request request = new AiHttpClient.Request(endpoint, key, profile, history, worldContext, "main", "主Agent", "", true);
        try {
            handle.main = handle.dispatch(transport, request);
            // Null means the handle is already terminal (cancelled, replaced, or completed): the
            // future is resolved, so returning here cannot hang the caller.
            if (handle.main == null) return handle;
        } catch (BudgetExhaustedException exhausted) {
            handle.failTurn("main", "主Agent", "模型调用预算已用尽");
            return handle;
        } catch (RuntimeException error) {
            handle.fail("主Agent请求失败，请重试");
            handle.future.completeExceptionally(new IOException("AI请求失败，请重试"));
            return handle;
        }
        handle.emit(new ChatEvent("main", "主Agent", ChatEvent.Type.RUNNING, "主Agent处理中", null));
        handle.main.future().whenComplete((plan, failure) -> {
            if (handle.isTerminal()) return;
            if (failure != null) { handle.failTurn("main", "主Agent", safeReason(failure)); return; }
            handle.rememberFallback(plan);
            handle.emit(new ChatEvent("main", "主Agent", ChatEvent.Type.COMPLETED, "主Agent完成", null));
            continueAfterAttention(handle, request, plan, focusReader, 0, resolved -> {
                // AiPlan itself rejects more than three delegations (constructor and parser), so a
                // defensive truncation branch here would be unreachable dead code (O9).
                List<AiPlan.Delegation> delegations = resolved.delegations();
                if (delegations.isEmpty()) { handle.complete(sanitize(resolved)); return; }
                runChildren(handle, endpoint, key, profile, history, worldContext, resolved, delegations, focusReader);
            });
        });
        return handle;
    }

    private void runChildren(TaskHandle handle, String endpoint, String key, AiProfile profile, List<ChatMessage> history,
                             String worldContext, AiPlan mainPlan, List<AiPlan.Delegation> delegations, FocusReader focusReader) {
        handle.rememberFallback(mainPlan);
        List<CompletableFuture<AiPlan>> futures = new ArrayList<>();
        List<AiHttpClient.Exchange> childExchanges = new ArrayList<>();
        for (int i = 0; i < delegations.size(); i++) {
            AiPlan.Delegation delegation = delegations.get(i); String id = "child-" + delegation.id();
            handle.emit(new ChatEvent(id, delegation.role(), ChatEvent.Type.QUEUED, "子Agent已排队", null));
            if (handle.isTerminal()) return;
            handle.emit(new ChatEvent(id, delegation.role(), ChatEvent.Type.RUNNING, "子Agent处理中", null));
            String childTask = delegation.task();
            if (delegation.focus() != null) childTask += "\n子Agent专属只读focus：" + delegation.focus().toJson();
            AiHttpClient.Request request = new AiHttpClient.Request(endpoint, key, profile, history, worldContext, id, delegation.role(), childTask, true);
            if (handle.isTerminal()) return;
            AiHttpClient.Exchange exchange;
            try { exchange = handle.dispatch(transport, request); }
            catch (BudgetExhaustedException exhausted) {
                // Stop spending budget, but still run the summary stage so the turn converges with
                // the main plan rather than leaving the future pending.
                handle.emit(new ChatEvent(id, delegation.role(), ChatEvent.Type.FAILED, "模型调用预算已用尽，已跳过剩余子Agent", null));
                futures.add(CompletableFuture.failedFuture(new IOException("模型调用预算已用尽")));
                break;
            } catch (RuntimeException error) {
                handle.emit(new ChatEvent(id, delegation.role(), ChatEvent.Type.FAILED, "子Agent请求失败，已纳入汇总", null));
                futures.add(CompletableFuture.failedFuture(new IOException("子Agent请求失败")));
                continue;
            }
            if (exchange == null) return;
            childExchanges.add(exchange);
            CompletableFuture<AiPlan> childResult = new CompletableFuture<>();
            futures.add(childResult);
            exchange.future().whenComplete((plan, failure) -> {
                if (handle.isTerminal() || handle.isChildStageClosed()) return;
                if (failure == null) {
                    continueAfterAttention(handle, request, plan, focusReader, 0, child -> {
                        handle.emit(new ChatEvent(id, delegation.role(), ChatEvent.Type.COMPLETED, "子Agent完成", null));
                        childResult.complete(child);
                    });
                } else {
                    handle.emit(new ChatEvent(id, delegation.role(), ChatEvent.Type.FAILED, "子Agent失败，已纳入汇总", null));
                    childResult.completeExceptionally(failure);
                }
            });
        }
        // Bound the child stage: a provider that never answers must not keep the turn alive forever.
        // Each future is mapped to a never-failing one first so that allOf waits for every child
        // (a single child failure must not short-circuit the others) and only the timeout can fire.
        CompletableFuture<?>[] tracked = futures.stream()
                .map(child -> child.exceptionally(error -> null))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(tracked)
                .orTimeout(limits.stageTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, stageFailure) -> {
            if (handle.isTerminal()) return;
            boolean timedOut = stageFailure != null;
            handle.closeChildStage();
            if (timedOut) {
                handle.emit(new ChatEvent("main", "主Agent", ChatEvent.Type.FAILED, "子Agent阶段超时，已用已有结果汇总", null));
                for (AiHttpClient.Exchange exchange : childExchanges) {
                    if (!exchange.future().isDone()) exchange.cancel();
                }
            }
            StringBuilder report = new StringBuilder("请结合主Agent初始计划与子Agent完整JSON结果生成最终计划，禁止再次派遣。\n主Agent初始计划：\n")
                    .append(mainPlan.toJson()).append("\n子Agent结果：\n");
            for (int i = 0; i < futures.size(); i++) {
                AiPlan.Delegation delegation = delegations.get(i);
                String id = "child-" + delegation.id();
                AiPlan child = settled(futures.get(i));
                if (child == null) {
                    String reason = timedOut ? "子Agent超时，已纳入汇总" : "子Agent失败，已纳入汇总";
                    handle.emit(new ChatEvent(id, delegation.role(), ChatEvent.Type.FAILED, reason, null));
                    report.append("[失败 ").append(delegation.role()).append("] 可用结果为空\n");
                    continue;
                }
                if (!child.delegations().isEmpty()) handle.emit(new ChatEvent(id, delegation.role(), ChatEvent.Type.FAILED, "子Agent禁止再次派遣，已忽略其派遣请求", null));
                report.append("[完成 ").append(delegation.role()).append("] ").append(child.toJson()).append('\n');
            }
            AiHttpClient.Request summary = new AiHttpClient.Request(endpoint, key, profile, history, worldContext, "summary", "汇总Agent", report.toString(), true);
            handle.emit(new ChatEvent("summary", "汇总Agent", ChatEvent.Type.QUEUED, "正在汇总子Agent结果", null));
            if (handle.isTerminal()) return;
            handle.emit(new ChatEvent("summary", "汇总Agent", ChatEvent.Type.RUNNING, "汇总Agent处理中", null));
            AiHttpClient.Exchange exchange;
            try { exchange = handle.dispatch(transport, summary); }
            catch (BudgetExhaustedException exhausted) {
                handle.degrade(mainPlan, "summary", "汇总Agent", "模型调用预算已用尽，已回落到主Agent计划");
                return;
            } catch (RuntimeException error) {
                // O4: a failed summary must not discard work that already succeeded.
                handle.degrade(mainPlan, "summary", "汇总Agent", "汇总请求失败，已回落到主Agent计划");
                return;
            }
            if (exchange == null) return;
            handle.summary = exchange;
            exchange.future().whenComplete((plan, failure) -> {
                if (handle.isTerminal()) return;
                if (failure != null || plan == null) {
                    // O4: degrade to the main plan instead of failing the whole turn.
                    handle.degrade(mainPlan, "summary", "汇总Agent", failure != null ? "汇总失败，已回落到主Agent计划" : "汇总结果为空，已回落到主Agent计划");
                    return;
                }
                handle.complete(sanitize(plan));
            });
        });
    }

    private void continueAfterAttention(TaskHandle handle, AiHttpClient.Request request, AiPlan plan,
                                        FocusReader reader, int rounds, Consumer<AiPlan> continuation) {
        if (reader == null || plan.attentionRequests().isEmpty() || rounds >= 3 || (plan.projectDraft() != null && !hasApprovedProject(request.worldContext()))) { continuation.accept(plan); return; }
        if (handle.isTerminal()) return;
        List<FocusRequest> requests = plan.attentionRequests().subList(0, Math.min(3, plan.attentionRequests().size()));
        List<CompletableFuture<String>> reads = new ArrayList<>();
        int reserved = 0;
        for (FocusRequest focus : requests) {
            if (!handle.reserveRead()) break;
            reserved++;
            try { reads.add(reader.read(focus)); } catch (RuntimeException error) { reads.add(CompletableFuture.failedFuture(error)); }
        }
        // U3: a truncated read set used to break out of the loop silently, so neither the user nor
        // the model learned that this round observed fewer windows than the plan asked for. Explain
        // it through the existing FAILED type: it is already this codebase's channel for non-fatal
        // degradation on an agent's timeline (stage timeout, fallback, dropped child); COMPLETED
        // would falsely mark the agent done, RUNNING would claim progress that is not happening,
        // and TEXT is forwarded model content (it would be re-read as assistant prose). No new
        // ChatEvent.Type is introduced because that would ripple into the client UI, out of scope.
        if (reserved < requests.size()) {
            handle.emit(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.FAILED,
                    "注意力读取已达 " + TaskHandle.READ_BUDGET + " 次上限，本轮仅读取 " + reserved + "/" + requests.size() + " 个窗口，其余窗口已跳过", null));
        }
        if (reads.isEmpty()) { continuation.accept(plan); return; }
        // Bound the read stage and degrade to whatever already arrived (O3).
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
                .orTimeout(limits.stageTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, readFailure) -> {
            if (handle.isTerminal()) return;
            StringBuilder task = new StringBuilder(request.task()).append("\n注意力读取结果：");
            for (int i = 0; i < reads.size(); i++) {
                String value = completedRead(reads.get(i));
                if (value == null) value = "[读取失败:" + readFailureReason(reads.get(i)) + "] 该区域数据不可用，禁止凭空推测其方块、实体与地形";
                task.append("\n[").append(requests.get(i).id()).append("] ").append(value, 0, Math.min(value.length(), 8192));
            }
            AiHttpClient.Request followup = new AiHttpClient.Request(request.endpoint(), request.apiKey(), request.profile(), request.history(), request.worldContext(), request.agentId(), request.role(), task.toString(), request.stream());
            AiHttpClient.Exchange exchange;
            try { exchange = handle.dispatch(transport, followup); }
            catch (BudgetExhaustedException exhausted) { continuation.accept(plan); return; }
            catch (RuntimeException error) { continuation.accept(plan); return; }
            if (exchange == null) return;
            exchange.future().whenComplete((next, nextFailure) -> {
                if (handle.isTerminal()) return;
                // U2: the old code fed the previous plan back into the recursion on failure. That
                // plan still carried its attentionRequests, so the next round re-read the same focus
                // windows, sent another model request and silently burned budget (up to three times)
                // without ever telling the user. A failed follow-up is now treated as consumed: the
                // loop ends and the best plan so far is used. A failed *read* is still non-fatal and
                // still produces a plan (that path only labels the read and continues).
                if (nextFailure != null || next == null) {
                    handle.emit(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.FAILED,
                            "跟进请求失败，已用当前计划继续（不再重复读取同一批窗口）", null));
                    continuation.accept(plan);
                    return;
                }
                continueAfterAttention(handle, followup, next, reader, rounds + 1, continuation);
            });
        });
    }

    private static boolean hasApprovedProject(String worldContext) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(worldContext).getAsJsonObject();
            com.google.gson.JsonElement project = root.get("project");
            return project != null && project.isJsonObject() && project.getAsJsonObject().get("project") != null && project.getAsJsonObject().get("project").isJsonObject();
        } catch (RuntimeException ignored) { return false; }
    }

    /** Reads a completed future without ever blocking; a pending or failed future yields {@code null}. */
    private static <T> T settled(CompletableFuture<T> future) {
        if (future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) return null;
        try { return future.join(); } catch (CompletionException | CancellationException ignored) { return null; }
    }

    /** Like {@link #settled} but never blocks; used for focus reads, which may still be pending. */
    private static String completedRead(CompletableFuture<String> future) {
        if (future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) return null;
        try { return future.join(); } catch (CompletionException | CancellationException ignored) { return null; }
    }

    /**
     * U4: converts a failed/pending focus read into a human-readable reason. The stage keeps no
     * exception of its own, so "still pending" means the bounded read stage expired first.
     */
    private static String readFailureReason(CompletableFuture<String> future) {
        if (future == null || !future.isDone()) return "超时";
        if (future.isCancelled()) return "已取消";
        try { future.join(); return "未知原因"; }
        catch (CompletionException error) { return classifyReadFailure(error.getCause() == null ? error : error.getCause()); }
        catch (CancellationException error) { return "已取消"; }
    }

    private static String classifyReadFailure(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException || error instanceof java.net.http.HttpTimeoutException) return "超时";
        if (error instanceof CancellationException) return "已取消";
        if (error instanceof SecurityException) return "被拒绝";
        String message = error.getMessage() == null ? "" : error.getMessage();
        // The server-side focus path rejects out-of-scope / unloaded / unapproved reads with a
        // message containing "拒绝" (MineCodeService error responses surface it as
        // IllegalStateException), so the marker covers that class without depending on server code.
        if (message.contains("拒绝")) return "被拒绝";
        if (error instanceof IOException) return "网络异常";
        return "未知原因";
    }

    private static AiPlan sanitize(AiPlan plan) {
        Map<String, AiAction> unique = new LinkedHashMap<>();
        for (AiAction action : plan.actions()) unique.putIfAbsent(action.id(), action);
        return new AiPlan(plan.message(), plan.thoughtSummary(), new ArrayList<>(unique.values()), plan.memoryDrafts(), plan.skillDrafts(), List.of(), plan.projectDraft(), plan.attentionRequests(), plan.checkpoint());
    }
    private static Throwable unwrap(Throwable failure) { return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure; }
    private static String safeReason(Throwable failure) { Throwable cause = unwrap(failure); return cause instanceof AiHttpClient.ApiException ? cause.getMessage() : "模型请求失败，请检查网络或配置后重试"; }

    /** Raised when a turn exceeded its hard model-request budget; every call site degrades explicitly. */
    static final class BudgetExhaustedException extends RuntimeException {
        BudgetExhaustedException() { super("模型调用预算已用尽", null, false, false); }
    }

    public static final class TaskHandle {
        /**
         * Global bound on the read-only focus observations one turn may request. It is above the
         * modelled worst case of the current loop (main 3 + 3 children x 3 rounds = 12 reads at one
         * attention request per response) and below the extreme legal case (4 agents x 3 rounds x 3
         * requests = 36), so it normally never trips; when it does, U3 reports the truncation.
         */
        static final int READ_BUDGET = 16;

        private final AiOrchestrator owner;
        private final Consumer<ChatEvent> callback; private final CompletableFuture<AiPlan> future = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(); private final List<AiHttpClient.Exchange> exchanges = new ArrayList<>();
        private final AtomicInteger attentionReads = new AtomicInteger();
        private final AtomicInteger modelRequests = new AtomicInteger();
        private final AtomicBoolean childStageClosed = new AtomicBoolean();
        private final Map<String, ChatEvent> agentStates = new LinkedHashMap<>();
        private volatile AiPlan fallbackPlan;
        private volatile ScheduledFuture<?> deadline;
        private AiHttpClient.Exchange main, summary;
        TaskHandle(AiOrchestrator owner, Consumer<ChatEvent> callback) { this.owner = owner; this.callback = callback == null ? e -> {} : callback; }
        public CompletableFuture<AiPlan> future() { return future; }

        /** True once this handle can no longer make progress: cancelled, resolved, or replaced. */
        boolean isTerminal() { return cancelled.get() || future.isDone() || owner.active.get() != this; }
        boolean isChildStageClosed() { return childStageClosed.get(); }
        void closeChildStage() { childStageClosed.set(true); }
        void rememberFallback(AiPlan plan) { this.fallbackPlan = plan; }
        private boolean reserveRead() { return !isTerminal() && attentionReads.getAndIncrement() < READ_BUDGET; }

        /** Arms the whole-turn deadline (O3); always disarmed when the future resolves. */
        void armTurnDeadline() {
            deadline = DEADLINES.schedule(() -> {
                if (isTerminal()) return;
                AiPlan fallback = fallbackPlan;
                // Resolve the turn first so late transport callbacks observe a terminal handle,
                // then drop the in-flight requests.
                if (fallback != null) { degrade(fallback, "main", "主Agent", "编排超时，已回落到主Agent计划"); }
                else { failTurn("main", "主Agent", "编排超时，请重试"); }
                cancelPendingExchanges();
            }, owner.limits.turnDeadline().toMillis(), TimeUnit.MILLISECONDS);
            future.whenComplete((ignored, failure) -> { ScheduledFuture<?> armed = deadline; if (armed != null) armed.cancel(false); });
        }

        public synchronized void cancel() {
            if (future.isDone() && !future.isCancelled()) return;
            if (!cancelled.compareAndSet(false, true)) return;
            cancelPendingExchanges();
            future.cancel(false);
            if (owner.active.get() != this) return;
            for (ChatEvent state : List.copyOf(agentStates.values())) {
                if (state.type() == ChatEvent.Type.QUEUED || state.type() == ChatEvent.Type.RUNNING) {
                    try { callback.accept(new ChatEvent(state.agentId(), state.role(), ChatEvent.Type.CANCELLED, "已取消", null)); } catch (RuntimeException ignored) {}
                }
            }
        }

        private void cancelPendingExchanges() {
            List<AiHttpClient.Exchange> pending = List.copyOf(exchanges).stream().filter(exchange -> !exchange.future().isDone()).toList();
            for (AiHttpClient.Exchange exchange : pending) exchange.cancel();
        }

        private synchronized void complete(AiPlan plan) { if (cancelled.get() || future.isDone() || owner.active.get() != this) return; emit(new ChatEvent("summary", "汇总Agent", ChatEvent.Type.PLAN, "最终计划已就绪", plan)); if (cancelled.get() || future.isDone() || owner.active.get() != this) return; emit(new ChatEvent("summary", "汇总Agent", ChatEvent.Type.COMPLETED, "编排完成", null)); future.complete(plan); }

        /**
         * Converges the turn with the best plan available (O1/O4): strips delegations through
         * {@link #sanitize}, emits an explanatory FAILED event, and always resolves the future.
         */
        private synchronized void degrade(AiPlan fallback, String agentId, String role, String reason) {
            if (isTerminal()) return;
            emit(new ChatEvent(agentId, role, ChatEvent.Type.FAILED, reason, null));
            if (isTerminal()) return;
            if (fallback == null) { future.completeExceptionally(new IOException(reason)); return; }
            complete(sanitize(fallback));
        }

        /** Resolves the future exceptionally with a safe, curated reason. */
        private synchronized void failTurn(String agentId, String role, String reason) {
            if (isTerminal()) return;
            emit(new ChatEvent(agentId, role, ChatEvent.Type.FAILED, reason, null));
            if (isTerminal()) return;
            future.completeExceptionally(new IOException(reason));
        }

        private synchronized void fail(String text) { if (!cancelled.get() && !future.isDone()) emit(new ChatEvent("main", "主Agent", ChatEvent.Type.FAILED, text, null)); }
        private synchronized void forward(ChatEvent event) { if (event.type() != ChatEvent.Type.TEXT && event.type() != ChatEvent.Type.REASONING) return; if (!cancelled.get() && !future.isDone()) emit(event); }
        private synchronized AiHttpClient.Exchange dispatch(AiHttpClient transport, AiHttpClient.Request request) {
            // Returns null only when this handle is already terminal, which means the future is
            // already resolved - callers may return without resolving it again.
            if (isTerminal()) return null;
            if (modelRequests.incrementAndGet() > owner.limits.modelRequests()) {
                modelRequests.decrementAndGet();
                emit(new ChatEvent(request.agentId(), request.role(), ChatEvent.Type.FAILED, "模型调用预算已用尽", null));
                throw new BudgetExhaustedException();
            }
            AiHttpClient.Exchange exchange = transport.request(request, this::forward);
            if (isTerminal()) { exchange.cancel(); return null; }
            exchanges.add(exchange);
            return exchange;
        }
        /**
         * U5 (evaluated, deliberately unchanged): events are delivered synchronously while the
         * handle monitor is held, and the callback must not block.
         *
         * <p>R18 noted the deadlock/pin risk of calling an external callback inside a
         * {@code synchronized} block. Publishing through a queue was rejected: the convergence and
         * cancellation tests rely on the callback running before the emitting statement returns
         * (a QUEUED event that calls {@code cancel()} must stop the next dispatch, which is exactly
         * what {@code cancellationDuringChildQueuedEventPreventsItsRequest} and
         * {@code cancellingSummaryQueuedEventPreventsSummaryRequest} assert), and the UI relies on
         * {@code PLAN < COMPLETED < future completion} global order. A queue would make both
         * asynchronous and reorder events across emitting threads unless the callback is still
         * drained inline, which reintroduces the same lock - only now split across two monitors with
         * no reentrancy argument. The safe improvement therefore is the documented contract below,
         * not a semantic change: production callbacks must be fast and non-blocking (the console
         * callback only enqueues onto the game thread), and callback exceptions are swallowed.
         */
        private synchronized void emit(ChatEvent event) {
            if (!cancelled.get() && !future.isDone() && owner.active.get() == this) {
                if (List.of(ChatEvent.Type.QUEUED, ChatEvent.Type.RUNNING, ChatEvent.Type.COMPLETED, ChatEvent.Type.FAILED, ChatEvent.Type.CANCELLED).contains(event.type())) agentStates.put(event.agentId(), event);
                try { callback.accept(event); } catch (RuntimeException ignored) {}
            }
        }
    }
}
