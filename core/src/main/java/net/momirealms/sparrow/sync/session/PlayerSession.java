package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataEntry;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class PlayerSession implements PlayerDataEntry {
    private static final String EARLY_PLAYER_DATA_LOAD = "PlayerDataStorage.load ran before player data preload completed";

    private final UUID uuid;
    private final String playerName;
    private final Connection connection; // 随会话保留到最终保存和解锁结束
    private final CompletableFuture<Void> released = new CompletableFuture<>(); // 会话从注册表移除后完成
    private SessionState state = SessionState.PREPARING;
    private LoginDataState loginDataState = LoginDataState.PRELOADING;
    private Map<DataKey, Tag> retainedData = Map.of(); // 本服不认识或已关闭的数据类型, 保存时原样写回快照
    private String lockToken; // 分布式锁的持有值, 释放时原样传回

    PlayerSession(@NotNull UUID uuid, @NotNull String playerName, @NotNull Connection connection) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.connection = connection;
    }

    @NotNull
    public UUID uuid() {
        return this.uuid;
    }

    @NotNull
    public String playerName() {
        return this.playerName;
    }

    @NotNull
    public Connection connection() {
        return this.connection;
    }

    @NotNull
    public CompletableFuture<Void> released() {
        return this.released;
    }

    @NotNull
    public synchronized SessionState state() {
        return this.state;
    }

    public synchronized boolean tryTransition(@NotNull SessionState expected, @NotNull SessionState target) {
        if (this.state != expected || !this.state.canTransitionTo(target)) return false;
        this.state = target;
        return true;
    }

    @ApiStatus.Internal
    public synchronized void transition(@NotNull SessionState target) {
        if (!this.state.canTransitionTo(target)) {
            throw new IllegalStateException("illegal session transition " + this.state + " -> " + target + " for " + this.playerName);
        }
        this.state = target;
    }

    @NotNull
    synchronized LoginDataState publishLoginData(
            @NotNull PlayerDataPreload playerData,
            @Nullable SnapshotLoadResult.Ready snapshot,
            long asyncReadNanos,
            long nativeApplyNanos
    ) {
        if (this.loginDataState == LoginDataState.PRELOADING) {
            Optional<CompoundTag> data = switch (playerData) {
                case PlayerDataPreload.Ready ready -> ready.data();
                case PlayerDataPreload.Fallback ignored -> Optional.empty();
            };
            this.loginDataState = new LoginDataState.Ready(data, 0, snapshot, asyncReadNanos, nativeApplyNanos);
        }
        return this.loginDataState;
    }

    @NotNull
    synchronized LoginDataState failLoginData(@NotNull String detail) {
        if (this.loginDataState == LoginDataState.PRELOADING) {
            this.loginDataState = new LoginDataState.Failed(detail);
        }
        return this.loginDataState;
    }

    @NotNull
    synchronized LoginDataState finishLoginData() {
        LoginDataState result = this.loginDataState;
        this.loginDataState = LoginDataState.CLEARED;
        return result;
    }

    @Override
    @NotNull
    public Optional<CompoundTag> loadPlayerData(@NotNull Supplier<Optional<CompoundTag>> original) {
        synchronized (this) {
            if (this.loginDataState instanceof LoginDataState.Ready ready) {
                this.loginDataState = new LoginDataState.Ready(ready.playerData(), ready.loads() + 1, ready.snapshot(), ready.asyncReadNanos(), ready.nativeApplyNanos());
                return ready.playerData();
            }
            if (this.loginDataState == LoginDataState.PRELOADING) {
                this.loginDataState = new LoginDataState.Failed(EARLY_PLAYER_DATA_LOAD);
            }
        }
        return original.get();
    }

    @NotNull
    @ApiStatus.Internal
    public synchronized Map<DataKey, Tag> retainedData() {
        return this.retainedData;
    }

    @ApiStatus.Internal
    public synchronized void retainedData(@NotNull Map<DataKey, Tag> retainedData) {
        this.retainedData = retainedData;
    }

    synchronized void lockToken(@NotNull String lockToken) {
        this.lockToken = lockToken;
    }

    @Nullable
    synchronized String lockToken() {
        return this.lockToken;
    }
}
