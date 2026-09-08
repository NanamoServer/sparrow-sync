package net.momirealms.sparrow.sync.redis;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.Logger;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.player.PlayerPresenceMessage;
import net.momirealms.sparrow.sync.map.message.MapInvalidationMessage;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.cluster.HandoffRequestMessage;
import net.momirealms.sparrow.sync.cluster.HandoffResponseMessage;
import net.momirealms.sparrow.sync.redis.heartbeats.ServerProbeMessage;
import net.momirealms.sparrow.sync.redis.heartbeats.ServerProbeResponseMessage;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

public final class MessageBrokerManager {
    private final SparrowSync plugin;
    private RedisConnector connector;
    private String serverId;
    private SyncLogger logger;

    private volatile MessageBroker<ByteBuf> broker;

    public MessageBrokerManager(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public MessageBrokerManager(@NotNull RedisConnector connector, @NotNull String serverId, @NotNull SyncLogger logger) {
        this.plugin = null;
        this.connector = connector;
        this.serverId = serverId;
        this.logger = logger;
    }

    /** 绑定 Redis 连接并订阅集群消息频道. */
    public void onLoad() {
        this.connector = this.plugin.redisConnector();
        this.serverId = ServerConfig.serverId();
        this.logger = this.plugin.logger();
        this.initialize();
    }

    public void initialize() {
        // Redis Pub/Sub 跨数据库共享频道, 将连接的数据库编号写入频道名以隔离通知与请求.
        MessageBroker<ByteBuf> broker = MessageBroker.<ByteBuf>builder(byteBuf -> byteBuf)
                .channel(("sparrow-sync:db:" + this.connector.database() + ":messages").getBytes(StandardCharsets.UTF_8))
                .serverId(this.serverId)
                .logger(new BrokerLogger(this.logger))
                .connection(this.connector.brokerConnection())
                .build();
        // 所有服务器的注册顺序必须一致, 新消息只能在末尾追加
        broker.registry().register(HandoffRequestMessage.ID, HandoffRequestMessage.CODEC);
        broker.registry().register(HandoffResponseMessage.ID, HandoffResponseMessage.CODEC);
        broker.registry().register(ServerProbeMessage.ID, ServerProbeMessage.CODEC);
        broker.registry().register(ServerProbeResponseMessage.ID, ServerProbeResponseMessage.CODEC);
        broker.registry().register(MapInvalidationMessage.ID, MapInvalidationMessage.CODEC);
        broker.registry().register(PlayerPresenceMessage.ID, PlayerPresenceMessage.CODEC);
        broker.subscribe();
        this.broker = broker;
    }

    @NotNull
    public MessageBroker<ByteBuf> broker() {
        return this.broker;
    }

    // 只退订频道, 连接与 client 的关闭归 RedisConnector
    public void shutdown() {
        MessageBroker<ByteBuf> broker = this.broker;
        if (broker != null) broker.unsubscribe();
    }

    // 消息 broker 的日志出口接到插件日志上, debug 噪音直接丢弃
    private record BrokerLogger(SyncLogger logger) implements Logger {

        @Override
        public void error(String msg, Throwable t) {
            this.logger.error(msg, t);
        }

        @Override
        public void warn(String msg, Throwable t) {
            this.logger.warn(msg, t);
        }

        @Override
        public void info(String msg) {
            this.logger.info(msg);
        }

        @Override
        public void debug(String msg) {
        }
    }
}
