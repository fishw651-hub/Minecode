package mine.coding.client.screen.components;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The confirm/reject gating that keeps a player from acting twice on one previewed action. */
class ActionCardTest {
    private static final ActionCard.Action ALLOWED =
            new ActionCard.Action("a1", "放石头", "placeBlock", true, "", 1, true);
    private static final ActionCard.Action FORBIDDEN =
            new ActionCard.Action("a2", "越界", "fill", false, "target outside player radius", 4096, false);

    private final List<String> calls = new ArrayList<>();

    private ActionCard card(ActionCard.Action action) {
        return new ActionCard(action, a -> calls.add("confirm:" + a.id()),
                a -> calls.add("reject:" + a.id()), a -> calls.add("undo:" + a.id()));
    }

    @Test void confirmRunsOnceThenStaysResolved() {
        ActionCard card = card(ALLOWED);
        card.confirm();
        card.confirm();
        assertEquals(List.of("confirm:a1"), calls);
        assertTrue(card.resolved());
    }

    @Test void forbiddenActionCannotBeConfirmed() {
        ActionCard card = card(FORBIDDEN);
        card.confirm();
        assertTrue(calls.isEmpty(), "the server rejected this action, the client must not confirm it");
        assertFalse(card.resolved(), "an impossible confirm must not burn the card");
    }

    @Test void rejectWorksForForbiddenActionsAndBlocksALaterConfirm() {
        ActionCard card = card(FORBIDDEN);
        card.reject();
        card.confirm();
        assertEquals(List.of("reject:a2"), calls);
    }

    @Test void undoFollowsServerReversibilityAndSurvivesResolution() {
        ActionCard reversible = card(ALLOWED);
        reversible.confirm();
        reversible.undo();
        ActionCard irreversible = card(new ActionCard.Action("a3", "命令", "say hi", true, "", 0, false));
        irreversible.confirm();
        irreversible.undo();
        assertEquals(List.of("confirm:a1", "undo:a1", "confirm:a3"), calls);
    }

    @Test void nullCallbacksAreToleratedInsteadOfThrowing() {
        ActionCard card = new ActionCard(ALLOWED, null, null, null);
        assertDoesNotThrow(card::confirm);
        assertDoesNotThrow(card::undo);
        assertTrue(card.resolved());
    }
}
