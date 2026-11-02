package mine.coding.ai.spatial;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;

/** Immutable inclusive axis-aligned block bounds. */
public record BlockBox(int x, int y, int z, int x2, int y2, int z2) {
    public BlockBox {
        if (x > x2 || y > y2 || z > z2) throw new IllegalArgumentException("Bounds must be normalized");
        dimensions(x, x2); dimensions(y, y2); dimensions(z, z2);
    }

    public static BlockBox ofCorners(int ax, int ay, int az, int bx, int by, int bz) {
        return new BlockBox(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
    }

    private static long dimensions(int low, int high) {
        long size = (long) high - low + 1L;
        if (size <= 0 || size > 4096) throw new IllegalArgumentException("Invalid bounds size");
        return size;
    }

    public long volume() {
        return dimensions(x, x2) * dimensions(y, y2) * dimensions(z, z2);
    }

    public boolean contains(BlockBox other) {
        Objects.requireNonNull(other, "other");
        return x <= other.x && y <= other.y && z <= other.z
                && x2 >= other.x2 && y2 >= other.y2 && z2 >= other.z2;
    }

    public boolean contains(int px, int py, int pz) {
        return px >= x && px <= x2 && py >= y && py <= y2 && pz >= z && pz <= z2;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("x", x); json.addProperty("y", y); json.addProperty("z", z);
        json.addProperty("x2", x2); json.addProperty("y2", y2); json.addProperty("z2", z2);
        return json;
    }

    public static BlockBox fromJson(JsonObject json) {
        Objects.requireNonNull(json, "json");
        requireFields(json, "x", "y", "z", "x2", "y2", "z2");
        return new BlockBox(integer(json, "x"), integer(json, "y"), integer(json, "z"),
                integer(json, "x2"), integer(json, "y2"), integer(json, "z2"));
    }

    static void requireFields(JsonObject json, String... names) {
        if (json.size() != names.length) throw new IllegalArgumentException("Unknown or missing fields");
        for (String name : names) if (!json.has(name) || json.get(name).isJsonNull()) throw new IllegalArgumentException("Missing field " + name);
    }

    static int integer(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(field + " must be an integer");
        String value = element.getAsString();
        if (!value.matches("-?\\d+")) throw new IllegalArgumentException(field + " must be an integer");
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { throw new IllegalArgumentException(field + " outside integer range", e); }
    }
}
