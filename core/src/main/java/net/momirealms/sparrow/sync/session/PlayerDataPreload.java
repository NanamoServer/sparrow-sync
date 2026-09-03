package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

sealed interface PlayerDataPreload {

    record Ready(@NotNull Optional<CompoundTag> data) implements PlayerDataPreload {
    }

    record Failed(@NotNull String detail) implements PlayerDataPreload {
    }
}
