package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.StringTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.handler.MapHandler;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

@ApiStatus.Internal
public final class MapPipeline {
    private static final String MAP_ID = "minecraft:map_id";
    private static final String CUSTOM_DATA = "minecraft:custom_data";
    private static final String NAMESPACE = "sparrow-sync";
    private static final String MAP_TYPE = "map-type";
    private static final String ORIGIN_SERVER = "origin-server";
    private static final String ORIGIN_ID = "origin-id";
    private static final String[] ITEM_LISTS = {"minecraft:bundle_contents", "minecraft:charged_projectiles"};

    private final DataRegistry registry;
    private final Map<MapType, MapHandler> handlers;
    private final SyncLogger logger;

    public MapPipeline(@NotNull DataRegistry registry, @NotNull List<? extends MapHandler> handlers, @NotNull SyncLogger logger) {
        this.registry = registry;
        this.logger = logger;
        Map<MapType, MapHandler> indexed = new EnumMap<>(MapType.class);
        for (int i = 0; i < handlers.size(); i++) {
            MapHandler handler = handlers.get(i);
            if (indexed.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalArgumentException("duplicate map handler: " + handler.type());
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    // 保存快照时编译本服原图, 已携带模式的地图沿用其来源服策略.
    @NotNull
    public Snapshot compile(@NotNull Snapshot snapshot, @NotNull MapType type, @NotNull String ownerId) {
        return this.rewrite(snapshot, components -> {
            try {
                return this.compileMap(components, type, ownerId);
            } catch (RuntimeException exception) {
                this.logger.warnWithFileCause(LogCategory.DATA, snapshot.meta().player(), null, exception, LogConstants.DATA_MAP_COMPILE_FAILED, snapshot.meta().player().toString(), snapshot.meta().id().toString(), String.valueOf(exception.getMessage()));
                return components;
            }
        });
    }

    // 解码按物品携带的模式分派, 本服配置仅决定之后新编译地图的模式.
    @NotNull
    public Snapshot decode(@NotNull Snapshot snapshot, @NotNull String ownerId) {
        return this.rewrite(snapshot, components -> {
            try {
                return this.decodeMap(components, ownerId);
            } catch (RuntimeException exception) {
                this.logger.warnWithFileCause(LogCategory.DATA, snapshot.meta().player(), null, exception, LogConstants.DATA_MAP_DECODE_FAILED, snapshot.meta().player().toString(), snapshot.meta().id().toString(), String.valueOf(exception.getMessage()));
                return components;
            }
        });
    }

    private CompoundTag compileMap(CompoundTag components, MapType type, String ownerId) {
        CompoundTag marker = this.marker(components);
        if (marker != null && marker.containsKey(MAP_TYPE)) return components;
        Tag mapId = components.get(MAP_ID);
        if (mapId == null) return components;
        if (!(mapId instanceof IntTag id)) {
            throw new IllegalArgumentException("map id is not an integer");
        }
        CompoundTag compiled = this.handler(type).compile(components, new MapOrigin(type, ownerId, id.getAsInt()));
        CompoundTag origin = marker == null ? NBT.createCompound() : new CompoundTag(new HashMap<>(marker.tags));
        origin.putString(MAP_TYPE, type.name());
        origin.putString(ORIGIN_SERVER, ownerId);
        origin.put(ORIGIN_ID, mapId);
        return this.writeMarker(compiled, origin);
    }

    private CompoundTag decodeMap(CompoundTag components, String ownerId) {
        CompoundTag marker = this.marker(components);
        if (marker == null || !marker.containsKey(MAP_TYPE)) return components;
        if (!(marker.get(MAP_TYPE) instanceof StringTag type)
                || !(marker.get(ORIGIN_SERVER) instanceof StringTag originServer)
                || originServer.getAsString().isBlank()
                || !(marker.get(ORIGIN_ID) instanceof IntTag originId)) {
            throw new IllegalArgumentException("invalid map origin metadata");
        }
        MapType mapType = MapType.valueOf(type.getAsString());
        MapOrigin origin = new MapOrigin(mapType, originServer.getAsString(), originId.getAsInt());
        CompoundTag decoded = this.handler(mapType).decode(components, origin, ownerId);
        // 只有实际恢复原始 ID 才清理标记, 回源缺图保留负数副本的身份.
        if (!ownerId.equals(origin.ownerId()) || !(decoded.get(MAP_ID) instanceof IntTag restoredId) || restoredId.getAsInt() != origin.id()) return decoded;
        CompoundTag remaining = new CompoundTag(new HashMap<>(marker.tags));
        remaining.remove(MAP_TYPE);
        remaining.remove(ORIGIN_SERVER);
        remaining.remove(ORIGIN_ID);
        return this.writeMarker(decoded, remaining);
    }

    @NotNull
    private MapHandler handler(MapType type) {
        MapHandler handler = this.handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("no map handler registered for " + type);
        }
        return handler;
    }

    @Nullable
    private CompoundTag marker(CompoundTag components) {
        Tag custom = components.get(CUSTOM_DATA);
        if (custom == null) return null;
        if (!(custom instanceof CompoundTag data)) {
            throw new IllegalArgumentException("map custom data is not a compound");
        }
        Tag marker = data.get(NAMESPACE);
        if (marker == null) return null;
        if (!(marker instanceof CompoundTag compound)) {
            throw new IllegalArgumentException("map sparrow-sync data is not a compound");
        }
        return compound;
    }

    // 来源元数据与同命名空间的其他业务字段共存, 清理后逐层移除空节点.
    private CompoundTag writeMarker(CompoundTag components, CompoundTag marker) {
        CompoundTag custom = components.get(CUSTOM_DATA) instanceof CompoundTag data
                ? new CompoundTag(new HashMap<>(data.tags)) : NBT.createCompound();
        if (marker.isEmpty()) {
            custom.remove(NAMESPACE);
        } else {
            custom.put(NAMESPACE, marker);
        }
        CompoundTag result = new CompoundTag(new HashMap<>(components.tags));
        if (custom.isEmpty()) {
            result.remove(CUSTOM_DATA);
        } else {
            result.put(CUSTOM_DATA, custom);
        }
        return result;
    }

    // 仅处理已启用的内置物品数据, 其他类型继续按原快照透传.
    private Snapshot rewrite(Snapshot snapshot, UnaryOperator<CompoundTag> operation) {
        Map<DataKey, Tag> changed = null;
        for (Map.Entry<DataKey, Tag> entry : snapshot.data().entrySet()) {
            DataKey key = entry.getKey();
            if (!this.registry.registered(key) || !(key.equals(InventoryDataType.INVENTORY) || key.equals(EnderChestDataType.ENDER_CHEST))) continue;
            if (!(entry.getValue() instanceof CompoundTag container)) continue;
            CompoundTag prepared = this.rewriteList(container, "items", false, operation);
            if (prepared == container) continue;
            if (changed == null) changed = new LinkedHashMap<>(snapshot.data());
            changed.put(key, prepared);
        }
        return changed == null ? snapshot : new Snapshot(snapshot.meta(), changed);
    }

    // 遍历原版承载物品的组件, custom_data 中的业务 NBT 保持原样.
    private CompoundTag rewriteItem(CompoundTag item, UnaryOperator<CompoundTag> operation) {
        if (!(item.get("components") instanceof CompoundTag components)) return item;
        CompoundTag changed = components;
        String id = item.getString("id");
        if (id.equals("minecraft:filled_map") || id.equals("filled_map")) {
            changed = operation.apply(changed);
        }
        changed = this.rewriteList(changed, "minecraft:container", true, operation);
        for (int i = 0; i < ITEM_LISTS.length; i++) {
            changed = this.rewriteList(changed, ITEM_LISTS[i], false, operation);
        }
        if (changed.get("minecraft:use_remainder") instanceof CompoundTag remainder) {
            CompoundTag prepared = this.rewriteItem(remainder, operation);
            if (prepared != remainder) {
                changed = new CompoundTag(new HashMap<>(changed.tags));
                changed.put("minecraft:use_remainder", prepared);
            }
        }
        if (changed == components) return item;
        CompoundTag result = new CompoundTag(new HashMap<>(item.tags));
        result.put("components", changed);
        return result;
    }

    // 快照 Tag 按不可变值共享, 仅复制真正发生变化的父节点和列表.
    private CompoundTag rewriteList(CompoundTag parent, String key, boolean slotted, UnaryOperator<CompoundTag> operation) {
        if (!(parent.get(key) instanceof ListTag items)) return parent;
        ListTag changed = null;
        int size = items.size();
        for (int i = 0; i < size; i++) {
            if (!(items.get(i) instanceof CompoundTag entry)) continue;
            CompoundTag item = entry;
            if (slotted) {
                if (!(entry.get("item") instanceof CompoundTag nested)) continue;
                item = nested;
            }
            CompoundTag prepared = this.rewriteItem(item, operation);
            if (prepared == item) continue;
            if (slotted) {
                CompoundTag wrapper = new CompoundTag(new HashMap<>(entry.tags));
                wrapper.put("item", prepared);
                prepared = wrapper;
            }
            if (changed == null) changed = new ListTag(new ArrayList<>(items));
            changed.set(i, prepared);
        }
        if (changed == null) return parent;
        CompoundTag result = new CompoundTag(new HashMap<>(parent.tags));
        result.put(key, changed);
        return result;
    }
}
