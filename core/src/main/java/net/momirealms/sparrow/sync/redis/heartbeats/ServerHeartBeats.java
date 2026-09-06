package net.momirealms.sparrow.sync.redis.heartbeats;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.plugin.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 本服在同步集群中的身份注册表.
 * 心跳键 {@code ss:server:{serverId}} 的值为本次启动的 token, 周期续期, 停跳的服务器随 TTL 自动消失.
 * 启动注册时发现同 id 的键已存在则发起 redis 消息探查所以还存活, 若存活则属配置冲突, 关闭服务器.
 */
public final class ServerHeartBeats {
    private static final long HEARTBEAT_INTERVAL_MILLIS = 3000;                      // 心跳续期周期
    private static final long HEARTBEAT_TTL_MILLIS = HEARTBEAT_INTERVAL_MILLIS * 3;  // 心跳键存活期, 停跳超过它身份即消失
    private static final long PROBE_WAIT_MILLIS = 3000;                              // 启动冲突探测的应答等待
    private static final String SEIZE_SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 else return 0 end";
    private static final String DELETE_SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";

    private SparrowSync plugin;
    private RedisConnector connector;
    private MessageBroker<ByteBuf> broker;
    private SessionLock lock;
    private SyncLogger logger;
    private String serverId;
    private final String token;  // 本次启动的身份凭据, 心跳键的值
    private byte[] key;
    private HeartbeatScheduler scheduler;
    private final long heartbeatIntervalMillis;
    private final long heartbeatTtlMillis;
    private final long probeWaitMillis;
    private volatile SchedulerTask heartbeatTask;

    public ServerHeartBeats(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
        this.token = UUID.randomUUID().toString();
        this.heartbeatIntervalMillis = HEARTBEAT_INTERVAL_MILLIS;
        this.heartbeatTtlMillis = HEARTBEAT_TTL_MILLIS;
        this.probeWaitMillis = PROBE_WAIT_MILLIS;
    }

    public ServerHeartBeats(
            @NotNull RedisConnector connector,
            @NotNull MessageBroker<ByteBuf> broker,
            @NotNull SessionLock lock,
            @NotNull String serverId,
            @NotNull SyncLogger logger,
            @NotNull HeartbeatScheduler scheduler
    ) {
        this(connector, broker, lock, serverId, logger, scheduler, HEARTBEAT_INTERVAL_MILLIS, HEARTBEAT_TTL_MILLIS, PROBE_WAIT_MILLIS);
    }

    ServerHeartBeats(
            @NotNull RedisConnector connector,
            @NotNull MessageBroker<ByteBuf> broker,
            @NotNull SessionLock lock,
            @NotNull String serverId,
            @NotNull SyncLogger logger,
            @NotNull HeartbeatScheduler scheduler,
            long heartbeatIntervalMillis,
            long heartbeatTtlMillis,
            long probeWaitMillis
    ) {
        this.connector = connector;
        this.broker = broker;
        this.lock = lock;
        this.logger = logger;
        this.serverId = serverId;
        this.token = UUID.randomUUID().toString();
        this.key = ("ss:server:" + serverId).getBytes(StandardCharsets.UTF_8);
        this.scheduler = scheduler;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.heartbeatTtlMillis = heartbeatTtlMillis;
        this.probeWaitMillis = probeWaitMillis;
        ServerProbeMessage.registry(this);
    }

    /** 绑定集群依赖并注册本服心跳身份. */
    public void onLoad() {
        this.connector = this.plugin.redisConnector();
        this.broker = this.plugin.messageBrokerManager().broker();
        this.lock = this.plugin.sessionLock();
        this.logger = this.plugin.logger();
        this.serverId = ServerConfig.serverId();
        this.key = ("ss:server:" + this.serverId).getBytes(StandardCharsets.UTF_8);
        this.scheduler = (task, intervalMillis) -> this.plugin.scheduler().asyncRepeating(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        ServerProbeMessage.registry(this);
        if (!this.initialize()) {
            this.logger.error(" ");
            this.logger.error(" ");
            this.logger.error(" ");
            this.logger.error("============================================================");
            this.logger.error(TranslationManager.console(LogConstants.SERVER_ID_DUPLICATE, ServerConfig.serverId()));
            this.logger.error("============================================================");
            this.logger.error(" ");
            this.logger.error(" ");
            this.logger.error(" ");
            Bukkit.getServer().shutdown();
        }
    }

    /**
     * 注册本服身份并启动心跳.
     *
     * @return 注册成功为 true, 若出现同 id 的服务器仍在线为 false.
     */
    public boolean initialize() {
        RedisCommands<byte[], byte[]> commands = this.connector.connection().sync();
        byte[] existing = commands.setGet(this.key, this.token.getBytes(StandardCharsets.UTF_8), SetArgs.Builder.nx().px(this.heartbeatTtlMillis));
        if (existing != null && !this.claimStaleIdentity(commands, existing)) return false;
        int swept = this.lock.sweepStaleLocks(); // 清理上次崩溃残留的玩家锁
        if (swept > 0) this.logger.file(LogCategory.LOCK, null, null, LogConstants.LOCK_SWEPT, String.valueOf(swept));
        this.heartbeatTask = this.scheduler.repeating(this::heartbeat, this.heartbeatIntervalMillis);
        return true;
    }

    // 同 id 的心跳键已存在就进行探测, 有应答 = 对方在线, 属配置冲突; 静默 = 残留身份.
    private boolean claimStaleIdentity(RedisCommands<byte[], byte[]> commands, byte[] observed) {
        if (this.probeHolder()) return false;
        // 值仍是观察值才接管, 变了说明有活人在动同样按冲突处理
        Long swapped = commands.eval(
                SEIZE_SCRIPT,
                ScriptOutputType.INTEGER,
                new byte[][]{this.key},
                observed,
                this.token.getBytes(StandardCharsets.UTF_8),
                Long.toString(this.heartbeatTtlMillis).getBytes(StandardCharsets.UTF_8)
        );
        if (swapped == 0L) return false;
        this.logger.file(LogCategory.LIFECYCLE, null, null, LogConstants.SERVER_ID_SEIZED, new String(observed, StandardCharsets.UTF_8));
        return true;
    }

    // 定向探测持有同 id 的服务器, 等出应答即在线. 自己发出的探测不会被自己应答.
    private boolean probeHolder() {
        try {
            this.broker.publishTwoWay(new ServerProbeMessage(this.token), this.serverId)
                    .orTimeout(this.probeWaitMillis, TimeUnit.MILLISECONDS)
                    .join();
            return true;
        } catch (CompletionException exception) {
            return false;
        }
    }

    // 回答一次身份探测. 探测来自另一台同 id 的服务器时应答自己的 token; 若来自发自收时不应答.
    @Nullable
    ServerProbeResponseMessage answerProbe(@NotNull String requesterToken) {
        return this.token.equals(requesterToken) ? null : new ServerProbeResponseMessage(this.token);
    }

    // 心跳续期, 断连期间命令压在 Lettuce 队列里重连补发, 停跳超过 TTL 后键过期、身份消失
    private void heartbeat() {
        this.connector.connection().async().set(this.key, this.token.getBytes(StandardCharsets.UTF_8), SetArgs.Builder.px(this.heartbeatTtlMillis));
    }

    // 停跳并注销身份, 值仍是自己的才删; 删除不等待结果, 命令丢失由 TTL 兜底清掉.
    public void shutdown() {
        if (this.connector == null) return;
        SchedulerTask task = this.heartbeatTask;
        if (task != null) task.cancel();
        this.connector.connection().async().eval(DELETE_SCRIPT, ScriptOutputType.INTEGER, new byte[][]{this.key}, this.token.getBytes(StandardCharsets.UTF_8));
    }
    /** 心跳调度的最小依赖面, 装配侧接插件调度器, 测试侧接任意定时器. */
    public interface HeartbeatScheduler {
        @NotNull
        SchedulerTask repeating(@NotNull Runnable task, long intervalMillis);
    }
}
