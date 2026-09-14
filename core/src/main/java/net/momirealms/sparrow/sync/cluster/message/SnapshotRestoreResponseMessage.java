package net.momirealms.sparrow.sync.cluster.message;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayResponseMessage;
import net.momirealms.sparrow.redis.messagebroker.util.ByteBufHelper;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotRestoreResult;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public final class SnapshotRestoreResponseMessage extends TwoWayResponseMessage<ByteBuf> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "snapshot_restore_response");
    public static final MessageCodec<ByteBuf, SnapshotRestoreResponseMessage> CODEC = RedisMessage.codec(SnapshotRestoreResponseMessage::write, SnapshotRestoreResponseMessage::new);
    private static final SnapshotRestoreResult.Stage[] STAGES = SnapshotRestoreResult.Stage.values();

    private final SnapshotRestoreResult result;

    public SnapshotRestoreResponseMessage(@NotNull SnapshotRestoreResult result) {
        this.result = result;
    }

    private SnapshotRestoreResponseMessage(ByteBuf buffer) {
        super(buffer);
        this.result = switch (buffer.readUnsignedByte()) {
            case 0 -> new SnapshotRestoreResult.Restored(new UUID(buffer.readLong(), buffer.readLong()), readSkipped(buffer));
            case 1 -> new SnapshotRestoreResult.RestoredOffline(new UUID(buffer.readLong(), buffer.readLong()));
            case 2 -> SnapshotRestoreResult.NOT_FOUND;
            case 3 -> SnapshotRestoreResult.WRONG_PLAYER;
            case 4 -> SnapshotRestoreResult.OFFLINE;
            case 6 -> new SnapshotRestoreResult.Cancelled(STAGES[buffer.readUnsignedByte()], readSkipped(buffer));
            case 7 -> new SnapshotRestoreResult.Failed(STAGES[buffer.readUnsignedByte()], ByteBufHelper.readUtf8(buffer, 32767), null, readSkipped(buffer));
            case 8 -> SnapshotRestoreResult.UNAVAILABLE;
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
                writeSkipped(buffer, saved.skipped());
            }
            case SnapshotRestoreResult.RestoredOffline saved -> {
                buffer.writeByte(1);
                buffer.writeLong(saved.snapshotId().getMostSignificantBits());
                buffer.writeLong(saved.snapshotId().getLeastSignificantBits());
            }
            case SnapshotRestoreResult.NotFound ignored -> buffer.writeByte(2);
            case SnapshotRestoreResult.WrongPlayer ignored -> buffer.writeByte(3);
            case SnapshotRestoreResult.Offline ignored -> buffer.writeByte(4);
            case SnapshotRestoreResult.Cancelled cancelled -> {
                buffer.writeByte(6);
                buffer.writeByte(cancelled.stage().ordinal());
                writeSkipped(buffer, cancelled.skipped());
            }
            case SnapshotRestoreResult.Failed failed -> {
                buffer.writeByte(7);
                buffer.writeByte(failed.stage().ordinal());
                ByteBufHelper.writeUtf8(buffer, failed.detail(), 32767);
                writeSkipped(buffer, failed.skipped());
            }
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

    private static List<DataKey> readSkipped(ByteBuf buffer) {
        int size = buffer.readInt();
        List<DataKey> skipped = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            skipped.add(DataKey.parse(ByteBufHelper.readUtf8(buffer, 32767)));
        }
        return skipped;
    }

    private static void writeSkipped(ByteBuf buffer, List<DataKey> skipped) {
        int size = skipped.size();
        buffer.writeInt(size);
        for (int i = 0; i < size; i++) {
            ByteBufHelper.writeUtf8(buffer, skipped.get(i).asString(), 32767);
        }
    }
}
