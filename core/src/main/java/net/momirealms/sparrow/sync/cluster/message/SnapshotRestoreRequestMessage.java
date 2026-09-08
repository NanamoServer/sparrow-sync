package net.momirealms.sparrow.sync.cluster.message;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayRequestMessage;
import net.momirealms.sparrow.sync.cluster.RemoteSnapshotManager;
import net.momirealms.sparrow.sync.session.operation.SnapshotRestoreResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SnapshotRestoreRequestMessage extends TwoWayRequestMessage<ByteBuf, SnapshotRestoreResponseMessage> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "snapshot_restore_request");
    public static final MessageCodec<ByteBuf, SnapshotRestoreRequestMessage> CODEC = RedisMessage.codec(SnapshotRestoreRequestMessage::write, SnapshotRestoreRequestMessage::new);
    private static volatile @Nullable RemoteSnapshotManager receiver;
    private final UUID playerId;
    private final UUID snapshotId;

    public SnapshotRestoreRequestMessage(@NotNull UUID playerId, @NotNull UUID snapshotId) {
        this.playerId = playerId;
        this.snapshotId = snapshotId;
    }

    private SnapshotRestoreRequestMessage(ByteBuf buffer) {
        super(buffer);
        this.playerId = new UUID(buffer.readLong(), buffer.readLong());
        this.snapshotId = new UUID(buffer.readLong(), buffer.readLong());
    }

    @Override
    protected void write(ByteBuf buffer) {
        super.write(buffer);
        buffer.writeLong(this.playerId.getMostSignificantBits());
        buffer.writeLong(this.playerId.getLeastSignificantBits());
        buffer.writeLong(this.snapshotId.getMostSignificantBits());
        buffer.writeLong(this.snapshotId.getLeastSignificantBits());
    }

    public static void receiver(@Nullable RemoteSnapshotManager receiver) {
        SnapshotRestoreRequestMessage.receiver = receiver;
    }

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }

    @Override
    @NotNull
    protected CompletableFuture<SnapshotRestoreResponseMessage> handleRequest() {
        RemoteSnapshotManager current = receiver;
        if (current == null) return CompletableFuture.completedFuture(new SnapshotRestoreResponseMessage(new SnapshotRestoreResult.Offline()));
        return current.receiveRestore(this.playerId, this.snapshotId).thenApply(SnapshotRestoreResponseMessage::new);
    }
}
