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
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.saveddata.maps.MapItemSavedDataProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;

// 在原生地图对象与可跨服保存的内容之间转换, 并维护本服负数副本.
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

    // 按原生 ID 复制世界中现存的地图内容.
    @Nullable
    public MapData capture(@NotNull ServerLevel level, int mapId) {
        MapItemSavedData data = level.getMapData(new MapId(mapId));
        if (data == null) return null;
        synchronized (data) {
            Tag tag = this.codec == null
                    ? NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, MapItemSavedDataProxy.INSTANCE.save(data, new net.minecraft.nbt.CompoundTag(), this.registries))
                    : this.codec.encodeStart(this.ops, data).getOrThrow();
            return new MapData(this.dataVersion, (CompoundTag) tag);
        }
    }

    // 从已保存副本的隔离维度恢复完整身份.
    @Nullable
    public MapIdentity replicaIdentity(@NotNull ServerLevel level, int mapId) {
        MapItemSavedData data = level.getMapData(new MapId(mapId));
        if (data == null) return null;
        MapIdentity identity = MapIdentity.fromReplicaDimension(Level.RESOURCE_KEY_CODEC.encodeStart(NBTOps.INSTANCE, data.dimension).getOrThrow().getAsString());
        return identity != null && identity.globalId() == mapId ? identity : null;
    }

    // 将来源内容准备为独立的原生副本, 此阶段可由异步工作线程调用.
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
            target.dimension = prepared.dimension;
            target.uniqueId = prepared.uniqueId;
            target.centerX = prepared.centerX;
            target.centerZ = prepared.centerZ;
            target.scale = prepared.scale;
            target.locked = prepared.locked;
            target.trackingPosition = prepared.trackingPosition;
            target.unlimitedTracking = prepared.unlimitedTracking;
            // 保留 MapView、渲染缓冲与持有者身份, 原生保存和发包继续观察同一对象.
            System.arraycopy(prepared.colors, 0, target.colors, 0, MapData.PIXEL_COUNT);
            MapItemSavedDataProxy proxy = MapItemSavedDataProxy.INSTANCE;
            // 来源旗帜用新内容替换, 本服玩家和展示框装饰继续留在目标对象中
            Map<String, MapBanner> banners = proxy.getBannerMarkers(target);
            for (String key : banners.keySet()) {
                target.decorations.remove(key);
            }
            banners.clear();
            banners.putAll(proxy.getBannerMarkers(prepared));
            target.decorations.putAll(prepared.decorations);
            // 装饰合并后按原版规则重算计数, 供标记数量限制继续使用
            int tracked = 0;
            for (MapDecoration decoration : target.decorations.values()) {
                if (decoration.type().value().trackCount()) {
                    tracked++;
                }
            }
            proxy.setTrackedDecorationCount(target, tracked);
            // 标记像素矩形两个端点及装饰变更, 所有原生查看者随后生成各自更新包
            proxy.setColorsDirty(target, 0, 0);
            proxy.setColorsDirty(target, 127, 127);
            proxy.setDecorationsDirty(target);
        }
    }
}
