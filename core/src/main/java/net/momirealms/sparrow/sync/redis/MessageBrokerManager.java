package net.momirealms.sparrow.sync.redis;

import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.session.cluster.HandoffRequestMessage;
import net.momirealms.sparrow.sync.session.cluster.HandoffResponseMessage;
import net.nyana.message.MessageBroker;
import org.jetbrains.annotations.NotNull;

public final class MessageBrokerManager {
    private final RedisConnector connector;
    private final String clusterId;
    private final String serverId;
    private final SyncLogger logger;

    private volatile MessageBroker broker;

    public MessageBrokerManager(@NotNull RedisConnector connector, @NotNull String clusterId, @NotNull String serverId, @NotNull SyncLogger logger) {
        this.connector = connector;
        this.clusterId = clusterId;
        this.serverId = serverId;
        this.logger = logger;
    }

    public void initialize() {
        MessageBroker broker = MessageBroker.builder()
                .appId(this.clusterId)
                .brokerId(this.serverId)
                .logger(new BrokerLogger(this.logger))
                .connection(this.connector.brokerConnection())
                .build();
        // 所有服务器的注册顺序必须一致, 新消息只能在末尾追加
        broker.registry().register(HandoffRequestMessage.ID, HandoffRequestMessage.CODEC);
        broker.registry().register(HandoffResponseMessage.ID, HandoffResponseMessage.CODEC);
        broker.subscribe();
        this.broker = broker;
    }

    @NotNull
    public MessageBroker broker() {
        return this.broker;
    }

    // 只退订频道, 连接与 client 的关闭归 RedisConnector
    public void shutdown() {
        MessageBroker broker = this.broker;
        if (broker != null) broker.unsubscribe();
    }

    // nyana-message 的日志出口接到插件日志上, debug 噪音直接丢弃
    private record BrokerLogger(SyncLogger logger) implements net.nyana.message.Logger {

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
