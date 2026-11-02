package mine.coding.client;

import mine.coding.client.render.AuditLogView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** C4: "查看日志" must render the audit entries the server sends in {@code logs}. */
class AuditLogViewTest {
    @Test void rendersEveryAuditEntryAfterTheServerMessage() {
        ServerResponse response = ServerResponse.parse("{\"event\":\"logs\",\"message\":\"本玩家世界审计日志\","
                + "\"logs\":[{\"message\":\"confirmed action FILL\",\"at\":1730000000000},"
                + "{\"message\":\"action rejected\",\"at\":1730000001000}]}");
        String text = AuditLogView.render(response);
        assertTrue(text.startsWith("本玩家世界审计日志"), text);
        assertTrue(text.contains("confirmed action FILL"), text);
        assertTrue(text.contains("action rejected"), text);
    }

    @Test void formattedLinesCarryTheirTimestamp() {
        List<String> lines = AuditLogView.entries(ServerResponse.parse(
                "{\"event\":\"logs\",\"logs\":[{\"message\":\"memory cleared\",\"at\":1730000000000}]}"));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().matches("\\d{2}:\\d{2}:\\d{2} · memory cleared"), lines.getFirst());
    }

    @Test void malformedEntriesAreSkippedInsteadOfThrowing() {
        List<String> lines = AuditLogView.entries(ServerResponse.parse(
                "{\"event\":\"logs\",\"logs\":[3,{\"message\":\"\"},{\"at\":1},\"text\",{\"message\":\"kept\"}]}"));
        assertEquals(List.of("kept"), lines);
    }

    @Test void emptyLogSaysSoInsteadOfShowingNothing() {
        String text = AuditLogView.render(ServerResponse.parse(
                "{\"event\":\"logs\",\"message\":\"本玩家世界审计日志\",\"logs\":[]}"));
        assertTrue(text.contains("暂无日志"), text);
    }

    @Test void keepsTheNewestEntriesWithinThePanelBudget() {
        StringBuilder logs = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            if (i > 0) logs.append(',');
            logs.append("{\"message\":\"entry-").append(i).append("\",\"at\":").append(1730000000000L + i).append('}');
        }
        String text = AuditLogView.render(ServerResponse.parse(
                "{\"event\":\"logs\",\"message\":\"本玩家世界审计日志\",\"logs\":[" + logs + "]}"));
        long shown = text.lines().filter(line -> line.contains("entry-")).count();
        assertEquals(AuditLogView.MAX_LINES, shown, "the panel must stay bounded");
        assertTrue(text.contains("entry-59"), "the newest entry must survive the cap");
        assertFalse(text.contains("entry-0\n"), "the oldest entry must be dropped first");
        assertTrue(text.contains("共 60 条"), text);
    }
}
