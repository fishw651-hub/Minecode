package mine.coding.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;

/** Defensive server response adapter, independent of the game runtime. */
public record ServerResponse(JsonObject json, String raw) {
    public static ServerResponse parse(String raw) {
        try { return new ServerResponse(JsonParser.parseString(raw).getAsJsonObject(), raw); }
        catch (RuntimeException ignored) { return new ServerResponse(new JsonObject(), raw == null ? "" : raw); }
    }
    public String event() { return text("event"); }
    public String text(String field) { return text(json, field); }
    public String displayText() { String message = text("message"); return message.isBlank() ? raw : message; }
    public List<JsonObject> objects(String field) {
        var result = new ArrayList<JsonObject>(); JsonElement array = json.get(field);
        if (array != null && array.isJsonArray()) for (JsonElement item : array.getAsJsonArray()) if (item.isJsonObject()) result.add(item.getAsJsonObject());
        return List.copyOf(result);
    }
    public List<String> strings(String field) {
        var result = new ArrayList<String>(); JsonElement array = json.get(field);
        if (array != null && array.isJsonArray()) for (JsonElement item : array.getAsJsonArray()) if (item.isJsonPrimitive()) result.add(item.getAsString());
        return List.copyOf(result);
    }
    public static String text(JsonObject item, String field) {
        JsonElement value = item.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
    public static long number(JsonObject item, String field, long fallback) {
        try { return item.get(field).getAsLong(); } catch (RuntimeException ignored) { return fallback; }
    }
    public static boolean flag(JsonObject item, String field) {
        try { return item.get(field).getAsBoolean(); } catch (RuntimeException ignored) { return false; }
    }
}
