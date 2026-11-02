package mine.coding.client.render;

import mine.coding.client.ServerResponse;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure rendering of the audit log the server returns for {@code op:logs}.
 *
 * <p>The server sends the entries as {@code logs:[{message,at}]} next to a human readable
 * {@code message}; showing only {@link ServerResponse#displayText()} printed the header and hid
 * every line of the log itself.
 */
public final class AuditLogView {
    /** Upper bound for one panel block; the archive keeps up to 256 entries. */
    public static final int MAX_LINES = 40;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private AuditLogView() {}

    /** Every usable audit entry, oldest first, formatted as {@code HH:mm:ss · message}. */
    public static List<String> entries(ServerResponse response) {
        List<String> lines = new ArrayList<>();
        if (response == null) return List.of();
        for (var entry : response.objects("logs")) {
            String message = ServerResponse.text(entry, "message");
            if (message.isBlank()) continue;
            long at = ServerResponse.number(entry, "at", -1);
            lines.add(at < 0 ? message : TIME.format(Instant.ofEpochMilli(at)) + " · " + message);
        }
        return List.copyOf(lines);
    }

    /** One panel block: the server message followed by the newest entries that fit the budget. */
    public static String render(ServerResponse response) {
        List<String> entries = entries(response);
        StringBuilder text = new StringBuilder(response == null ? "" : response.displayText());
        if (entries.isEmpty()) return text.append("\n暂无日志").toString();
        if (entries.size() > MAX_LINES) {
            text.append("\n（仅显示最近 ").append(MAX_LINES).append(" 条，共 ").append(entries.size()).append(" 条）");
        }
        for (String entry : entries.subList(Math.max(0, entries.size() - MAX_LINES), entries.size())) {
            text.append('\n').append(entry);
        }
        return text.toString();
    }
}
