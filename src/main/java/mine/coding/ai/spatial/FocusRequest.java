package mine.coding.ai.spatial;

import com.google.gson.JsonObject;
import java.util.Objects;

public record FocusRequest(String id, String purpose, BlockBox bounds) {
    public FocusRequest {
        id = text(id, "id", 128); purpose = text(purpose, "purpose", 1024);
        bounds = Objects.requireNonNull(bounds, "bounds");
        if (bounds.volume() > 4096) throw new IllegalArgumentException("Focus bounds exceed 4096 blocks");
    }
    public JsonObject toJson() { JsonObject j = new JsonObject(); j.addProperty("id", id); j.addProperty("purpose", purpose); j.add("bounds", bounds.toJson()); return j; }
    public static FocusRequest fromJson(JsonObject j) { Objects.requireNonNull(j, "json"); BlockBox.requireFields(j, "id", "purpose", "bounds"); return new FocusRequest(string(j, "id"), string(j, "purpose"), BlockBox.fromJson(object(j, "bounds"))); }
    static String string(JsonObject j, String f) { if (!j.get(f).isJsonPrimitive() || !j.getAsJsonPrimitive(f).isString()) throw new IllegalArgumentException(f + " must be text"); return j.get(f).getAsString(); }
    static JsonObject object(JsonObject j, String f) { if (!j.get(f).isJsonObject()) throw new IllegalArgumentException(f + " must be object"); return j.getAsJsonObject(f); }
    static String text(String v, String f, int max) { Objects.requireNonNull(v, f); if (v.isBlank() || v.length() > max) throw new IllegalArgumentException("Invalid " + f); return v; }
}
