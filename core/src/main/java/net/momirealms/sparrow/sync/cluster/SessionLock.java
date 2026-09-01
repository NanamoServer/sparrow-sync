package net.momirealms.sparrow.sync.cluster;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class SessionLock {
    // TTL 仅回收崩溃后再无人登录的残留键
    private static final long LOCK_TTL_MILLIS = TimeUnit.DAYS.toMillis(15);
    private static final String RELEASE_SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";
    private static final String SEIZE_SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 else return 0 end";

    private SparrowSync plugin;
    private RedisConnector connector;
    private String keyPrefix;  // "ss:{cluster}:lock:", cluster 隔离共用一个 Redis 的多套集群
    private String serverId;

    public SessionLock(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public SessionLock(@NotNull RedisConnector connector, @NotNull String clusterId, @NotNull String serverId) {
        this.connector = connector;
        this.keyPrefix = "ss:" + clusterId + ":lock:";
        this.serverId = serverId;
    }

    /** 绑定 Redis 连接与本服锁命名空间. */
    public void onLoad() {
        this.connector = this.plugin.redisConnector();
        this.keyPrefix = "ss:" + PluginConfig.clusterId() + ":lock:";
        this.serverId = ServerConfig.serverId();
    }

    /**
     * 为玩家取锁, 一条 SET NX PX GET 原子完成查与抢.
     *
     * @param player 玩家 uuid
     * @return 抢到为 {@link AcquireOutcome.Acquired} 并携带写入的锁值, 被占为 {@link AcquireOutcome.Held} 并携带持有者的锁值;
     *         Redis 不可用时异常完成
     */
    @NotNull
    public CompletableFuture<AcquireOutcome> tryAcquire(@NotNull UUID player) {
        String value = this.newValue();
        return this.connector.connection().async()
                .setGet(this.key(player), bytes(value), SetArgs.Builder.nx().px(LOCK_TTL_MILLIS))
                .thenApply(existing -> existing == null ? (AcquireOutcome) new AcquireOutcome.Acquired(value) : new AcquireOutcome.Held(text(existing)))
                .toCompletableFuture();
    }

    /**
     * 释放自己持有的锁, 值完全一致才删.
     *
     * @param player 玩家 uuid
     * @param value 取锁或夺锁时拿到的完整锁值
     * @return 删掉为 true, 值不匹配(锁已被夺走或早已释放)为 false
     */
    @NotNull
    public CompletableFuture<Boolean> release(@NotNull UUID player, @NotNull String value) {
        RedisFuture<Long> deleted = this.connector.connection().async()
                .eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new byte[][]{this.key(player)}, bytes(value));
        return deleted.thenApply(count -> count != 0L).toCompletableFuture();
    }

    /**
     * 从判死的持有者手里夺锁, 锁值仍等于探测期间观察到的值才换成本服的新值.
     *
     * @param player 玩家 uuid
     * @param observedValue 探测期间观察到的持有者锁值
     * @return 夺到为携带新锁值的 Optional, 值已变化(对方仍活着或已被别人夺走)为 empty
     */
    @NotNull
    public CompletableFuture<Optional<String>> seize(@NotNull UUID player, @NotNull String observedValue) {
        String next = this.newValue();
        RedisFuture<Long> swapped = this.connector.connection().async()
                .eval(SEIZE_SCRIPT, ScriptOutputType.INTEGER, new byte[][]{this.key(player)}, bytes(observedValue), bytes(next), bytes(Long.toString(LOCK_TTL_MILLIS)));
        return swapped.thenApply(count -> count != 0L ? Optional.of(next) : Optional.<String>empty()).toCompletableFuture();
    }

    /**
     * 服务器崩溃时, 所有在线玩家的会话锁都未释放, 以 [服务器ID]:token 的形式留在 Redis 里.
     * 为了避免未释放锁的玩家在连接时把残留锁视为 "同serverId的服务器在持有锁" 的情况, 就需要在服务器启动&身份注册成功后进行必要的清理. e
     * @return 清除的数量
     */
    public int sweepStaleLocks() {
        RedisCommands<byte[], byte[]> commands = this.connector.connection().sync();
        ScanArgs pattern = ScanArgs.Builder.matches(this.keyPrefix + "*").limit(200);
        int swept = 0;
        KeyScanCursor<byte[]> cursor = commands.scan(pattern);
        while (true) {
            for (byte[] key : cursor.getKeys()) {
                byte[] observed = commands.get(key);
                if (observed == null) continue;
                LockValue holder = LockValue.parse(text(observed));
                if (holder == null || !holder.serverId().equals(this.serverId)) continue;
                // 值仍是观察值才删, 扫描期间被别的服夺走的锁不误删
                Long deleted = commands.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new byte[][]{key}, observed);
                if (deleted != 0L) swept++;
            }
            if (cursor.isFinished()) break;
            cursor = commands.scan(cursor, pattern);
        }
        return swept;
    }

    /**
     * 锁服务当前是否连通.
     * 断连重连期间为 false, 此时提交的锁操作压在队列里等重连按序补发, 压住最多 60s.
     */
    public boolean available() {
        return this.connector.available();
    }

    private String newValue() {
        return this.serverId + ':' + UUID.randomUUID();
    }

    private byte[] key(UUID player) {
        return bytes(this.keyPrefix + player);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 一次取锁的结果. */
    public sealed interface AcquireOutcome {

        /** 抢到了, value 是写进 Redis 的完整锁值, 释放时原样传回. */
        record Acquired(@NotNull String value) implements AcquireOutcome {
        }

        /** 被别的会话占着, value 是持有者的完整锁值, 供探测与夺锁使用. */
        record Held(@NotNull String value) implements AcquireOutcome {
        }
    }
}
