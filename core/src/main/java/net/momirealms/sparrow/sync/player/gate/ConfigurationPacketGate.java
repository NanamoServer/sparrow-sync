package net.momirealms.sparrow.sync.player.gate;

import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.player.PlayerManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.proxy.minecraft.server.network.ServerCommonPacketListenerImplProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.network.ServerConfigurationPacketListenerImplProxy;
import net.momirealms.sparrow.sync.proxy.mojang.authlib.GameProfileProxy;
import net.momirealms.sparrow.sync.snapshot.operation.SessionPrepareResult;
import net.momirealms.sparrow.sync.util.MinecraftComponents;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.network.NMSPacketEvent;
import net.momirealms.sparrow.ui.network.NetworkManager;
import net.momirealms.sparrow.ui.network.NetworkUser;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketFlow;
import net.momirealms.sparrow.ui.network.packet.PacketType;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("UnstableApiUsage")
public final class ConfigurationPacketGate implements LoginGate {
    private SparrowSync plugin;
    private PlayerManager playerManager;

    public ConfigurationPacketGate(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onDelayedEnable() {
        this.playerManager = this.plugin.playerManager();
        NetworkManager networkManager = SparrowUI.getInstance().networkManager();
        networkManager.listenNMS(
                new PacketType("minecraft:finish_configuration", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND),
                (user, event, packet) -> this.onFinishConfiguration(user, event)
        );
    }

    // 运行在连接的 eventLoop 上, 不能阻塞等待
    private void onFinishConfiguration(NetworkUser user, NMSPacketEvent event) {
        Channel channel = user.channel();
        if (!(channel.pipeline().get("packet_handler") instanceof Connection connection)) return;
        if (!(connection.getPacketListener() instanceof ServerConfigurationPacketListenerImpl listener)) return;
        Object profile = ServerConfigurationPacketListenerImplProxy.INSTANCE.getGameProfile(listener);
        UUID uuid = GameProfileProxy.INSTANCE.getId(profile);
        String name = GameProfileProxy.INSTANCE.getName(profile);
        assert uuid != null;
        assert name != null;
        // 暂缓发送配置结束包, 数据准备期间暂停原版的应答超时计时
        event.cancel();
        ServerCommonPacketListenerImplProxy.INSTANCE.setClosed(listener, false);
        this.plugin.logger().file(LogCategory.JOIN, uuid, name, LogConstants.GATE_HELD);
        this.playerManager.login(uuid, name, connection).thenAccept(result -> {
            switch (result) {
                case SessionPrepareResult.Ready ignored -> this.resume(user, listener, uuid, name);
                case SessionPrepareResult.Busy ignored -> this.disconnect(listener, MessageConstants.KICK_LOGIN_TOO_FAST.build());
                case SessionPrepareResult.Failed ignored -> this.disconnect(listener, MessageConstants.KICK_SYNC_NOT_READY.build());
                case SessionPrepareResult.Rejected ignored -> {
                }
            }
        });
    }

    // 补发配置结束包, 并重新开始原版应答计时
    private void resume(NetworkUser user, ServerConfigurationPacketListenerImpl listener, UUID uuid, String name) {
        user.channel().eventLoop().execute(() -> {
            ServerCommonPacketListenerImplProxy.INSTANCE.setClosedListenerTime(listener, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            ServerCommonPacketListenerImplProxy.INSTANCE.setClosed(listener, true);
            this.plugin.logger().file(LogCategory.JOIN, uuid, name, LogConstants.GATE_RELEASED, name);
            user.sendPacketSilently(ClientboundFinishConfigurationPacket.INSTANCE);
        });
    }

    private void disconnect(ServerConfigurationPacketListenerImpl listener, Component reason) {
        net.minecraft.network.chat.Component vanillaReason = MinecraftComponents.fromAdventure(reason);
        this.plugin.scheduler().platform().execute(() -> listener.disconnect(vanillaReason));
    }
}