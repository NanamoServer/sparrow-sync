package net.momirealms.sparrow.sync.cluster.cache;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

// 在现有 Redis 连接上保存一份短 TTL 的最新快照, 供接手服登录时直接取用.
public final class RedisSnapshotCache implements SnapshotCache {
    private static final String KEY_PREFIX = "sparrow-sync:latest-snapshot:";

    private final RedisConnector connector;
    private final BinarySnapshotCodec codec;
    private final SyncLogger logger;
    private final Executor executor;

    public RedisSnapshotCache(@NotNull SparrowSync plugin) {
        this.connector = plugin.redisConnector();
        this.codec = plugin.binaryCodec();
        this.logger = plugin.logger();
        this.executor = plugin.scheduler().async();
    }

    @Override
    @NotNull
    public CompletableFuture<Void> publish(@NotNull Snapshot snapshot, int ttlSeconds) {
        UUID player = snapshot.meta().player();
        RedisAsyncCommands<byte[], byte[]> commands = this.commands();
        if (commands == null) return CompletableFuture.completedFuture(null);
        byte[] payload;
        // 编码在调用线程同步完成, SET 才能与随后放锁的 EVAL 在同一连接上按序入队
        try {
            payload = this.codec.encode(snapshot);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return commands.set(key(player), payload, SetArgs.Builder.ex(Math.max(1, ttlSeconds))).toCompletableFuture().thenApply(ignored -> null);
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<Snapshot>> consume(@NotNull UUID player) {
        RedisAsyncCommands<byte[], byte[]> commands = this.commands();
        if (commands == null) return CompletableFuture.completedFuture(Optional.empty());
        // GETDEL 原子取回并删除, 同一份条目不会被第二个会话读到
        return commands.getdel(key(player)).toCompletableFuture()
                .thenApplyAsync(bytes -> this.decode(player, bytes), this.executor)
                .exceptionally(failure -> {
                    this.logger.file(LogCategory.APPLY, player, null, failure, LogConstants.SYNC_CACHE_READ_FAILED, player.toString(), String.valueOf(failure.getMessage()));
                    return Optional.empty();
                });
    }

    @Override
    @NotNull
    public CompletableFuture<Void> invalidate(@NotNull UUID player) {
        RedisAsyncCommands<byte[], byte[]> commands = this.commands();
        if (commands == null) return CompletableFuture.completedFuture(null);
        return commands.del(key(player)).toCompletableFuture().<Void>handle((deleted, failure) -> {
            if (failure != null) {
                this.logger.file(LogCategory.SAVE, player, null, failure, LogConstants.SYNC_CACHE_INVALIDATE_FAILED, player.toString(), String.valueOf(failure.getMessage()));
            }
            return null;
        });
    }

    // 连接未就绪或已断开时返回空, 调用方按未命中处理.
    @Nullable
    private RedisAsyncCommands<byte[], byte[]> commands() {
        if (!this.connector.available()) return null;
        return this.connector.connection().async();
    }

    // 帧被消费掉但读不出内容时记日志, 调用方回退数据库.
    @NotNull
    private Optional<Snapshot> decode(@NotNull UUID player, byte @Nullable [] bytes) {
        if (bytes == null) return Optional.empty();
        DecodedSnapshot decoded = this.codec.decode(bytes);
        if (decoded instanceof DecodedSnapshot.Valid valid) return Optional.of(valid.snapshot());
        DecodedSnapshot.Invalid invalid = (DecodedSnapshot.Invalid) decoded;
        this.logger.file(LogCategory.APPLY, player, null, LogConstants.SYNC_CACHE_CORRUPTED, player.toString(), invalid.reason().name(), invalid.detail());
        return Optional.empty();
    }

    private static byte[] key(@NotNull UUID player) {
        return (KEY_PREFIX + player).getBytes(StandardCharsets.UTF_8);
    }
}
