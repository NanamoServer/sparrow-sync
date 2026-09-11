package net.momirealms.sparrow.sync.cluster.message;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayResponseMessage;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotCaptureResult;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class SnapshotCaptureResponseMessage extends TwoWayResponseMessage<ByteBuf> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "snapshot_capture_response");
    public static final MessageCodec<ByteBuf, SnapshotCaptureResponseMessage> CODEC = RedisMessage.codec(SnapshotCaptureResponseMessage::write, SnapshotCaptureResponseMessage::new);
    private final SnapshotCaptureResult result;

    public SnapshotCaptureResponseMessage(@NotNull SnapshotCaptureResult result) {
        this.result = result;
    }

    private SnapshotCaptureResponseMessage(ByteBuf buffer) {
        super(buffer);
        this.result = switch (buffer.readUnsignedByte()) {
            case 0 -> new SnapshotCaptureResult.Captured(new UUID(buffer.readLong(), buffer.readLong()));
            case 1 -> SnapshotCaptureResult.OFFLINE;
            case 2 -> SnapshotCaptureResult.CANCELLED;
            case 3 -> SnapshotCaptureResult.FAILED;
            case 4 -> SnapshotCaptureResult.UNAVAILABLE;
            default -> throw new IllegalArgumentException("Unknown snapshot capture result");
        };
    }

    @Override
    protected void write(ByteBuf buffer) {
        super.write(buffer);
        switch (this.result) {
            case SnapshotCaptureResult.Captured saved -> {
                buffer.writeByte(0);
                buffer.writeLong(saved.snapshotId().getMostSignificantBits());
                buffer.writeLong(saved.snapshotId().getLeastSignificantBits());
            }
            case SnapshotCaptureResult.Offline ignored -> buffer.writeByte(1);
            case SnapshotCaptureResult.Cancelled ignored -> buffer.writeByte(2);
            case SnapshotCaptureResult.Failed ignored -> buffer.writeByte(3);
            case SnapshotCaptureResult.Unavailable ignored -> buffer.writeByte(4);
        }
    }

    @NotNull
    public SnapshotCaptureResult result() {
        return this.result;
    }

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }
}
