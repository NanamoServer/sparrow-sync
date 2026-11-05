package net.momirealms.sparrow.sync.cluster.message;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayRequestMessage;
import net.momirealms.sparrow.redis.messagebroker.util.ByteBufHelper;
import net.momirealms.sparrow.sync.cluster.RemoteSnapshotManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotCaptureResult;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SnapshotCaptureRequestMessage extends TwoWayRequestMessage<ByteBuf, SnapshotCaptureResponseMessage> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "snapshot_capture_request");
    public static final MessageCodec<ByteBuf, SnapshotCaptureRequestMessage> CODEC = RedisMessage.codec(SnapshotCaptureRequestMessage::write, SnapshotCaptureRequestMessage::new);
    private final @Nullable RemoteSnapshotManager receiver = SparrowSync.instance().remoteSnapshotManager();
    private final UUID playerId;
    private final SaveCause cause;

    public SnapshotCaptureRequestMessage(@NotNull UUID playerId, @NotNull SaveCause cause) {
        this.playerId = playerId;
        this.cause = cause;
    }

    private SnapshotCaptureRequestMessage(ByteBuf buffer) {
        super(buffer);
        this.playerId = new UUID(buffer.readLong(), buffer.readLong());
        this.cause = SaveCause.byName(ByteBufHelper.readUtf8(buffer, 64));
    }

    @Override
    protected void write(ByteBuf buffer) {
        super.write(buffer);
        buffer.writeLong(this.playerId.getMostSignificantBits());
        buffer.writeLong(this.playerId.getLeastSignificantBits());
        ByteBufHelper.writeUtf8(buffer, this.cause.name(), 64);
    }

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }

    @Override
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotCaptureResponseMessage> handleRequest() {
        if (this.receiver == null) return CompletableFuture.completedFuture(new SnapshotCaptureResponseMessage(SnapshotCaptureResult.OFFLINE));
        return this.receiver.receiveCapture(this.playerId, this.cause).thenApply(SnapshotCaptureResponseMessage::new);
    }
}
