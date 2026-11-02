package mine.coding.ai.spatial;

import com.google.gson.JsonObject;
import java.util.Objects;

public record StageCheckpoint(String stage, String summary, String nextTask, boolean complete) {
    public StageCheckpoint { stage = FocusRequest.text(stage,"stage",256); summary = FocusRequest.text(summary,"summary",2048); nextTask = FocusRequest.text(nextTask,"nextTask",2048); }
    public JsonObject toJson() { JsonObject j = new JsonObject(); j.addProperty("stage",stage); j.addProperty("summary",summary); j.addProperty("nextTask",nextTask); j.addProperty("complete",complete); return j; }
    public static StageCheckpoint fromJson(JsonObject j) { Objects.requireNonNull(j,"json"); BlockBox.requireFields(j,"stage","summary","nextTask","complete"); if (!j.get("complete").isJsonPrimitive() || !j.getAsJsonPrimitive("complete").isBoolean()) throw new IllegalArgumentException("complete must be boolean"); return new StageCheckpoint(FocusRequest.string(j,"stage"),FocusRequest.string(j,"summary"),FocusRequest.string(j,"nextTask"),j.get("complete").getAsBoolean()); }
}
