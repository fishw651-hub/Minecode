package mine.coding.network;

import com.google.gson.JsonObject;
import mine.coding.server.MineCodeService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class MineCodeNetworking {
    private MineCodeNetworking(){}
    public static void registerServer(){
        PayloadTypeRegistry.serverboundPlay().register(RequestPayload.TYPE,RequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ResponsePayload.TYPE,ResponsePayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestPayload.TYPE,(payload,context)->
                context.server().execute(()->MineCodeService.handle(context.player(),payload.json())));
    }
    public static void send(ServerPlayer player,JsonObject response){
        ServerPlayNetworking.send(player,new ResponsePayload(response.toString()));
    }
}
