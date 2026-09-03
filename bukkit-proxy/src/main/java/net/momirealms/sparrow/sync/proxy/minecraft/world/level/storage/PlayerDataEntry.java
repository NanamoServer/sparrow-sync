package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Supplier;

@ApiStatus.Internal
public interface PlayerDataEntry {

    @NotNull
    Optional<CompoundTag> loadPlayerData(@NotNull Supplier<Optional<CompoundTag>> original);
}
