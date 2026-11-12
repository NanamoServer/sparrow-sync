package net.momirealms.sparrow.sync.player.component;

import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.sync.player.data.LoginDataState;
import net.momirealms.sparrow.sync.player.data.PlayerDataPreload;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataEntry;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class PlayerLoginData implements PlayerDataEntry {
    private static final String EARLY_PLAYER_DATA_LOAD = "PlayerDataStorage.load ran before player data preload completed";
    private LoginDataState state = LoginDataState.PRELOADING;

    // 原版读取前发布准备结果, 早读失败或已结束的连接保留原状态.
    @NotNull
    public synchronized LoginDataState publish(
            @NotNull PlayerDataPreload playerData,
            @Nullable SnapshotLoadResult.Ready snapshot,
            long asyncReadNanos,
            long nativeApplyNanos
    ) {
        if (this.state == LoginDataState.PRELOADING) {
            Optional<CompoundTag> data = switch (playerData) {
                case PlayerDataPreload.Ready ready -> ready.data();
                case PlayerDataPreload.Fallback ignored -> Optional.empty();
            };
            this.state = new LoginDataState.Ready(data, 0, snapshot, asyncReadNanos, nativeApplyNanos);
        }
        return this.state;
    }

    // 准备失败记入原版读取入口, 后续结果不能覆盖它.
    @NotNull
    public synchronized LoginDataState fail(@NotNull String detail) {
        if (this.state == LoginDataState.PRELOADING) {
            this.state = new LoginDataState.Failed(detail);
        }
        return this.state;
    }

    // Join 接管准备结果, 或连接结束时释放仍被持有的数据.
    @NotNull
    public synchronized LoginDataState clear() {
        LoginDataState result = this.state;
        this.state = LoginDataState.CLEARED;
        return result;
    }

    // 统计原版实际读取次数, 未完成预加载的早读会使本次登录失败.
    @Override
    @NotNull
    public Optional<CompoundTag> loadPlayerData(@NotNull Supplier<Optional<CompoundTag>> original) {
        synchronized (this) {
            if (this.state instanceof LoginDataState.Ready ready) {
                this.state = new LoginDataState.Ready(ready.playerData(), ready.loads() + 1, ready.snapshot(), ready.asyncReadNanos(), ready.nativeApplyNanos());
                return ready.playerData();
            }
            if (this.state == LoginDataState.PRELOADING) {
                this.state = new LoginDataState.Failed(EARLY_PLAYER_DATA_LOAD);
            }
        }
        return original.get();
    }
}