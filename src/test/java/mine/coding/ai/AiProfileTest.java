package mine.coding.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiProfileTest {
    private static AiProfile profile(String effort) {
        return new AiProfile("p", "档案", "system", "model", effort, List.of());
    }

    /** O8: one list owns the reasoning_effort values; UI and transport must both read it. */
    @Test void effortsIsTheSingleSourceOfTruth() {
        assertEquals(List.of("none", "low", "medium", "high"), AiProfile.EFFORTS);
        assertTrue(AiProfile.isSupportedEffort("high"));
        assertFalse(AiProfile.isSupportedEffort("xhigh"));
        assertFalse(AiProfile.isSupportedEffort(null));
        assertThrows(UnsupportedOperationException.class, () -> AiProfile.EFFORTS.add("xhigh"));
    }

    /** O8: an unknown or disabled effort degrades to "omit the field", never to a failed request. */
    @Test void unsupportedEffortDegradesToOmittedRequestField() {
        assertEquals("high", profile("high").apiReasoningEffort());
        assertEquals("low", profile("low").apiReasoningEffort());
        assertNull(profile("none").apiReasoningEffort());
        assertNull(profile("xhigh").apiReasoningEffort());
    }

    @Test void unknownEffortStillLoadsFromConfig() {
        assertDoesNotThrow(() -> profile("xhigh"));
    }
}
