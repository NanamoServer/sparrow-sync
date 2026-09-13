package net.momirealms.sparrow.sync.cluster;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class SessionLock {
    private static final String KEY_PREFIX = "sparrow-sync:lock:";
    private static final long LOCK_TTL_MILLIS = TimeUnit.DAYS.toMillis(15);
    private static final String RELEASE_SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";
    private static final String SEIZE_SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 else return 0 end";

    private SparrowSync plugin;
    private RedisConnector connector;
    private String serverId;

    public SessionLock(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public SessionLock(@NotNull RedisConnector connector, @NotNull String serverId) {
        this.connector = connector;
        this.serverId = serverId;
    }

    public void onLoad() {
        this.connector = this.plugin.redisConnector();
        this.serverId = ServerConfig.serverId();
    }

    /**
     * 尝试获取玩家的会话锁, 同时返回本次写入或已有的锁值.
     *
     * @return 成功时返回 {@link AcquireOutcome.Acquired}, 已被占用时返回 {@link AcquireOutcome.Held}; Redis 操作失败时异常完成
     */
    @NotNull
    public CompletableFuture<AcquireOutcome> tryAcquire(@NotNull UUID player) {
        String value = this.newValue();
        return this.connector.connection().async()
                .setGet(this.key(player), bytes(value), SetArgs.Builder.nx().px(LOCK_TTL_MILLIS))
                .thenApply(existing -> existing == null ? (AcquireOutcome) new AcquireOutcome.Acquired(value) : new AcquireOutcome.Held(text(existing)))
                .toCompletableFuture();
    }

    /** 读取锁持有者, 无锁时返回空, 查询失败或锁值格式错误时异常完成. */
    @NotNull
    public CompletableFuture<Optional<LockValue>> holder(@NotNull UUID player) {
        return this.connector.connection().async().get(this.key(player)).thenApply(raw -> {
            if (raw == null) return Optional.<LockValue>empty();
            LockValue value = LockValue.parse(text(raw));
            if (value == null) throw new IllegalStateException("Invalid session lock value for " + player);
            return Optional.of(value);
        }).toCompletableFuture();
    }

    /**
     * 释放锁, 仅在当前锁值与传入值完全一致时删除.
     *
     * @param value 获取或接管锁时返回的完整锁值
     * @return 删除成功为 true, 锁不存在或已变更为 false
     */
    @NotNull
    public CompletableFuture<Boolean> release(@NotNull UUID player, @NotNull String value) {
        RedisFuture<Long> deleted = this.connector.connection().async()
                .eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new byte[][]{this.key(player)}, bytes(value));
        return deleted.thenApply(count -> count != 0L).toCompletableFuture();
    }

    /**
     * 接管锁, 仅在锁值仍与探测时一致时写入本服的新锁值.
     *
     * @param observedValue 探测时读到的完整锁值
     * @return 成功时返回新锁值, 锁不存在或已变更时返回空
     */
    @NotNull
    public CompletableFuture<Optional<String>> seize(@NotNull UUID player, @NotNull String observedValue) {
        String next = this.newValue();
        RedisFuture<Long> swapped = this.connector.connection().async()
                .eval(SEIZE_SCRIPT, ScriptOutputType.INTEGER, new byte[][]{this.key(player)}, bytes(observedValue), bytes(next), bytes(Long.toString(LOCK_TTL_MILLIS)));
        return swapped.thenApply(count -> count != 0L ? Optional.of(next) : Optional.<String>empty()).toCompletableFuture();
    }

    /**
     * 清理本服上次运行留下的锁, <strong>须在启动时成功注册服务器身份后调用</strong>.
     *
     * @return 清除的锁数
     */
    public int sweepStaleLocks() {
        RedisCommands<byte[], byte[]> commands = this.connector.connection().sync();
        ScanArgs pattern = ScanArgs.Builder.matches(KEY_PREFIX + "*").limit(200);
        int swept = 0;
        KeyScanCursor<byte[]> cursor = commands.scan(pattern);
        while (true) {
            for (byte[] key : cursor.getKeys()) {
                byte[] observed = commands.get(key);
                if (observed == null) continue;
                LockValue holder = LockValue.parse(text(observed));
                if (holder == null || !holder.serverId().equals(this.serverId)) continue;
                // 只删除锁值未变的条目, 保留扫描期间已被接管的锁
                Long deleted = commands.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new byte[][]{key}, observed);
                if (deleted != 0L) swept++;
            }
            if (cursor.isFinished()) break;
            cursor = commands.scan(cursor, pattern);
        }
        return swept;
    }

    public boolean available() {
        return this.connector.available();
    }

    private String newValue() {
        return this.serverId + ':' + UUID.randomUUID();
    }

    private byte[] key(UUID player) {
        return bytes(KEY_PREFIX + player);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public sealed interface AcquireOutcome {

        record Acquired(@NotNull String value) implements AcquireOutcome {
        }

        record Held(@NotNull String value) implements AcquireOutcome {
        }
    }
}
