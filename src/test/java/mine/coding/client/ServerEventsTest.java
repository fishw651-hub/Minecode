package mine.coding.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** C2/C3: packets the client bridge must route instead of echoing into the chat timeline. */
class ServerEventsTest {
    private static JsonObject parse(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test void openIsRecognisedSoTheCommandCanShowTheConsole() {
        assertTrue(ServerEvents.opensConsole(parse("{\"event\":\"open\",\"message\":\"打开 MineCode 控制台\"}")));
        assertFalse(ServerEvents.opensConsole(parse("{\"event\":\"context\",\"message\":\"已读取本世界档案\"}")));
        assertFalse(ServerEvents.opensConsole(parse("{\"message\":\"没有 event 字段\"}")));
        assertFalse(ServerEvents.opensConsole(null));
    }

    @Test void focusAnswersAreRecognisedSoTheyStayOutOfTheTimeline() {
        assertTrue(ServerEvents.isFocusObservation(parse(
                "{\"event\":\"focus\",\"requestId\":\"f1\",\"message\":\"已读取只读方块观察；不包含库存或 NBT\"}")));
        assertFalse(ServerEvents.isFocusObservation(parse("{\"event\":\"preview\",\"planId\":\"p\"}")));
    }

    @Test void focusErrorsStillReachTheGatewayAndTheConsole() {
        // A failed focus read is answered as an "error" carrying the requestId, so it must not be swallowed.
        assertFalse(ServerEvents.isFocusObservation(parse("{\"event\":\"error\",\"requestId\":\"f1\",\"message\":\"focus 超出批准项目范围\"}")));
    }

    @Test void malformedEventFieldsAreTreatedAsNoEvent() {
        assertEquals("", ServerEvents.event(parse("{\"event\":{}}")));
        assertEquals("", ServerEvents.event(parse("{}")));
        assertEquals("", ServerEvents.event(null));
    }
}
