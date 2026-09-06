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

// 在NMS地图对象与可跨服保存的内容之间转换, 并维护本服负数副本.
@ApiStatus.Internal
public final class NativeMapAdapter {
    private final HolderLookup.Provider registries;
    private final DynamicOps<Tag> ops; // 携带当前注册表的插件 NBT 编解码上下文
    private final int dataVersion;
    private final Codec<MapItemSavedData> codec = MapItemSavedDataProxy.INSTANCE.getCodec(); // 版本代理提供的地图 Codec; 旧分支返回 null 并使用 load/save

    public NativeMapAdapter(@NotNull HolderLookup.Provider registries, int dataVersion) {
        this.registries = registries;
        this.ops = registries.createSerializationContext(NBTOps.INSTANCE);
        this.dataVersion = dataVersion;
    }

    // 只读已准备的缓存; 未加载的嵌套地图直接读文件, 不异步触发原生加载事件.
    @Nullable
    public MapData capture(@NotNull NativeMapStorage storage, int mapId) throws IOException {
        MapItemSavedData data = storage.cached(mapId);
        if (data != null) return this.capture(data);
        net.minecraft.nbt.CompoundTag root;
        try {
            root = storage.read(mapId);
        } catch (IOException exception) {
            // 文件读取可能与原生加载后的保存重叠. 已出现内存原图时优先采集它, 不重试文件.
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
        // 原生写入不获取 data 的监视器. 并发集合保证可遍历, 像素允许采到一次更新中的画面.
        Tag tag = this.codec == null
                ? NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, MapItemSavedDataProxy.INSTANCE.save(data, new net.minecraft.nbt.CompoundTag(), this.registries))
                : this.codec.encodeStart(this.ops, data).getOrThrow();
        return new MapData(this.dataVersion, (CompoundTag) tag);
    }

    // 将来源内容准备为独立的原生副本, 此阶段可由异步线程调用.
    @NotNull
    public MapItemSavedData prepareReplica(@NotNull MapIdentity identity, @NotNull MapData data) throws IOException {
        if (data.dataVersion() > this.dataVersion) {
            throw new IOException("map data version " + data.dataVersion() + " is newer than this server (" + this.dataVersion + ")");
        }
        CompoundTag tag = data.getTag();
        if (data.dataVersion() < this.dataVersion) {
            // SAVED_DATA_MAP_DATA 的升级入口读取外层 data 字段, 这里补齐原生文件结构
            CompoundTag root = NBT.createCompound();
            root.put("data", tag);
            Tag fixed = DataFixers.getDataFixer().update(References.SAVED_DATA_MAP_DATA, new Dynamic<>(NBTOps.INSTANCE, root), data.dataVersion(), this.dataVersion).getValue();
            if (!(fixed instanceof CompoundTag compound) || !(compound.get("data") instanceof CompoundTag upgraded)) {
                throw new IOException("map data fixer returned no map content");
            }
            tag = upgraded;
        }
        // 持久画面归属隔离维度, 展示框和 Bukkit 世界绑定随后由本服建立
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

    // 将经 prepareReplica 得到的共享内容写入负数副本, 已有对象的身份与内容一起更新.
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
            // 相同画面只比较数组; 变化像素原地写入, 保留 MapView 和渲染缓冲的身份.
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
                // 原生持有者会将本次矩形与尚未发送的变化合并, 文件脏标记也由此设置.
                proxy.setColorsDirty(target, minX, minY);
                proxy.setColorsDirty(target, maxX, maxY);
            }
            // 来源旗帜负责自己的装饰键, 本服玩家与展示框的其他装饰继续保留.
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
            // 元数据和旗帜可在像素不变时修改, 仍须保存; 包头随原生装饰更新一并发送.
            if (metadataChanged || bannersChanged) target.setDirty();
            if (headerChanged || decorationsChanged) proxy.setDecorationsDirty(target);
        }
    }
}
