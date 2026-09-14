package net.momirealms.sparrow.sync.map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.CompoundTagProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.saveddata.maps.MapItemSavedDataProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

// 转换原版地图与同步数据, 更新本服地图副本.
@ApiStatus.Internal
public final class NativeMapAdapter {
    private final HolderLookup.Provider registries;
    private final DynamicOps<Tag> ops; // 带有当前注册表的 NBT 编解码上下文
    private final int dataVersion;
    private final Codec<MapItemSavedData> codec = MapItemSavedDataProxy.INSTANCE.getCodec(); // 旧版本没有 Codec, 使用 load/save

    public NativeMapAdapter(@NotNull HolderLookup.Provider registries, int dataVersion) {
        this.registries = registries;
        this.ops = registries.createSerializationContext(NBTOps.INSTANCE);
        this.dataVersion = dataVersion;
    }

    // 优先采集缓存中的地图, 未加载时直接读文件, 避免在异步线程触发原版加载事件.
    @Nullable
    public MapData capture(@NotNull NativeMapStorage storage, int mapId) throws IOException {
        MapItemSavedData data = storage.cached(mapId);
        if (data != null) return this.capture(data);
        net.minecraft.nbt.CompoundTag root;
        try {
            root = storage.read(mapId);
        } catch (IOException exception) {
            // 读文件可能撞上原版保存; 若地图已加载到内存, 改为采集内存数据.
            data = storage.cached(mapId);
            if (data != null) return this.capture(data);
            throw exception;
        }
        data = storage.cached(mapId);
        if (data != null) return this.capture(data);
        if (root == null) return null;
        net.minecraft.nbt.Tag content = CompoundTagProxy.INSTANCE.getTags(root).get("data");
        if (!(content instanceof net.minecraft.nbt.CompoundTag)) {
            throw new IOException("map file contains no map data: " + mapId);
        }
        return new MapData(this.dataVersion, (CompoundTag) NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, content));
    }

    private MapData capture(MapItemSavedData data) {
        // 原版更新地图时不锁定 data. 集合可并发遍历, 但像素可能包含更新中的画面.
        Tag tag = this.codec == null
                ? NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, MapItemSavedDataProxy.INSTANCE.save(data, new net.minecraft.nbt.CompoundTag(), this.registries))
                : this.codec.encodeStart(this.ops, data).getOrThrow();
        return new MapData(this.dataVersion, (CompoundTag) tag);
    }

    // 构造独立的原版地图副本, 可在异步线程调用.
    @NotNull
    public MapItemSavedData prepareReplica(@NotNull MapIdentity identity, @NotNull MapData data) throws IOException {
        if (data.dataVersion() > this.dataVersion) {
            throw new IOException("map data version " + data.dataVersion() + " is newer than this server (" + this.dataVersion + ")");
        }
        CompoundTag tag = data.getTag();
        if (data.dataVersion() < this.dataVersion) {
            // 数据升级器需要外层 data 字段, 按原版地图文件格式包装.
            CompoundTag root = NBT.createCompound();
            root.put("data", tag);
            Tag fixed = DataFixers.getDataFixer().update(References.SAVED_DATA_MAP_DATA, new Dynamic<>(NBTOps.INSTANCE, root), data.dataVersion(), this.dataVersion).getValue();
            if (!(fixed instanceof CompoundTag compound) || !(compound.get("data") instanceof CompoundTag upgraded)) {
                throw new IOException("map data fixer returned no map content");
            }
            tag = upgraded;
        }
        // 使用副本专用维度, 展示框和 Bukkit 世界关联由本服重建.
        tag.putString("dimension", identity.replicaDimension());
        tag.remove("UUIDMost");
        tag.remove("UUIDLeast");
        tag.remove("frames");
        if (this.codec == null) {
            return MapItemSavedDataProxy.INSTANCE.load((net.minecraft.nbt.CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, tag), this.registries);
        }
        return this.codec.parse(this.ops, tag)
                .getOrThrow(message -> new IOException("failed to decode map: " + message));
    }

    // 安装准备好的地图副本; 副本已存在时, 在原对象上更新标识和内容.
    @NotNull
    public MapItemSavedData updateReplica(@NotNull ServerLevel level, @NotNull MapIdentity identity, @NotNull MapItemSavedData prepared) {
        MapId id = new MapId(identity.globalId());
        MapItemSavedData existing = level.getMapData(id);
        if (existing == null) {
            level.setMapData(id, prepared);
            return prepared;
        }
        this.updateReplica(existing, prepared);
        return existing;
    }

    private void updateReplica(MapItemSavedData target, MapItemSavedData prepared) {
        synchronized (target) {
            boolean headerChanged = target.scale != prepared.scale || target.locked != prepared.locked;
            boolean metadataChanged = headerChanged || !target.dimension.equals(prepared.dimension)
                    || !Objects.equals(target.uniqueId, prepared.uniqueId) || target.centerX != prepared.centerX || target.centerZ != prepared.centerZ
                    || target.trackingPosition != prepared.trackingPosition || target.unlimitedTracking != prepared.unlimitedTracking;
            target.dimension = prepared.dimension;
            target.uniqueId = prepared.uniqueId;
            target.centerX = prepared.centerX;
            target.centerZ = prepared.centerZ;
            target.scale = prepared.scale;
            target.locked = prepared.locked;
            target.trackingPosition = prepared.trackingPosition;
            target.unlimitedTracking = prepared.unlimitedTracking;
            MapItemSavedDataProxy proxy = MapItemSavedDataProxy.INSTANCE;
            // 只修改变化的像素, 保留原有 MapView 和渲染缓冲.
            int firstDifference = Arrays.mismatch(target.colors, prepared.colors);
            if (firstDifference >= 0) {
                int minX = 127;
                int maxX = 0;
                int minY = firstDifference >> 7;
                int maxY = minY;
                for (int i = firstDifference; i < MapData.PIXEL_COUNT; i++) {
                    if (target.colors[i] == prepared.colors[i]) continue;
                    target.colors[i] = prepared.colors[i];
                    int x = i & 127;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    maxY = i >> 7;
                }
                // 原版会合并尚未发送的像素范围, 并标记地图待保存.
                proxy.setColorsDirty(target, minX, minY);
                proxy.setColorsDirty(target, maxX, maxY);
            }
            // 更新来源地图的旗帜, 保留本服玩家和展示框的其他标记.
            Map<String, MapBanner> banners = proxy.getBannerMarkers(target);
            Map<String, MapBanner> incomingBanners = proxy.getBannerMarkers(prepared);
            boolean bannersChanged = !banners.equals(incomingBanners);
            boolean decorationsChanged = false;
            for (String key : banners.keySet()) {
                if (!prepared.decorations.containsKey(key) && target.decorations.remove(key) != null) {
                    decorationsChanged = true;
                }
            }
            if (bannersChanged) {
                banners.clear();
                banners.putAll(incomingBanners);
            }
            for (Map.Entry<String, MapDecoration> entry : prepared.decorations.entrySet()) {
                if (!entry.getValue().equals(target.decorations.get(entry.getKey()))) {
                    target.decorations.put(entry.getKey(), entry.getValue());
                    decorationsChanged = true;
                }
            }
            if (decorationsChanged) {
                int tracked = 0;
                for (MapDecoration decoration : target.decorations.values()) {
                    if (decoration.type().value().trackCount()) tracked++;
                }
                proxy.setTrackedDecorationCount(target, tracked);
            }
            // 像素不变时也要保存元数据和旗帜; 缩放与锁定状态随装饰更新发送.
            if (metadataChanged || bannersChanged) target.setDirty();
            if (headerChanged || decorationsChanged) proxy.setDecorationsDirty(target);
        }
    }
}
