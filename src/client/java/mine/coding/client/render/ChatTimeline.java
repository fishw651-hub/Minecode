package mine.coding.client.render;

import mine.coding.ai.transport.ChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scrollable, bounded view model for public agent output and reasoning summaries. */
public final class ChatTimeline {
    private static final int MAX_EVENTS = 400;
    private final List<ChatEvent> events = new ArrayList<>();

    public synchronized void append(ChatEvent event) {
        if (event == null) return;
        events.add(event);
        if (events.size() > MAX_EVENTS) events.remove(0);
    }

    public synchronized List<ChatEvent> events() { return List.copyOf(events); }
    public record Entry(String agentId, String role, String status, String output, String reasoning) {}
    public synchronized List<Entry> entries() {
        var entries = new ArrayList<MutableEntry>();
        Map<String, MutableEntry> active = new LinkedHashMap<>();
        for (ChatEvent event : events) {
            boolean separate = event.agentId().equals("user") || event.agentId().equals("server");
            MutableEntry entry = active.get(event.agentId());
            if (entry == null || separate || event.type() == ChatEvent.Type.QUEUED) {
                entry = new MutableEntry(event.agentId(), event.role()); entries.add(entry); active.put(event.agentId(), entry);
            }
            switch (event.type()) {
                case TEXT -> entry.output.append(event.text());
                case REASONING -> entry.reasoning.append(event.text());
                case PLAN -> { if (event.plan() != null) { entry.output.setLength(0); entry.output.append(event.plan().message()); entry.reasoning.setLength(0); entry.reasoning.append(event.plan().thoughtSummary()); } }
                default -> { entry.status = event.type().name(); if ((event.type() == ChatEvent.Type.FAILED || separate) && !event.text().isBlank()) entry.output.append(event.text()); }
            }
        }
        return entries.stream().map(item -> new Entry(item.id,item.role,item.status,item.output.toString(),item.reasoning.toString())).toList();
    }
    public synchronized void cancelRunning() {
        for (Entry entry : entries()) if (entry.status().equals("QUEUED") || entry.status().equals("RUNNING")) append(new ChatEvent(entry.agentId(),entry.role(),ChatEvent.Type.CANCELLED,"已取消",null));
    }
    private static final class MutableEntry {
        final String id, role; String status = "TEXT";
        final StringBuilder output = new StringBuilder(), reasoning = new StringBuilder();
        MutableEntry(String id, String role) { this.id = id; this.role = role; }
    }
}
