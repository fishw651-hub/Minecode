package mine.coding.client;

import mine.coding.ai.AiPlan;
import mine.coding.ai.transport.ChatEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleControllerTest {
    @Test
    void onlyFinalPlanCreatesPreview() {
        ConsoleController controller = new ConsoleController();
        controller.accept(new ChatEvent("child-a", "建造Agent", ChatEvent.Type.PLAN, "中间计划", null));
        assertTrue(controller.preview().isEmpty());

        AiPlan plan = AiPlan.fromJson("{\"message\":\"完成\",\"actions\":[]}");
        controller.accept(new ChatEvent("summary", "汇总Agent", ChatEvent.Type.PLAN, "最终计划已就绪", plan));
        controller.drainEvents();
        assertEquals(plan, controller.preview().orElseThrow());
    }

    @Test
    void callbacksAreQueuedUntilDrainOnMainThread() {
        ConsoleController controller = new ConsoleController();
        controller.accept(new ChatEvent("main", "主Agent", ChatEvent.Type.TEXT, "hello", null));
        assertTrue(controller.timeline().isEmpty());
        controller.drainEvents();
        assertEquals("hello", controller.timeline().get(0).text());
    }

    @Test
    void busySendCancelAndRetryFollowSingleTurnRules() {
        ConsoleController controller = new ConsoleController();
        assertTrue(controller.beginSend("first"));
        assertFalse(controller.beginSend("second"));
        controller.cancel();
        assertFalse(controller.busy());
        assertEquals("first", controller.retryText());
        assertTrue(controller.beginSend(controller.retryText()));
    }

    @Test
    void childCompletionDoesNotReleaseBusyTurn() {
        ConsoleController controller = new ConsoleController();
        controller.beginSend("create");
        controller.accept(new ChatEvent("child-a", "builder", ChatEvent.Type.COMPLETED, "done", null));
        controller.drainEvents();
        assertTrue(controller.busy());
        controller.finish();
        assertFalse(controller.busy());
    }

    @Test
    void finalPreviewIsConsumedOnceAndChildPlansAreIgnored() {
        ConsoleController controller = new ConsoleController();
        AiPlan plan = AiPlan.fromJson("{\"message\":\"done\",\"actions\":[]}");
        controller.accept(new ChatEvent("child-a", "builder", ChatEvent.Type.PLAN, "child", plan));
        controller.drainEvents();
        assertTrue(controller.takePreview().isEmpty());
        controller.accept(new ChatEvent("summary", "summary", ChatEvent.Type.PLAN, "final", plan));
        controller.drainEvents();
        assertEquals(plan, controller.takePreview().orElseThrow());
        assertTrue(controller.takePreview().isEmpty());
    }

    @Test
    void drainingEventsReturnsOnlyNewEvents() {
        ConsoleController controller = new ConsoleController();
        controller.accept(new ChatEvent("main", "main", ChatEvent.Type.TEXT, "a", null));
        assertEquals(1, controller.drainEvents().size());
        assertTrue(controller.drainEvents().isEmpty());
    }
}
