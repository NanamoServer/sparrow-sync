package net.momirealms.sparrow.sync.map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.saveddata.maps.MapItemSavedDataProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;

@ApiStatus.Internal
public final class NativeMapAdapter {
    private final HolderLookup.Provider registries;
    private final DynamicOps<Tag> ops;
    private final int dataVersion;
    private final Codec<MapItemSavedData> codec = MapItemSavedDataProxy.INSTANCE.getCodec();

    public NativeMapAdapter(@NotNull HolderLookup.Provider registries, int dataVersion) {
        this.registries = registries;
        this.ops = registries.createSerializationContext(NBTOps.INSTANCE);
        this.dataVersion = dataVersion;
    }

    /** 读取原生地图. <strong>调用方须处于允许原生地图访问的线程, 同一 ID 的安装操作须串行</strong>. */
    @Nullable
    public MapData capture(@NotNull ServerLevel level, int mapId) {
        MapItemSavedData data = level.getMapData(new MapId(mapId));
        return data == null ? null : this.capture(data);
    }

    // 从已落盘副本的隔离维度恢复身份, 不因一个负数 ID 把其他插件的地图纳入同步.
    @Nullable
    public MapIdentity replicaIdentity(@NotNull ServerLevel level, int mapId) {
        MapItemSavedData data = level.getMapData(new MapId(mapId));
        if (data == null) return null;
        MapIdentity identity = MapIdentity.fromReplicaDimension(Level.RESOURCE_KEY_CODEC.encodeStart(NBTOps.INSTANCE, data.dimension).getOrThrow().getAsString());
        return identity != null && identity.globalId() == mapId ? identity : null;
    }

    /** 复制持久内容, 返回值可交给异步存储. <strong>Paper 在主线程调用, Folia 遵守地图访问线程约束</strong>. */
    @NotNull
    public MapData capture(@NotNull MapItemSavedData data) {
        // Folia 原生修改使用同一地图监视器, 像素与标记在锁内复制为独立数据.
        synchronized (data) {
            Tag tag = this.codec == null
                    ? NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, MapItemSavedDataProxy.INSTANCE.save(data, new net.minecraft.nbt.CompoundTag(), this.registries))
                    : this.codec.encodeStart(this.ops, data).getOrThrow();
            return new MapData(this.dataVersion, (CompoundTag) tag);
        }
    }

    /** 构造独立副本, 保留源图内容并隔离本服地形. */
    @NotNull
    public MapItemSavedData prepareReplica(@NotNull MapIdentity identity, @NotNull MapData data) throws IOException {
        if (data.dataVersion() > this.dataVersion) {
            throw new IOException("map data version " + data.dataVersion() + " is newer than this server (" + this.dataVersion + ")");
        }
        CompoundTag tag = data.getTag();
        if (data.dataVersion() < this.dataVersion) {
            CompoundTag root = NBT.createCompound();
            root.put("data", tag);
            Tag fixed = DataFixers.getDataFixer().update(References.SAVED_DATA_MAP_DATA, new Dynamic<>(NBTOps.INSTANCE, root), data.dataVersion(), this.dataVersion).getValue();
            if (!(fixed instanceof CompoundTag compound) || !(compound.get("data") instanceof CompoundTag upgraded)) {
                throw new IOException("map data fixer returned no map content");
            }
            tag = upgraded;
        }
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

    /** 注册或更新已准备的原生副本. <strong>原生访问线程调用; 同 ID 串行, prepared 的所有权转交原生存储</strong>. */
    @NotNull
    public MapItemSavedData installReplica(@NotNull ServerLevel level, @NotNull MapIdentity identity, @NotNull MapItemSavedData prepared) {
        String dimension = Level.RESOURCE_KEY_CODEC.encodeStart(NBTOps.INSTANCE, prepared.dimension).getOrThrow().getAsString();
        if (!identity.replicaDimension().equals(dimension)) {
            throw new IllegalArgumentException("prepared map does not belong to this replica identity");
        }
        MapId id = new MapId(identity.globalId());
        MapItemSavedData existing = level.getMapData(id);
        if (existing == null) {
            level.setMapData(id, prepared);
            return prepared;
        }
        if (!existing.dimension.equals(prepared.dimension)) {
            throw new IllegalStateException("global map id " + identity.globalId() + " is occupied by another native map");
        }
        this.updateReplica(existing, prepared);
        return existing;
    }

    private void updateReplica(MapItemSavedData target, MapItemSavedData prepared) {
        synchronized (target) {
            target.centerX = prepared.centerX;
            target.centerZ = prepared.centerZ;
            target.scale = prepared.scale;
            target.locked = prepared.locked;
            target.trackingPosition = prepared.trackingPosition;
            target.unlimitedTracking = prepared.unlimitedTracking;
            // 保留 MapView、渲染缓冲与持有者身份, 原生保存和发包继续观察同一对象.
            System.arraycopy(prepared.colors, 0, target.colors, 0, MapData.PIXEL_COUNT);
            MapItemSavedDataProxy proxy = MapItemSavedDataProxy.INSTANCE;
            Map<String, MapBanner> banners = proxy.getBannerMarkers(target);
            for (String key : banners.keySet()) {
                target.decorations.remove(key);
            }
            banners.clear();
            banners.putAll(proxy.getBannerMarkers(prepared));
            target.decorations.putAll(prepared.decorations);
            int tracked = 0;
            for (MapDecoration decoration : target.decorations.values()) {
                if (decoration.type().value().trackCount()) {
                    tracked++;
                }
            }
            proxy.setTrackedDecorationCount(target, tracked);
            proxy.setColorsDirty(target, 0, 0);
            proxy.setColorsDirty(target, 127, 127);
            proxy.setDecorationsDirty(target);
        }
    }
}
