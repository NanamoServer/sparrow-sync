package net.momirealms.sparrow.sync.cluster;

import net.momirealms.sparrow.sync.cluster.message.SnapshotApplyContainersResponseMessage;
import net.momirealms.sparrow.sync.snapshot.operation.ContainerApplyResult;
import net.momirealms.sparrow.sync.cluster.message.SnapshotApplyContainersRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotCaptureRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotCaptureResponseMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotRestoreRequestMessage;
import net.momirealms.sparrow.sync.cluster.message.SnapshotRestoreResponseMessage;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotCaptureResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotRestoreResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class RemoteSnapshotManager {
    private final SparrowSync plugin;
    private volatile boolean closed;

    public RemoteSnapshotManager(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    /** 请求目标服采集并保存快照, 等待操作结果, 通信失败或超时返回 UNAVAILABLE. */
    @NotNull
    public CompletableFuture<SnapshotCaptureResult> capture(@NotNull String serverId, @NotNull UUID playerId, @NotNull SaveCause cause) {
        if (this.closed) return CompletableFuture.completedFuture(SnapshotCaptureResult.OFFLINE);
        byte[] heartbeat = ("sparrow-sync:server:" + serverId).getBytes(StandardCharsets.UTF_8);
        return this.plugin.redisConnector().connection().async().get(heartbeat).toCompletableFuture().thenCompose(value -> {
            if (this.closed || value == null) return CompletableFuture.completedFuture(SnapshotCaptureResult.OFFLINE);
            return this.plugin.messageBrokerManager().broker()
                    .publishTwoWay(new SnapshotCaptureRequestMessage(playerId, cause), serverId)
                    .thenApply(SnapshotCaptureResponseMessage::result);
        }).orTimeout(30, TimeUnit.SECONDS).exceptionally(failure -> SnapshotCaptureResult.UNAVAILABLE);
    }

    /** 在本服处理采集请求, 玩家离线时返回 OFFLINE. */
    @NotNull
    public CompletableFuture<SnapshotCaptureResult> receiveCapture(@NotNull UUID playerId, @NotNull SaveCause cause) {
        if (this.closed) return CompletableFuture.completedFuture(SnapshotCaptureResult.OFFLINE);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return CompletableFuture.completedFuture(SnapshotCaptureResult.OFFLINE);
        return this.plugin.snapshotService().capture(player, cause).exceptionally(failure -> {
            this.plugin.logger().warn(TranslationManager.console("log.command.snapshot_failed", "capture"), failure);
            return SnapshotCaptureResult.FAILED;
        });
    }

    /** 请求目标服恢复快照, 等待操作结果, 通信失败或超时返回 UNAVAILABLE. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restore(@NotNull String serverId, @NotNull UUID playerId, @NotNull UUID snapshotId) {
        if (this.closed) return CompletableFuture.completedFuture(SnapshotRestoreResult.OFFLINE);
        byte[] heartbeat = ("sparrow-sync:server:" + serverId).getBytes(StandardCharsets.UTF_8);
        return this.plugin.redisConnector().connection().async().get(heartbeat).toCompletableFuture().thenCompose(value -> {
            if (this.closed || value == null) return CompletableFuture.completedFuture(SnapshotRestoreResult.OFFLINE);
            return this.plugin.messageBrokerManager().broker()
                    .publishTwoWay(new SnapshotRestoreRequestMessage(playerId, snapshotId), serverId)
                    .thenApply(SnapshotRestoreResponseMessage::result);
        }).orTimeout(30, TimeUnit.SECONDS).exceptionally(failure -> SnapshotRestoreResult.UNAVAILABLE);
    }

    /** 在本服处理恢复请求, 玩家离线时返回 OFFLINE. */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> receiveRestore(@NotNull UUID playerId, @NotNull UUID snapshotId) {
        if (this.closed) return CompletableFuture.completedFuture(SnapshotRestoreResult.OFFLINE);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return CompletableFuture.completedFuture(SnapshotRestoreResult.OFFLINE);
        return this.plugin.snapshotService().restore(player, snapshotId).exceptionally(failure -> {
            this.plugin.logger().warn(TranslationManager.console("log.command.snapshot_failed", "restore"), failure);
            return new SnapshotRestoreResult.Failed(SnapshotRestoreResult.Stage.PREPARE, failure.toString(), failure, List.of());
        });
    }

    /** 请求目标服应用已保存快照中的背包和末影箱, 通信失败或超时返回 UNAVAILABLE. */
    @NotNull
    public CompletableFuture<ContainerApplyResult> applyContainers(@NotNull String serverId, @NotNull UUID playerId, @NotNull UUID snapshotId) {
        if (this.closed) return CompletableFuture.completedFuture(ContainerApplyResult.OFFLINE);
        byte[] heartbeat = ("sparrow-sync:server:" + serverId).getBytes(StandardCharsets.UTF_8);
        return this.plugin.redisConnector().connection().async().get(heartbeat).toCompletableFuture().thenCompose(value -> {
            if (this.closed || value == null) return CompletableFuture.completedFuture(ContainerApplyResult.OFFLINE);
            return this.plugin.messageBrokerManager().broker()
                    .publishTwoWay(new SnapshotApplyContainersRequestMessage(playerId, snapshotId), serverId)
                    .thenApply(SnapshotApplyContainersResponseMessage::result);
        }).orTimeout(30, TimeUnit.SECONDS).exceptionally(failure -> ContainerApplyResult.UNAVAILABLE);
    }

    /** 在本服处理容器应用请求, 玩家离线或服务停止时返回 OFFLINE. */
    @NotNull
    public CompletableFuture<ContainerApplyResult> receiveApplyContainers(@NotNull UUID playerId, @NotNull UUID snapshotId) {
        if (this.closed) return CompletableFuture.completedFuture(ContainerApplyResult.OFFLINE);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return CompletableFuture.completedFuture(ContainerApplyResult.OFFLINE);
        return this.plugin.snapshotService().applyContainers(player, snapshotId).exceptionally(failure -> {
            this.plugin.logger().warn(TranslationManager.console("log.command.snapshot_failed", "restore"), failure);
            return ContainerApplyResult.FAILED;
        });
    }

    public void shutdown() {
        this.closed = true;
    }
}
