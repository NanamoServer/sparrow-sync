package net.momirealms.sparrow.sync.player.gate;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.player.PlayerManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.proxy.paper.connection.PaperCommonConnectionProxy;
import net.momirealms.sparrow.sync.snapshot.operation.SessionPrepareResult;
import net.momirealms.sparrow.sync.util.MinecraftComponents;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public final class PaperEventGate implements LoginGate, Listener {
    private final SparrowSync plugin;
    private PlayerManager playerManager;

    public PaperEventGate(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onDelayedEnable() {
        this.playerManager = this.plugin.playerManager();
        Bukkit.getPluginManager().registerEvents(this, this.plugin.javaPlugin());
    }

    // Paper 等监听器返回后才继续配置流程, 此处等待数据准备完成
    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        ServerConfigurationPacketListenerImpl listener = (ServerConfigurationPacketListenerImpl) PaperCommonConnectionProxy.INSTANCE.getHandle(connection);
        PlayerProfile profile = connection.getProfile();
        UUID uuid = profile.getId();
        String name = profile.getName();
        assert uuid != null;
        assert name != null;
        this.plugin.logger().file(LogCategory.JOIN, uuid, name, LogConstants.GATE_HELD, name);
        switch (this.playerManager.login(uuid, name, listener.connection).join()) {
            case SessionPrepareResult.Ready ignored -> this.plugin.logger().file(LogCategory.JOIN, uuid, name, LogConstants.GATE_RELEASED, name);
            case SessionPrepareResult.Busy ignored -> listener.disconnect(MinecraftComponents.fromAdventure(MessageConstants.KICK_LOGIN_TOO_FAST.build()));
            case SessionPrepareResult.Failed ignored -> listener.disconnect(MinecraftComponents.fromAdventure(MessageConstants.KICK_SYNC_NOT_READY.build()));
            case SessionPrepareResult.Rejected ignored -> {
            }
        }
    }
}