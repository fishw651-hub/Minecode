package mine.coding.client;
import mine.coding.client.render.ChatTimeline;
import mine.coding.ai.transport.ChatEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatTimelineTest {
    @Test void streamingOutputAccumulatesWithinSameAgent() {
        var timeline = new ChatTimeline();
        timeline.append(new ChatEvent("child-a","建造",ChatEvent.Type.QUEUED,"queued",null));
        timeline.append(new ChatEvent("child-a","建造",ChatEvent.Type.TEXT,"hello ",null));
        timeline.append(new ChatEvent("child-a","建造",ChatEvent.Type.TEXT,"world",null));
        assertEquals(1,timeline.entries().size()); assertEquals("hello world",timeline.entries().get(0).output());
    }
    @Test void queuedForSameAgentStartsNewTurnAndCancellationMarksAllRunningAgents() {
        var timeline = new ChatTimeline();
        timeline.append(new ChatEvent("main","主",ChatEvent.Type.QUEUED,"",null));
        timeline.append(new ChatEvent("child-a","建造",ChatEvent.Type.RUNNING,"",null));
        timeline.cancelRunning();
        assertTrue(timeline.entries().stream().allMatch(entry -> entry.status().equals("CANCELLED")));
        timeline.append(new ChatEvent("main","主",ChatEvent.Type.QUEUED,"",null));
        assertEquals(3,timeline.entries().size());
    }
}
