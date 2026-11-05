package net.momirealms.sparrow.sync.cluster.message;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayRequestMessage;
import net.momirealms.sparrow.sync.cluster.RemoteSnapshotManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.operation.ContainerApplyResult;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SnapshotApplyContainersRequestMessage extends TwoWayRequestMessage<ByteBuf, SnapshotApplyContainersResponseMessage> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "snapshot_apply_containers_request");
    public static final MessageCodec<ByteBuf, SnapshotApplyContainersRequestMessage> CODEC = RedisMessage.codec(SnapshotApplyContainersRequestMessage::write, SnapshotApplyContainersRequestMessage::new);
    private final @Nullable RemoteSnapshotManager receiver = SparrowSync.instance().remoteSnapshotManager(); // 启动期间管理器可能尚未创建
    private final UUID playerId;
    private final UUID snapshotId;

    public SnapshotApplyContainersRequestMessage(@NotNull UUID playerId, @NotNull UUID snapshotId) {
        this.playerId = playerId;
        this.snapshotId = snapshotId;
    }

    private SnapshotApplyContainersRequestMessage(ByteBuf buffer) {
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

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }

    @Override
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotApplyContainersResponseMessage> handleRequest() {
        RemoteSnapshotManager current = this.receiver;
        if (current == null) return CompletableFuture.completedFuture(new SnapshotApplyContainersResponseMessage(ContainerApplyResult.OFFLINE));
        return current.receiveApplyContainers(this.playerId, this.snapshotId).thenApply(SnapshotApplyContainersResponseMessage::new);
    }
}