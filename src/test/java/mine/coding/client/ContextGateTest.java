package mine.coding.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** C1: an approved project may only resume once the freshest world context has arrived. */
class ContextGateTest {
    @Test void requestSendsImmediatelyAndCoalescesWhileOneIsInFlight() {
        ContextGate gate = new ContextGate();
        assertTrue(gate.request(), "the first refresh must be sent");
        assertFalse(gate.request(), "a refresh issued while one is in flight must be coalesced");

        ContextGate.Step step = gate.settle();
        assertTrue(step.sendAgain(), "the coalesced refresh must be sent once the answer arrives");
        assertNull(step.action());
        assertFalse(gate.settle().sendAgain(), "only one follow-up may be coalesced");
    }

    @Test void runsTheDeferredActionOnlyAfterTheFreshestContextAnswer() {
        ContextGate gate = new ContextGate();
        assertTrue(gate.request()); // the console opened and asked for the context

        List<String> ran = new ArrayList<>();
        gate.defer(() -> ran.add("resume"));
        assertFalse(gate.request(), "the approval refresh is coalesced behind the in-flight request");

        ContextGate.Step first = gate.settle();
        assertTrue(first.sendAgain(), "the stale answer must trigger the coalesced refresh instead");
        assertTrue(ran.isEmpty(), "the deferred action ran against a stale world context");

        ContextGate.Step last = gate.settle();
        assertFalse(last.sendAgain());
        assertNotNull(last.action(), "the deferred action must run after the freshest answer");
        last.action().run();
        assertEquals(List.of("resume"), ran);
    }

    @Test void runsTheDeferredActionOnlyOnce() {
        ContextGate gate = new ContextGate();
        assertTrue(gate.request());
        gate.defer(() -> {});
        assertNotNull(gate.settle().action());
        assertNull(gate.settle().action(), "a second context answer must not repeat the deferred action");
    }

    @Test void clearDropsTheDeferredActionSoCancelAndWorldSwitchStaySilent() {
        ContextGate gate = new ContextGate();
        gate.request();
        List<String> ran = new ArrayList<>();
        gate.defer(() -> ran.add("resume"));

        gate.clear();

        assertFalse(gate.deferred());
        assertNull(gate.settle().action());
        assertTrue(ran.isEmpty());
        assertTrue(gate.request(), "clear must also forget the in-flight flag");
    }

    @Test void theNewestDeferredActionWins() {
        ContextGate gate = new ContextGate();
        gate.request();
        List<String> ran = new ArrayList<>();
        gate.defer(() -> ran.add("first"));
        gate.defer(() -> ran.add("second"));

        gate.settle().action().run();

        assertEquals(List.of("second"), ran);
    }

    @Test void anUnsolicitedContextAnswerCanUnblockAPendingAction() {
        ContextGate gate = new ContextGate();
        List<String> ran = new ArrayList<>();
        gate.defer(() -> ran.add("resume"));

        ContextGate.Step step = gate.settle();

        assertFalse(step.sendAgain());
        assertNotNull(step.action(), "a context answer must still resume when no request was tracked");
        step.action().run();
        assertEquals(List.of("resume"), ran);
    }
}
