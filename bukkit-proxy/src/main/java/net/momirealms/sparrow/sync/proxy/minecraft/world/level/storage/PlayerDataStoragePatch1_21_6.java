package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;

import java.util.Optional;

public final class PlayerDataStoragePatch1_21_6 extends PlayerDataStorage {
    private final PlayerDataStorage original;

    public PlayerDataStoragePatch1_21_6(LevelStorageSource.LevelStorageAccess levelAccess, DataFixer fixerUpper, PlayerDataStorage original) {
        super(levelAccess, fixerUpper);
        this.original = original;
    }

    @SuppressWarnings("unchecked")
    public Optional<CompoundTag> load(String name, String uuid, ProblemReporter reporter) {
        return (Optional<CompoundTag>) PlayerDataStorageProxy.INSTANCE.load$1(this.original, name, uuid, reporter);
    }
}
