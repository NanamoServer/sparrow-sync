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
    private static volatile @Nullable IntConsumer listener; // 当前服务的通知入口, null 表示尚未启用或正在关闭
    private final int globalId; // 当前集群需要重新读取的负数地图 ID

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
