package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

sealed interface LoginDataState {

    record Preloading() implements LoginDataState {
    }

    record Ready(
            @NotNull Optional<CompoundTag> playerData,
            int loads,
            @Nullable SnapshotLoadResult.Ready snapshot,
            long asyncReadNanos,
            long nativeApplyNanos
    ) implements LoginDataState {
    }

    record Failed(@NotNull String detail) implements LoginDataState {
    }

    record Cleared() implements LoginDataState {
    }
}
