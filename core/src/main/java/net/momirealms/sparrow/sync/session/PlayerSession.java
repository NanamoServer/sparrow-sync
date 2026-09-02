package net.momirealms.sparrow.sync.session;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerSession {
    private final UUID uuid;
    private final String playerName;
    private final CompletableFuture<Void> released = new CompletableFuture<>(); // 当会话从注册表移除后用于注册回调的
    private SessionState state = SessionState.PREPARING;
    private SnapshotLoadResult.Ready loadedSnapshot;
    private Map<DataKey, Tag> retainedData = Map.of();  // 本服不认识或已关闭的数据类型, 玩家快照保存时原样写回快照
    private String lockToken;   // 分布式锁的持有值, 释放时原样传回

    PlayerSession(@NotNull UUID uuid, @NotNull String playerName) {
        this.uuid = uuid;
        this.playerName = playerName;
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
    public CompletableFuture<Void> released() {
        return this.released;
    }

    @NotNull
    public synchronized SessionState state() {
        return this.state;
    }

    synchronized boolean tryTransition(@NotNull SessionState expected, @NotNull SessionState target) {
        if (this.state != expected || !this.state.canTransitionTo(target)) return false;
        this.state = target;
        return true;
    }

    synchronized void transition(@NotNull SessionState target) {
        if (!this.state.canTransitionTo(target)) {
            throw new IllegalStateException("illegal session transition " + this.state + " -> " + target + " for " + this.playerName);
        }
        this.state = target;
    }

    synchronized void loadedSnapshot(@NotNull SnapshotLoadResult.Ready loadedSnapshot) {
        this.loadedSnapshot = loadedSnapshot;
    }

    @Nullable
    synchronized SnapshotLoadResult.Ready takeLoadedSnapshot() {
        SnapshotLoadResult.Ready loaded = this.loadedSnapshot;
        this.loadedSnapshot = null;
        return loaded;
    }

    @NotNull
    synchronized Map<DataKey, Tag> retainedData() {
        return this.retainedData;
    }

    synchronized void retainedData(@NotNull Map<DataKey, Tag> retainedData) {
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
