package net.momirealms.sparrow.sync.player.data;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public sealed interface PlayerDataPreload {

    record Ready(@NotNull Optional<CompoundTag> data) implements PlayerDataPreload {
    }

    Fallback FALLBACK = new Fallback();

    record Fallback() implements PlayerDataPreload {
    }
}