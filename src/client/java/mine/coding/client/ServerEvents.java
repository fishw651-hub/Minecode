package mine.coding.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Pure classification of server-to-client packets in the client bridge.
 *
 * <p>The bridge sees every packet, including the ones that are not meant for the chat timeline:
 * {@code open} is a command request and every {@code focus} answer belongs to {@link FocusGateway}.
 * Echoing either of them printed one noise line per read.
 */
public final class ServerEvents {
    private ServerEvents() {}

    public static String event(JsonObject packet) {
        if (packet == null) return "";
        JsonElement value = packet.get("event");
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** The {@code /minecode} command asks the client to show the console screen. */
    public static boolean opensConsole(JsonObject packet) { return "open".equals(event(packet)); }

    /** Focus answers are consumed by {@link FocusGateway}; they must never reach the chat timeline. */
    public static boolean isFocusObservation(JsonObject packet) { return "focus".equals(event(packet)); }
}
