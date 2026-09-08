package net.momirealms.sparrow.sync.player;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.incendo.cloud.suggestion.Suggestion;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerDirectory {
    private static final String ROSTER_PREFIX = "ss:online-players:";
    private static final long REFRESH_MILLIS = 30000;
    private static final long NAME_TTL_SECONDS = 300;

    private final SparrowSync plugin;
    private final Map<String, Map<String, PlayerIdentity>> servers = new HashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Optional<PlayerIdentity>>> loadingNames = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private String serverId;
    private byte[] rosterKey;
    private volatile OnlineView online = OnlineView.EMPTY; // 更新后发布完整视图, 补全线程直接读取
    private volatile boolean closed;
    private SchedulerTask task;

    public PlayerDirectory(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    /** 绑定本服身份和消息入口, 启动全服名单校准. */
    public void onDelayedEnable() {
        this.serverId = ServerConfig.serverId();
        this.rosterKey = (ROSTER_PREFIX + this.serverId).getBytes(StandardCharsets.UTF_8);
        PlayerPresenceMessage.listener(this::acceptPresence);
        // 启动时拉取已有玩家, 此后每 30 秒补齐漏掉的通知.
        this.task = this.plugin.scheduler().asyncRepeating(this::refresh, 0, REFRESH_MILLIS, TimeUnit.MILLISECONDS);
    }

    // 每轮只有一个刷新在途, Redis 断线时不会不断堆积整服名单请求.
    void refresh() {
        if (this.closed || !this.refreshing.compareAndSet(false, true)) return;
        try {
            RedisCommands<byte[], byte[]> commands = this.plugin.redisConnector().connection().sync();
            // 异步任务读取 ACTIVE 会话中的身份, 重写本服 Hash 来修正遗漏的进退服记录.
            Map<UUID, String> local = this.plugin.sessionManager().onlinePlayers();
            Map<byte[], byte[]> fields = new HashMap<>();
            local.forEach((uuid, name) -> fields.put(name.getBytes(StandardCharsets.UTF_8), UUIDUtils.toBytes(uuid)));
            commands.del(this.rosterKey);
            if (!fields.isEmpty()) {
                commands.hset(this.rosterKey, fields);
            }
            Map<String, Map<String, PlayerIdentity>> rosters = new HashMap<>();
            // 按扫描批次查询心跳, 每批仅为仍存活的服务器读取名单.
            ScanArgs scan = ScanArgs.Builder.matches(ROSTER_PREFIX + "*").limit(64);
            KeyScanCursor<byte[]> cursor = commands.scan(scan);
            while (true) {
                List<byte[]> keys = cursor.getKeys();
                if (!keys.isEmpty()) {
                    byte[][] heartbeatKeys = new byte[keys.size()][];
                    for (int i = 0; i < keys.size(); i++) {
                        String id = new String(keys.get(i), StandardCharsets.UTF_8).substring(ROSTER_PREFIX.length());
                        heartbeatKeys[i] = ("ss:server:" + id).getBytes(StandardCharsets.UTF_8);
                    }
                    List<KeyValue<byte[], byte[]>> heartbeats = commands.mget(heartbeatKeys);
                    for (int i = 0; i < keys.size(); i++) {
                        byte[] key = keys.get(i);
                        if (!heartbeats.get(i).hasValue()) {
                            // 心跳消失时整服清理, 下方发布的新视图也会移除这些玩家.
                            commands.del(key);
                            continue;
                        }
                        Map<String, PlayerIdentity> players = new HashMap<>();
                        commands.hgetall(key).forEach((name, uuid) -> {
                            String playerName = new String(name, StandardCharsets.UTF_8);
                            players.put(playerName.toLowerCase(Locale.ROOT), new PlayerIdentity(UUIDUtils.fromBytes(uuid), playerName));
                        });
                        rosters.put(new String(key, StandardCharsets.UTF_8).substring(ROSTER_PREFIX.length()), players);
                    }
                }
                if (cursor.isFinished() || this.closed) {
                    break;
                }
                cursor = commands.scan(cursor, scan);
            }
            // Redis 查询在锁外完成, 与通知共用的锁只覆盖本地名单替换.
            synchronized (this) {
                if (!this.closed) {
                    this.servers.clear();
                    this.servers.putAll(rosters);
                    this.rebuildOnline();
                }
            }
        } catch (RedisException ignored) {
            // 保留本地名单, 下一轮校准补齐丢失的消息和缓存写入.
        } finally {
            this.refreshing.set(false);
            if (this.closed) {
                // 关闭期间仍在途的校准可能写回本服名单, 收尾时再清理一次.
                this.deleteRoster();
            }
        }
    }

    // 由进退服事件更新本地名单, 异步写入 Redis 并通知其他服务器.
    public void presence(@NotNull UUID uuid, @NotNull String name, boolean joined) {
        if (this.closed) return;
        PlayerPresenceMessage message = new PlayerPresenceMessage(this.serverId, uuid, name, joined);
        // 本服立即可见, 跨服缓存写入交给 Lettuce 异步执行.
        this.acceptPresence(message);
        RedisAsyncCommands<byte[], byte[]> commands = this.plugin.redisConnector().connection().async();
        CompletableFuture<?> write = joined
                ? commands.hset(this.rosterKey, name.getBytes(StandardCharsets.UTF_8), UUIDUtils.toBytes(uuid)).toCompletableFuture()
                : commands.hdel(this.rosterKey, name.getBytes(StandardCharsets.UTF_8)).toCompletableFuture();
        MessageBroker<ByteBuf> broker = this.plugin.messageBrokerManager().broker();
        // Redis 接受名单变更后再广播, 写入或通知失败由周期校准补齐.
        write.thenCompose(ignored -> this.closed ? CompletableFuture.completedFuture(0L)
                : commands.publish(broker.channel(), broker.encode(message)).toCompletableFuture());
    }

    synchronized void acceptPresence(PlayerPresenceMessage message) {
        if (this.closed) return;
        Map<String, PlayerIdentity> players = this.servers.computeIfAbsent(message.serverId(), ignored -> new HashMap<>());
        String name = message.name().toLowerCase(Locale.ROOT);
        if (message.joined()) {
            PlayerIdentity identity = new PlayerIdentity(message.uuid(), message.name());
            // 本服广播回环和重复在线通知可以复用已经生成的补全项.
            if (identity.equals(players.put(name, identity))) return;
        } else {
            // 跨服时旧服的离线通知只移除旧服记录, 目标服的在线记录仍然有效.
            players.remove(name);
            if (players.isEmpty()) {
                this.servers.remove(message.serverId());
            }
        }
        this.rebuildOnline();
    }

    // 进退服通知和全量校准持有当前对象的监视器调用, 按名字合并各服的在线身份.
    private void rebuildOnline() {
        Map<String, PlayerIdentity> players = new HashMap<>();
        this.servers.values().forEach(players::putAll);
        this.online = OnlineView.of(players);
    }

    @NotNull
    public List<PlayerIdentity> onlinePlayers() {
        return this.online.players();
    }

    // 在线操作按玩家名定位来源服, 接收方仍会核对自己的实际会话.
    @NotNull
    public synchronized Optional<String> server(@NotNull String name) {
        String key = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Map<String, PlayerIdentity>> entry : this.servers.entrySet()) {
            if (entry.getValue().containsKey(key)) return Optional.of(entry.getKey());
        }
        return Optional.empty();
    }

    // 按前缀返回已缓存的补全项, 空前缀提供全部在线名字.
    @NotNull
    public List<Suggestion> suggestions(@NotNull String prefix) {
        OnlineView current = this.online;
        if (prefix.isEmpty()) return current.suggestions();
        // 非空输入只过滤首字符对应的候选桶, 结果继续引用更新时创建的 Suggestion.
        List<Suggestion> result = new ArrayList<>();
        List<Suggestion> candidates = current.prefixes().getOrDefault(Character.toLowerCase(prefix.charAt(0)), List.of());
        int size = candidates.size();
        for (int i = 0; i < size; i++) {
            Suggestion candidate = candidates.get(i);
            if (candidate.suggestion().regionMatches(true, 0, prefix, 0, prefix.length())) {
                result.add(candidate);
            }
        }
        return result;
    }

    // 按玩家名查询本地在线名单.
    @NotNull
    public Optional<PlayerIdentity> cached(@NotNull String input) {
        OnlineView current = this.online;
        PlayerIdentity player = current.byName().get(input.toLowerCase(Locale.ROOT));
        return Optional.ofNullable(player);
    }

    // 按全服在线名单、Redis、数据库的顺序解析玩家信息.
    @NotNull
    public CompletableFuture<Optional<PlayerIdentity>> resolve(@NotNull String input) {
        Optional<PlayerIdentity> cached = this.cached(input);
        if (cached.isPresent()) return CompletableFuture.completedFuture(cached);
        // 同名的并发查询共享一次远端读取, 完成后移除在途占位.
        CompletableFuture<Optional<PlayerIdentity>> pending = this.loadingNames.computeIfAbsent(input, this::loadName);
        pending.whenComplete((result, failure) -> this.loadingNames.remove(input, pending));
        // 调用方的取消和超时只影响自己的等待, 其他同名查询仍可取得共享结果.
        return pending.copy();
    }

    private CompletableFuture<Optional<PlayerIdentity>> loadName(String name) {
        byte[] key = nameKey(name);
        // Redis 名字缓存最多等待 1 秒, 读取失败或未命中时继续查 storage.
        return this.plugin.redisConnector().connection().async().get(key).toCompletableFuture()
                .orTimeout(1, TimeUnit.SECONDS)
                .handle((value, failure) -> failure == null ? value : null)
                .thenCompose(value -> {
                    if (value != null && value.length == 16) {
                        PlayerIdentity identity = new PlayerIdentity(UUIDUtils.fromBytes(value), name);
                        return CompletableFuture.completedFuture(Optional.of(identity));
                    }
                    // 只回填存在的身份, 未知名字保持 empty, 数据库异常交给命令反馈.
                    return this.plugin.storageProvider().lookupUser(name).thenApply(found -> found.map(uuid -> {
                        PlayerIdentity identity = new PlayerIdentity(uuid, name);
                        this.writeName(identity, true);
                        return identity;
                    }));
                }).orTimeout(3, TimeUnit.SECONDS).whenComplete((result, failure) -> {
                    // 3 秒限制整条名字加载链, 最终查询失败在这里记录一次.
                    if (failure != null && !this.closed) {
                        this.plugin.logger().warn(TranslationManager.console("log.command.player_lookup_failed", name), failure);
                    }
                });
    }

    // 用户名映射成功写入数据库后更新 Redis 名字缓存
    public void remember(@NotNull UUID uuid, @NotNull String name) {
        PlayerIdentity identity = new PlayerIdentity(uuid, name);
        this.writeName(identity, false);
    }

    private void writeName(PlayerIdentity player, boolean onlyAbsent) {
        if (this.closed) return;
        SetArgs args = SetArgs.Builder.ex(NAME_TTL_SECONDS);
        if (onlyAbsent) {
            // storage 查询的被动回填保留 Redis 中已存在的映射, 登录更新则直接覆盖.
            args.nx();
        }
        this.plugin.redisConnector().connection().async().set(nameKey(player.name()), UUIDUtils.toBytes(player.uuid()), args);
    }

    // 停止名单维护并清理本服在线记录.
    public synchronized void shutdown() {
        // 先撤销消息入口, closed 同时阻止在途校准重新发布本地名单.
        PlayerPresenceMessage.listener(null);
        this.servers.clear();
        this.closed = true;
        if (this.task != null) {
            this.task.cancel();
        }
        this.online = OnlineView.EMPTY;
        this.deleteRoster();
    }

    private void deleteRoster() {
        if (this.rosterKey != null && this.plugin.redisConnector().available()) {
            this.plugin.redisConnector().connection().async().del(this.rosterKey);
        }
    }

    private static byte[] nameKey(String name) {
        // 保留数据库名字比较语义, 历史名字缓存不按大小写折叠.
        return ("ss:user-name:" + HexFormat.of().formatHex(name.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
    }

    private record OnlineView(Map<String, PlayerIdentity> byName, List<PlayerIdentity> players, List<Suggestion> suggestions, Map<Character, List<Suggestion>> prefixes) {
        private static final OnlineView EMPTY = new OnlineView(Map.of(), List.of(), List.of(), Map.of());

        private static OnlineView of(Map<String, PlayerIdentity> values) {
            // 排序在名单更新时完成, 空输入和前缀补全沿用同一份名字顺序.
            List<PlayerIdentity> players = values.values().stream().sorted(Comparator.comparing(PlayerIdentity::name, String.CASE_INSENSITIVE_ORDER)).toList();
            List<Suggestion> suggestions = new ArrayList<>(players.size());
            Map<Character, List<Suggestion>> prefixes = new HashMap<>();
            int size = players.size();
            for (int i = 0; i < size; i++) {
                PlayerIdentity player = players.get(i);
                // 名字候选同时用于空输入列表和前缀桶, 两处共享同一个对象.
                Suggestion name = Suggestion.suggestion(player.name());
                suggestions.add(name);
                if (!player.name().isEmpty()) {
                    prefixes.computeIfAbsent(Character.toLowerCase(player.name().charAt(0)), ignored -> new ArrayList<>()).add(name);
                }

            }
            // 构建完成后冻结各个列表, 读取线程可直接复用视图中的集合和候选对象.
            prefixes.replaceAll((key, list) -> List.copyOf(list));
            return new OnlineView(Map.copyOf(values), players, List.copyOf(suggestions), Map.copyOf(prefixes));
        }
    }
}
