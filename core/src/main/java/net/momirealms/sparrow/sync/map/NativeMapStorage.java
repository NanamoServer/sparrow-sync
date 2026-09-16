package net.momirealms.sparrow.sync.map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.level.ServerLevelProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.saveddata.maps.MapItemSavedDataProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.MapDataCache;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.SavedDataStorageProxy;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

public final class NativeMapStorage {
    private final Object storage;
    private final MapDataCache cache;
    private final int dataVersion;

    // 替换原版地图缓存, 允许异步采集时遍历.
    public NativeMapStorage(@NotNull MinecraftServer server, int dataVersion) {
        this.storage = VersionHelper.isOrAbove26_1()
                ? MinecraftServerProxy.INSTANCE.getDataStorage(server)
                : ServerLevelProxy.INSTANCE.getDataStorage(server.overworld());
        SavedDataStorageProxy proxy = SavedDataStorageProxy.INSTANCE;
        this.cache = new MapDataCache(proxy.getCache(this.storage));
        this.dataVersion = dataVersion;
        proxy.setCache(this.storage, this.cache);
    }

    @Nullable
    public MapItemSavedData cached(int mapId) {
        MapId id = new MapId(mapId);
        // 1.21.5 起缓存按 SavedDataType 建索引, 更早的版本用存档文件名
        Object key = VersionHelper.isOrAbove1_21_5() ? MapItemSavedDataProxy.INSTANCE.type(id) : id.key();
        Optional<?> value = this.cache.get(key);
        return value != null && value.orElse(null) instanceof MapItemSavedData data ? data : null;
    }

    /** 按原版规则解压并升级地图文件, 返回独立 NBT, 不写入原版缓存. 文件不存在时返回 null. */
    @Nullable
    public CompoundTag read(int mapId) throws IOException {
        SavedDataStorageProxy proxy = SavedDataStorageProxy.INSTANCE;
        String name = new MapId(mapId).key();
        try {
            if (VersionHelper.isOrAbove26_1()) {
                Object id = IdentifierProxy.INSTANCE.newInstance("minecraft", name);
                Path path = proxy.getDataFile(this.storage, id);
                return proxy.readTagFromDisk$1(this.storage, path, DataFixTypes.SAVED_DATA_MAP_DATA, this.dataVersion);
            }
            return proxy.readTagFromDisk$0(this.storage, name, DataFixTypes.SAVED_DATA_MAP_DATA, this.dataVersion);
        } catch (NoSuchFileException exception) {
            return null;
        }
    }
}
