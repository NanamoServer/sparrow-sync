package net.momirealms.sparrow.sync.cluster;

import net.momirealms.sparrow.sync.cluster.message.SnapshotCaptureRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotCaptureResponseMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotRestoreRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotRestoreResponseMessage;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.operation.SnapshotCaptureResult;
import net.momirealms.sparrow.sync.session.operation.SnapshotRestoreResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class RemoteSnapshotManager {
    private final SparrowSync plugin;
    private volatile boolean closed;

    public RemoteSnapshotManager(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onDelayedEnable() {
        SnapshotCaptureRequestMessage.receiver(this);
        SnapshotRestoreRequestMessage.receiver(this);
    }

    /** 请求目标服采集玩家快照, Future 等待保存后的回执. */
    @NotNull
    public CompletableFuture<SnapshotCaptureResult> capture(@NotNull String serverId, @NotNull UUID playerId) {
        if (this.closed) return CompletableFuture.completedFuture(new SnapshotCaptureResult.Offline());
        byte[] heartbeat = ("ss:server:" + serverId).getBytes(StandardCharsets.UTF_8);
        return this.plugin.redisConnector().connection().async().get(heartbeat).toCompletableFuture().thenCompose(value -> {
            if (value == null) return CompletableFuture.<SnapshotCaptureResult>completedFuture(new SnapshotCaptureResult.Offline());
            return this.plugin.messageBrokerManager().broker()
                    .publishTwoWay(new SnapshotCaptureRequestMessage(playerId), serverId)
                    .thenApply(SnapshotCaptureResponseMessage::result);
        }).orTimeout(30, TimeUnit.SECONDS).exceptionally(failure -> new SnapshotCaptureResult.Unavailable());
    }

    /** 将远程采集请求交给本服快照服务, 玩家离线时返回失败. */
    @NotNull
    public CompletableFuture<SnapshotCaptureResult> receiveCapture(@NotNull UUID playerId) {
        if (this.closed) return CompletableFuture.completedFuture(new SnapshotCaptureResult.Offline());
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return CompletableFuture.completedFuture(new SnapshotCaptureResult.Offline());
        return this.plugin.snapshotService().capture(player).exceptionally(failure -> {
            this.plugin.logger().warn(TranslationManager.console("log.command.snapshot_failed", "capture"), failure);
            return new SnapshotCaptureResult.Failed();
        });
    }

    /** 请求目标服恢复玩家快照, Future 等待保存后的回执. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restore(@NotNull String serverId, @NotNull UUID playerId, @NotNull UUID snapshotId) {
        if (this.closed) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
        byte[] heartbeat = ("ss:server:" + serverId).getBytes(StandardCharsets.UTF_8);
        return this.plugin.redisConnector().connection().async().get(heartbeat).toCompletableFuture().thenCompose(value -> {
            if (value == null) return CompletableFuture.<SnapshotRestoreResult>completedFuture(new SnapshotRestoreResult.Offline());
            return this.plugin.messageBrokerManager().broker()
                    .publishTwoWay(new SnapshotRestoreRequestMessage(playerId, snapshotId), serverId)
                    .thenApply(SnapshotRestoreResponseMessage::result);
        }).orTimeout(30, TimeUnit.SECONDS).exceptionally(failure -> new SnapshotRestoreResult.Unavailable());
    }

    /** 将远程恢复请求交给本服快照服务, 玩家离线时返回失败. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> receiveRestore(@NotNull UUID playerId, @NotNull UUID snapshotId) {
        if (this.closed) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return CompletableFuture.completedFuture(new SnapshotRestoreResult.Offline());
        return this.plugin.snapshotService().restore(player, snapshotId).exceptionally(failure -> {
            this.plugin.logger().warn(TranslationManager.console("log.command.snapshot_failed", "restore"), failure);
            return new SnapshotRestoreResult.Failed();
        });
    }

    public void shutdown() {
        this.closed = true;
        SnapshotCaptureRequestMessage.receiver(null);
        SnapshotRestoreRequestMessage.receiver(null);
    }
}
