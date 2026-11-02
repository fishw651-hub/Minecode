package mine.coding.server;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ServerRequestTest {
    @Test void rejectsUnknownAndMissingFieldsAndWrongTypes(){for(String s:new String[]{"{}","{\"op\":\"unknown\"}","{\"op\":\"confirm\",\"planId\":\"a\"}","{\"op\":\"context\",\"key\":\"secret\"}","{\"op\":\"rollback\",\"version\":1.0}","{\"op\":\"rollback\",\"version\":-1}","{\"op\":\"undo\",\"operationId\":1}"})assertThrows(RuntimeException.class,()->ServerRequest.parse(s),s);}
    @Test void acceptsAllExpectedFields(){assertEquals("confirm",ServerRequest.parse("{\"op\":\"confirm\",\"planId\":\"p\",\"actionId\":\"a\"}").get("op").getAsString()); assertEquals("proposeProject",ServerRequest.parse("{\"op\":\"proposeProject\",\"definition\":\"{}\"}").get("op").getAsString()); assertEquals("proposeProject",ServerRequest.parse("{\"op\":\"proposeProject\",\"definition\":\"{}\",\"checkpoint\":\"{}\"}").get("op").getAsString()); assertEquals("confirmStage",ServerRequest.parse("{\"op\":\"confirmStage\",\"planId\":\"p\"}").get("op").getAsString()); assertEquals("focus",ServerRequest.parse("{\"op\":\"focus\",\"requestId\":\"r\",\"bounds\":\"{}\",\"purpose\":\"inspect\"}").get("op").getAsString());}
    @Test void enforcesUtf8Envelope(){assertThrows(IllegalArgumentException.class,()->ServerRequest.parse("中".repeat(30000)));}
}

