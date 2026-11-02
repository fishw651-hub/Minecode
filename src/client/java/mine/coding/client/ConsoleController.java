package mine.coding.client;

import mine.coding.ai.AiPlan;
import mine.coding.ai.transport.ChatEvent;
import java.util.List;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;

/** Pure client state; Minecraft integration drains queued events on the main thread. */
public final class ConsoleController {
    private final Queue<ChatEvent> pending = new ArrayDeque<>();
    private final List<ChatEvent> timeline = new ArrayList<>();
    private AiPlan preview;
    private boolean busy;
    private String retryText = "";

    public synchronized void accept(ChatEvent event) { if (event != null) pending.add(event); }
    public synchronized List<ChatEvent> drainEvents() {
        List<ChatEvent> fresh = new ArrayList<>();
        while (!pending.isEmpty()) {
            ChatEvent event = pending.remove();
            timeline.add(event);
            fresh.add(event);
            if (event.type() == ChatEvent.Type.PLAN && event.plan() != null && "summary".equals(event.agentId())) preview = event.plan();
        }
        return fresh;
    }
    public synchronized List<ChatEvent> timeline() { return List.copyOf(timeline); }
    public synchronized Optional<AiPlan> preview() { return Optional.ofNullable(preview); }
    public synchronized Optional<AiPlan> takePreview() { AiPlan value = preview; preview = null; return Optional.ofNullable(value); }
    public synchronized boolean beginSend(String text) {
        if (busy || text == null || text.isBlank()) return false;
        busy = true; retryText = text; preview = null; return true;
    }
    public synchronized boolean busy() { return busy; }
    public synchronized void finish() { busy = false; }
    public synchronized void cancel() { busy = false; accept(new ChatEvent("main", "主Agent", ChatEvent.Type.CANCELLED, "已取消", null)); }
    public synchronized String retryText() { return retryText; }
}
