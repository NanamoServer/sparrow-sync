package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerDataStoragePatch1_21_4 extends PlayerDataStoragePatch {

    public PlayerDataStoragePatch1_21_4(LevelStorageSource.LevelStorageAccess levelAccess, DataFixer fixerUpper, PlayerDataStorage original, Map<UUID, ? extends PlayerDataEntry> sessions) {
        super(levelAccess, fixerUpper, original, sessions);
    }

    public Optional<CompoundTag> load(String name, String uuid) {
        return this.loadPlayerData(UUID.fromString(uuid), () -> this.loadOriginal(name, uuid));
    }

    @Override
    @NotNull
    public Optional<CompoundTag> loadOriginal(@NotNull UUID player, @NotNull String playerName) {
        return this.loadOriginal(playerName, player.toString());
    }

    @SuppressWarnings("unchecked")
    private Optional<CompoundTag> loadOriginal(String name, String uuid) {
        return (Optional<CompoundTag>) PlayerDataStorageProxy.INSTANCE.load$0(this.original, name, uuid);
    }
}
