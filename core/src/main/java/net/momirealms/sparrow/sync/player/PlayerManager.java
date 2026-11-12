package net.momirealms.sparrow.sync.player;

import net.minecraft.network.Connection;
import net.momirealms.sparrow.sync.player.component.PlayerLifecycle;
import net.momirealms.sparrow.sync.player.component.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.operation.SessionPrepareResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
public final class PlayerManager {
    private final PlayerLifecycle lifecycle = new PlayerLifecycle();

    public void onLoad() {
        this.lifecycle.onLoad();
    }

    public void onDelayedEnable() {
        this.lifecycle.onDelayedEnable();
    }

    public void injectPlayerDataStorage(@NotNull String version) {
        this.lifecycle.injectPlayerDataStorage(version);
    }

    @NotNull
    public CompletableFuture<SessionPrepareResult> login(@NotNull UUID uuid, @NotNull String name, @NotNull Connection connection) {
        return this.lifecycle.login(uuid, name, connection);
    }

    @Nullable
    public CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        return this.lifecycle.captureNowAndSave(session, player, cause);
    }

    @Nullable
    public CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull PlayerSession session, @NotNull Player player, @NotNull SaveCause cause) {
        return this.lifecycle.captureLaterAndSave(session, player, cause);
    }

    @Nullable
    public PlayerSession find(@NotNull UUID player) {
        return this.lifecycle.find(player);
    }

    public int size() {
        return this.lifecycle.size();
    }

    @NotNull
    public Map<UUID, String> onlinePlayers() {
        return this.lifecycle.onlinePlayers();
    }

    public void shutdown() {
        this.lifecycle.shutdown();
    }

    public boolean awaitReleases(long timeout, @NotNull TimeUnit unit) {
        return this.lifecycle.awaitReleases(timeout, unit);
    }
}