package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public enum PlayerJsonFile {
    STATISTICS(LevelResource.PLAYER_STATS_DIR),
    ADVANCEMENTS(LevelResource.PLAYER_ADVANCEMENTS_DIR);

    private final LevelResource directory;

    PlayerJsonFile(LevelResource directory) {
        this.directory = directory;
    }

    LevelResource directory() {
        return this.directory;
    }
}
