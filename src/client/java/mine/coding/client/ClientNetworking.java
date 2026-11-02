package mine.coding.client;

import com.google.gson.JsonObject;
import mine.coding.client.screen.AiConsoleScreen;
import mine.coding.network.RequestPayload;
import mine.coding.network.ResponsePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import java.util.function.Consumer;

/** Client-side bridge; server networking remains independent of client classes. */
public final class ClientNetworking {
    private static Consumer<JsonObject> receiver = ignored -> {};
    private static Consumer<JsonObject> focusReceiver = ignored -> {};
    private static boolean consoleOpen;
    private ClientNetworking() {}

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(ResponsePayload.TYPE, (payload, context) -> {
            JsonObject parsed;
            try {
                parsed = com.google.gson.JsonParser.parseString(payload.json()).getAsJsonObject();
            } catch (RuntimeException ignored) {
                parsed = new JsonObject();
                parsed.addProperty("message", payload.json());
            }
            JsonObject value = parsed;
            context.client().execute(() -> dispatch(value));
        });
    }

    /**
     * Routes one server packet on the client thread.
     *
     * <p>{@code open} is the answer to {@code /minecode} and only opens the screen, while {@code focus}
     * answers belong to {@link FocusGateway}; neither may be echoed into the chat timeline.
     */
    private static void dispatch(JsonObject packet) {
        if (ServerEvents.opensConsole(packet)) { openConsole(); return; }
        focusReceiver.accept(packet);
        if (ServerEvents.isFocusObservation(packet)) return;
        receiver.accept(packet);
    }

    private static void openConsole() {
        if (consoleOpen) return;
        Minecraft.getInstance().setScreenAndShow(new AiConsoleScreen());
    }

    /** Runs client work on the game thread; used by bridges called from the AI worker threads. */
    public static void execute(Runnable task) {
        if (task == null) return;
        Minecraft.getInstance().execute(task);
    }

    public static void setClientReceiver(Consumer<JsonObject> callback) {
        receiver = callback == null ? ignored -> {} : callback;
        consoleOpen = callback != null;
    }
    public static void setFocusReceiver(Consumer<JsonObject> callback) { focusReceiver = callback == null ? ignored -> {} : callback; }
    public static void send(JsonObject request) {
        if (ClientPlayNetworking.canSend(RequestPayload.TYPE)) ClientPlayNetworking.send(new RequestPayload(request.toString()));
        else { JsonObject error = new JsonObject(); error.addProperty("event", "error"); error.addProperty("message", "服务端未启用 MineCode 网络协议"); if (request.has("requestId")) error.addProperty("requestId", request.get("requestId").getAsString()); receiver.accept(error); focusReceiver.accept(error); }
    }
}
