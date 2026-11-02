package mine.coding.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerResponseTest {
    @Test void parsesPreviewActionsAndDraftsWithoutTrustingMalformedEntries() {
        var response = ServerResponse.parse("{\"event\":\"preview\",\"planId\":\"p\",\"actions\":[3,{\"id\":\"a\",\"allowed\":true,\"blockCount\":12,\"reversible\":false}],\"memoryDrafts\":[\"remember\"],\"skillDrafts\":[{\"name\":\"skill\",\"instructions\":\"do it\"}]}");
        assertEquals("p", response.text("planId"));
        assertEquals(1, response.objects("actions").size());
        assertEquals(12, ServerResponse.number(response.objects("actions").get(0), "blockCount", 0));
        assertEquals("remember", response.strings("memoryDrafts").get(0));
    }
    @Test void malformedFieldsDoNotThrowAndKeepMessage() {
        var response = ServerResponse.parse("{\"event\":{},\"actions\":false,\"message\":\"bad action\"}");
        assertEquals("", response.event());
        assertTrue(response.objects("actions").isEmpty());
        assertEquals("bad action", response.displayText());
    }
    @Test void invalidJsonRetainsRawText() {
        assertEquals("raw response", ServerResponse.parse("raw response").displayText());
    }
}
