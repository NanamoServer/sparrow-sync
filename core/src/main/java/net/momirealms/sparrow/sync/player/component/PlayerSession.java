package net.momirealms.sparrow.sync.player.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataEntry;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class PlayerSession implements PlayerDataEntry {
    private final UUID uuid;
    private final String playerName;
    private final Connection connection; // 保留连接引用, 直到保存和解锁结束
    final PlayerLifecycle.Session state = new PlayerLifecycle.Session(); // 状态由 PlayerLifecycle 统一推进

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

    // 登录数据已全部应用且尚未开始退出, 此时才接受采集和在线恢复.
    public boolean isActive() {
        return (this.state.bits.get() & PlayerLifecycle.ACTIVE) != 0;
    }

    // 供状态命令和日志展示的阶段名.
    @NotNull
    public String getPhase() {
        int bits = this.state.bits.get();
        if ((bits & PlayerLifecycle.ACTIVE) != 0) return "ACTIVE";
        if ((bits & PlayerLifecycle.CLOSED) != 0) return (bits & PlayerLifecycle.WRITES_DONE) != 0 ? "CLOSED" : "SAVING";
        return (bits & PlayerLifecycle.APPLYING) != 0 ? "APPLYING" : "PREPARING";
    }

    @NotNull
    public SnapshotData retainedData() {
        return this.state.retainedData;
    }

    public void retainedData(@NotNull SnapshotData retainedData) {
        this.state.retainedData = retainedData;
    }

    @NotNull
    @Override
    public Optional<CompoundTag> loadPlayerData(@NotNull Supplier<Optional<CompoundTag>> original) {
        return this.state.loginData.loadPlayerData(original);
    }
}