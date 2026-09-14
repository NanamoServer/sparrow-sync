package net.momirealms.sparrow.sync.map.message;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntConsumer;

public final class MapInvalidationMessage implements RedisMessage<ByteBuf> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "map_invalidation");
    public static final MessageCodec<ByteBuf, MapInvalidationMessage> CODEC = RedisMessage.codec(MapInvalidationMessage::write, MapInvalidationMessage::new);
    private static volatile @Nullable IntConsumer listener; // 未启用或关闭时为 null
    private final int globalId; // 需要重新读取的全局地图 ID (负数)

    public MapInvalidationMessage(int globalId) {
        if (globalId >= 0) {
            throw new IllegalArgumentException("global map id must be negative");
        }
        this.globalId = globalId;
    }

    private MapInvalidationMessage(ByteBuf buffer) {
        this(buffer.readInt());
    }

    private void write(ByteBuf buffer) {
        buffer.writeInt(this.globalId);
    }

    public static void listener(@Nullable IntConsumer listener) {
        MapInvalidationMessage.listener = listener;
    }

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }

    @Override
    public void handle(@NotNull MessageBroker<ByteBuf> broker) {
        IntConsumer listener = MapInvalidationMessage.listener;
        if (listener != null) {
            listener.accept(this.globalId);
        }
    }
}
