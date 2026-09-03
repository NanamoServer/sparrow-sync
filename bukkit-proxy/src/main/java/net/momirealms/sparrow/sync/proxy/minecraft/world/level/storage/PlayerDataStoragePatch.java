package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@ApiStatus.Internal
public abstract class PlayerDataStoragePatch extends PlayerDataStorage {
    protected final PlayerDataStorage original;
    private final Map<UUID, ? extends PlayerDataEntry> sessions;

    protected PlayerDataStoragePatch(LevelStorageSource.LevelStorageAccess levelAccess, DataFixer fixerUpper, PlayerDataStorage original, Map<UUID, ? extends PlayerDataEntry> sessions) {
        super(levelAccess, fixerUpper);
        this.original = original;
        this.sessions = sessions;
    }

    @NotNull
    protected final Optional<CompoundTag> loadPlayerData(@NotNull UUID player, @NotNull Supplier<Optional<CompoundTag>> original) {
        PlayerDataEntry entry = this.sessions.get(player);
        return entry == null ? original.get() : entry.loadPlayerData(original);
    }

    @NotNull
    public abstract Optional<CompoundTag> loadOriginal(@NotNull UUID player, @NotNull String playerName);
}
