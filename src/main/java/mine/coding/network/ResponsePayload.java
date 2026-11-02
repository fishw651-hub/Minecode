package mine.coding.network;

import mine.coding.MineCode;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record ResponsePayload(String json) implements CustomPacketPayload {
    public static final Type<ResponsePayload> TYPE=new Type<>(MineCode.id("response"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ResponsePayload> STREAM_CODEC=StreamCodec.of(
            (buffer,payload)->buffer.writeUtf(payload.json(),RequestPayload.MAX_BYTES),
            buffer->new ResponsePayload(buffer.readUtf(RequestPayload.MAX_BYTES)));
    public ResponsePayload { Objects.requireNonNull(json,"json");if(json.getBytes(StandardCharsets.UTF_8).length>RequestPayload.MAX_BYTES)throw new IllegalArgumentException("response exceeds 64 KiB"); }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
