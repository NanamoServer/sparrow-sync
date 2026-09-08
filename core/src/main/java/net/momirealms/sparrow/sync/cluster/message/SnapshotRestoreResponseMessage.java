package net.momirealms.sparrow.sync.cluster.message;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayResponseMessage;
import net.momirealms.sparrow.sync.session.operation.SnapshotRestoreResult;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class SnapshotRestoreResponseMessage extends TwoWayResponseMessage<ByteBuf> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "snapshot_restore_response");
    public static final MessageCodec<ByteBuf, SnapshotRestoreResponseMessage> CODEC = RedisMessage.codec(SnapshotRestoreResponseMessage::write, SnapshotRestoreResponseMessage::new);
    private final SnapshotRestoreResult result;

    public SnapshotRestoreResponseMessage(@NotNull SnapshotRestoreResult result) {
        this.result = result;
    }

    private SnapshotRestoreResponseMessage(ByteBuf buffer) {
        super(buffer);
        this.result = switch (buffer.readUnsignedByte()) {
            case 0 -> new SnapshotRestoreResult.Restored(new UUID(buffer.readLong(), buffer.readLong()));
            case 1 -> new SnapshotRestoreResult.RestoredOffline(new UUID(buffer.readLong(), buffer.readLong()));
            case 2 -> new SnapshotRestoreResult.NotFound();
            case 3 -> new SnapshotRestoreResult.WrongPlayer();
            case 4 -> new SnapshotRestoreResult.Offline();
            case 6 -> new SnapshotRestoreResult.Cancelled();
            case 7 -> new SnapshotRestoreResult.Failed();
            case 8 -> new SnapshotRestoreResult.Unavailable();
            default -> throw new IllegalArgumentException("Unknown snapshot restore result");
        };
    }

    @Override
    protected void write(ByteBuf buffer) {
        super.write(buffer);
        switch (this.result) {
            case SnapshotRestoreResult.Restored saved -> {
                buffer.writeByte(0);
                buffer.writeLong(saved.snapshotId().getMostSignificantBits());
                buffer.writeLong(saved.snapshotId().getLeastSignificantBits());
            }
            case SnapshotRestoreResult.RestoredOffline saved -> {
                buffer.writeByte(1);
                buffer.writeLong(saved.snapshotId().getMostSignificantBits());
                buffer.writeLong(saved.snapshotId().getLeastSignificantBits());
            }
            case SnapshotRestoreResult.NotFound ignored -> buffer.writeByte(2);
            case SnapshotRestoreResult.WrongPlayer ignored -> buffer.writeByte(3);
            case SnapshotRestoreResult.Offline ignored -> buffer.writeByte(4);
            case SnapshotRestoreResult.Cancelled ignored -> buffer.writeByte(6);
            case SnapshotRestoreResult.Failed ignored -> buffer.writeByte(7);
            case SnapshotRestoreResult.Unavailable ignored -> buffer.writeByte(8);
        }
    }

    @NotNull
    public SnapshotRestoreResult result() {
        return this.result;
    }

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }
}
