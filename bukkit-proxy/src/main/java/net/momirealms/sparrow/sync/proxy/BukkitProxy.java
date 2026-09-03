package net.momirealms.sparrow.sync.proxy;

import com.mojang.datafixers.DataFixer;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.momirealms.sparrow.reflection.SReflection;
import net.momirealms.sparrow.reflection.remapper.Remapper;
import net.momirealms.sparrow.sync.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.players.PlayerListProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataEntry;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataStoragePatch;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataStoragePatch1_21_4;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataStoragePatch1_21_6;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerDataStoragePatch1_21_9;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BukkitProxy {
    private static final int MIN_PLAYER_DATA_STORAGE_VERSION = MinecraftPredicate.parseVersionToInteger("1.21.4");
    private static final int PLAYER_DATA_STORAGE_VERSION_1_21_6 = MinecraftPredicate.parseVersionToInteger("1.21.6");
    private static final int PLAYER_DATA_STORAGE_VERSION_1_21_9 = MinecraftPredicate.parseVersionToInteger("1.21.9");
    private static final int MAX_PLAYER_DATA_STORAGE_VERSION = MinecraftPredicate.parseVersionToInteger("26.2");
    private static boolean init;

    private BukkitProxy() {}

    /**
     * 初始化代理类.
     */
    public static void init(String version, List<String> patches) {
        if (!init) {
            SReflection.setAsmClassPrefix("sparrow_sync");
            SReflection.setActivePredicate(new MinecraftPredicate(version, patches));
            Remapper remapper = Remapper.createFromPaperJar();
            if (remapper != Remapper.noOp()) {
                SReflection.setRemapper(CraftBukkitRemapper.create(remapper));
            }
            init = true;
        }
    }

    /** 替换原版 PlayerDataStorage, 并返回可读取 original 数据的版本适配器. */
    @NotNull
    public static PlayerDataStoragePatch injectPlayerDataStorage(@NotNull String version, @NotNull Map<UUID, ? extends PlayerDataEntry> sessions) {
        MinecraftServerProxy serverProxy = MinecraftServerProxy.INSTANCE;
        Object server = serverProxy.getServer();
        Object playerList = serverProxy.getPlayerList(server);
        // 获取当前服务器的 PlayerDataStorage
        PlayerDataStorage original = (PlayerDataStorage) PlayerListProxy.INSTANCE.getPlayerIo(playerList);
        LevelStorageSource.LevelStorageAccess levelAccess = (LevelStorageSource.LevelStorageAccess) serverProxy.getStorageSource(server);
        DataFixer fixerUpper = serverProxy.getFixerUpper(server);
        // 构造并注入字段
        PlayerDataStoragePatch patch = createPlayerDataStoragePatch(version, levelAccess, fixerUpper, original, sessions);
        PlayerListProxy.INSTANCE.setPlayerIo(playerList, patch);
        return patch;
    }

    /**
     * 构造代理的 PlayerDataStorage.
     */
    private static PlayerDataStoragePatch createPlayerDataStoragePatch(
            String versionString,
            LevelStorageSource.LevelStorageAccess levelAccess,
            DataFixer fixerUpper,
            PlayerDataStorage original,
            Map<UUID, ? extends PlayerDataEntry> sessions
    ) {
        int version = MinecraftPredicate.parseVersionToInteger(versionString);
        if (version < MIN_PLAYER_DATA_STORAGE_VERSION || version > MAX_PLAYER_DATA_STORAGE_VERSION) {
            throw new IllegalArgumentException("Unsupported PlayerDataStorage version: " + versionString);
        }
        if (version >= PLAYER_DATA_STORAGE_VERSION_1_21_9) return new PlayerDataStoragePatch1_21_9(levelAccess, fixerUpper, original, sessions);
        if (version >= PLAYER_DATA_STORAGE_VERSION_1_21_6) return new PlayerDataStoragePatch1_21_6(levelAccess, fixerUpper, original, sessions);
        return new PlayerDataStoragePatch1_21_4(levelAccess, fixerUpper, original, sessions);
    }
}
