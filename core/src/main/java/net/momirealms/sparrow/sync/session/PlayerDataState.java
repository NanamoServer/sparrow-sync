package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

sealed interface PlayerDataState {

    record Preloading() implements PlayerDataState {
    }

    record Ready(@NotNull Optional<CompoundTag> data, int loads) implements PlayerDataState {
    }

    record Failed(@NotNull String detail) implements PlayerDataState {
    }

    record Cleared() implements PlayerDataState {
    }
}
