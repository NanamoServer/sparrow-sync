package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerDataStoragePatch1_21_9 extends PlayerDataStoragePatch {

    public PlayerDataStoragePatch1_21_9(LevelStorageSource.LevelStorageAccess levelAccess, DataFixer fixerUpper, PlayerDataStorage original, Map<UUID, ? extends PlayerDataEntry> sessions) {
        super(levelAccess, fixerUpper, original, sessions);
    }

    @Override
    public Optional<CompoundTag> load(NameAndId nameAndId) {
        return this.loadPlayerData(nameAndId.id(), () -> this.loadOriginal(nameAndId));
    }

    @Override
    @NotNull
    public Optional<CompoundTag> loadOriginal(@NotNull UUID player, @NotNull String playerName) {
        return this.loadOriginal(new NameAndId(player, playerName));
    }

    @SuppressWarnings("unchecked")
    private Optional<CompoundTag> loadOriginal(NameAndId nameAndId) {
        return (Optional<CompoundTag>) PlayerDataStorageProxy.INSTANCE.load$2(this.original, nameAndId);
    }
}
