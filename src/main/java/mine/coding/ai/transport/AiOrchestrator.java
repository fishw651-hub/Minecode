package mine.coding.ai.transport;

import mine.coding.ai.AiAction;
import mine.coding.ai.AiPlan;
import mine.coding.ai.AiProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Coordinates one main request, up to three depth-one children, and one summary request. */
public final class AiOrchestrator {
    private final AiHttpClient transport;
    private final AtomicReference<TaskHandle> active = new AtomicReference<>();

    public AiOrchestrator(AiHttpClient transport) { this.transport = transport; }

    public synchronized TaskHandle start(String endpoint, String key, AiProfile profile, List<ChatMessage> history,
                                         String worldContext, Consumer<ChatEvent> callback) {
        TaskHandle old = active.getAndSet(null); if (old != null) old.cancel();
        TaskHandle handle = new TaskHandle(callback);
        active.set(handle);
        handle.emit(new ChatEvent("main", "主Agent", ChatEvent.Type.QUEUED, "主Agent排队中", null));
        AiHttpClient.Request request = new AiHttpClient.Request(endpoint, key, profile, history, worldContext, "main", "主Agent", "", true);
        handle.main = transport.request(request, handle::forward);
        handle.emit(new ChatEvent("main", "主Agent", ChatEvent.Type.RUNNING, "主Agent处理中", null));
        handle.main.future().whenComplete((plan, failure) -> {
            if (handle.cancelled.get() || handle.future.isCancelled()) return;
            if (failure != null) { handle.fail("主Agent失败，请重试"); handle.future.completeExceptionally(unwrap(failure)); return; }
            List<AiPlan.Delegation> delegations = plan.delegations();
            if (delegations.isEmpty()) { handle.complete(sanitize(plan)); return; }
            if (delegations.size() > 3) { handle.emit(new ChatEvent("main", "主Agent", ChatEvent.Type.FAILED, "派遣超过3个，已限制为前3个", null)); delegations = delegations.subList(0, 3); }
            runChildren(handle, endpoint, key, profile, history, worldContext, delegations);
        });
        return handle;
    }

    private void runChildren(TaskHandle handle, String endpoint, String key, AiProfile profile, List<ChatMessage> history,
                             String worldContext, List<AiPlan.Delegation> delegations) {
        List<CompletableFuture<AiPlan>> futures = new ArrayList<>();
        for (int i = 0; i < delegations.size(); i++) {
            AiPlan.Delegation delegation = delegations.get(i); String id = "child-" + delegation.id();
            handle.emit(new ChatEvent(id, delegation.role(), ChatEvent.Type.QUEUED, "子Agent已排队", null));
            AiHttpClient.Request request = new AiHttpClient.Request(endpoint, key, profile, history, worldContext, id, delegation.role(), delegation.task(), true);
            AiHttpClient.Exchange exchange = transport.request(request, handle::forward); handle.exchanges.add(exchange); futures.add(exchange.future());
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, ignoredFailure) -> {
            if (handle.cancelled.get() || handle.future.isCancelled()) return;
            StringBuilder report = new StringBuilder("子Agent结果：\n");
            for (int i = 0; i < futures.size(); i++) {
                try {
                    AiPlan child = futures.get(i).join();
                    if (!child.delegations().isEmpty()) handle.emit(new ChatEvent("child-" + delegations.get(i).id(), delegations.get(i).role(), ChatEvent.Type.FAILED, "子Agent禁止再次派遣，已忽略其派遣请求", null));
                    report.append("[完成 ").append(delegations.get(i).role()).append("] ").append(child.message()).append('\n');
                } catch (CompletionException failure) {
                    handle.emit(new ChatEvent("child-" + delegations.get(i).id(), delegations.get(i).role(), ChatEvent.Type.FAILED, "子Agent失败，已纳入汇总", null));
                    report.append("[失败 ").append(delegations.get(i).role()).append("] 可用结果为空\n");
                }
            }
            AiHttpClient.Request summary = new AiHttpClient.Request(endpoint, key, profile, history, worldContext, "summary", "汇总Agent", report.toString(), true);
            handle.emit(new ChatEvent("summary", "汇总Agent", ChatEvent.Type.QUEUED, "正在汇总子Agent结果", null));
            AiHttpClient.Exchange exchange = transport.request(summary, handle::forward); handle.exchanges.add(exchange); handle.summary = exchange;
            exchange.future().whenComplete((plan, failure) -> {
                if (handle.cancelled.get() || handle.future.isCancelled()) return;
                if (failure != null) { handle.fail("汇总失败，请重试"); handle.future.completeExceptionally(unwrap(failure)); }
                else handle.complete(sanitize(plan));
            });
        });
    }

    private static AiPlan sanitize(AiPlan plan) {
        Map<String, AiAction> unique = new LinkedHashMap<>();
        for (AiAction action : plan.actions()) unique.putIfAbsent(action.id(), action);
        return new AiPlan(plan.message(), plan.thoughtSummary(), new ArrayList<>(unique.values()), plan.memoryDrafts(), plan.skillDrafts(), List.of());
    }
    private static Throwable unwrap(Throwable failure) { return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure; }

    public static final class TaskHandle {
        private final Consumer<ChatEvent> callback; private final CompletableFuture<AiPlan> future = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(); private final List<AiHttpClient.Exchange> exchanges = new ArrayList<>();
        private AiHttpClient.Exchange main, summary;
        TaskHandle(Consumer<ChatEvent> callback) { this.callback = callback == null ? e -> {} : callback; }
        public CompletableFuture<AiPlan> future() { return future; }
        public synchronized void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            if (main != null) main.cancel(); if (summary != null) summary.cancel(); for (AiHttpClient.Exchange exchange : exchanges) exchange.cancel();
            future.cancel(false);
            try { callback.accept(new ChatEvent("main", "主Agent", ChatEvent.Type.CANCELLED, "已取消", null)); } catch (RuntimeException ignored) {}
        }
        private void complete(AiPlan plan) { if (cancelled.get() || future.isDone()) return; emit(new ChatEvent("summary", "汇总Agent", ChatEvent.Type.PLAN, "最终计划已就绪", plan)); emit(new ChatEvent("summary", "汇总Agent", ChatEvent.Type.COMPLETED, "编排完成", null)); future.complete(plan); }
        private void fail(String text) { if (!cancelled.get()) emit(new ChatEvent("main", "主Agent", ChatEvent.Type.FAILED, text, null)); }
        private void forward(ChatEvent event) { if (!cancelled.get() && !future.isDone()) emit(event); }
        private void emit(ChatEvent event) { if (!cancelled.get()) try { callback.accept(event); } catch (RuntimeException ignored) {} }
    }
}
