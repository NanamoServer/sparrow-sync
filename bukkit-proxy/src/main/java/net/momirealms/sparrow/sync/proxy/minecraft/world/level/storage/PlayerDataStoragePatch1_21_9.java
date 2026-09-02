package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;

import java.util.Optional;

public final class PlayerDataStoragePatch1_21_9 extends PlayerDataStorage {
    private final PlayerDataStorage original;

    public PlayerDataStoragePatch1_21_9(LevelStorageSource.LevelStorageAccess levelAccess, DataFixer fixerUpper, PlayerDataStorage original) {
        super(levelAccess, fixerUpper);
        this.original = original;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Optional<CompoundTag> load(NameAndId nameAndId) {
        return (Optional<CompoundTag>) PlayerDataStorageProxy.INSTANCE.load$2(this.original, nameAndId);
    }
}
