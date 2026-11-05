package net.momirealms.sparrow.sync.cluster.message;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayResponseMessage;
import net.momirealms.sparrow.sync.snapshot.operation.ContainerApplyResult;
import org.jetbrains.annotations.NotNull;

public final class SnapshotApplyContainersResponseMessage extends TwoWayResponseMessage<ByteBuf> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "snapshot_apply_containers_response");
    public static final MessageCodec<ByteBuf, SnapshotApplyContainersResponseMessage> CODEC = RedisMessage.codec(SnapshotApplyContainersResponseMessage::write, SnapshotApplyContainersResponseMessage::new);
    private static final ContainerApplyResult[] RESULTS = ContainerApplyResult.values(); // 与结果枚举的协议序号保持一致
    private final ContainerApplyResult result;

    public SnapshotApplyContainersResponseMessage(@NotNull ContainerApplyResult result) {
        this.result = result;
    }

    private SnapshotApplyContainersResponseMessage(ByteBuf buffer) {
        super(buffer);
        this.result = RESULTS[buffer.readUnsignedByte()];
    }

    @Override
    protected void write(ByteBuf buffer) {
        super.write(buffer);
        buffer.writeByte(this.result.ordinal());
    }

    @NotNull
    @Override
    public MessageIdentifier identifier() {
        return ID;
    }

    @NotNull
    public ContainerApplyResult result() {
        return this.result;
    }
}
