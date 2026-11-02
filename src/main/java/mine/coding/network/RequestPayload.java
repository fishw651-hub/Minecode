package mine.coding.network;

import mine.coding.MineCode;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record RequestPayload(String json) implements CustomPacketPayload {
    public static final int MAX_BYTES=65536;
    public static final Type<RequestPayload> TYPE=new Type<>(MineCode.id("request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RequestPayload> STREAM_CODEC=StreamCodec.of(
            (buffer,payload)->buffer.writeUtf(payload.json(),MAX_BYTES),
            buffer->new RequestPayload(buffer.readUtf(MAX_BYTES)));
    public RequestPayload { Objects.requireNonNull(json,"json"); if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("request exceeds 64 KiB"); }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
