package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.StringTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.data.MapOrigin;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.handler.MapHandler;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;
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
    private static final DataKey[] ITEM_DATA_KEYS = {InventoryDataType.INVENTORY, EnderChestDataType.ENDER_CHEST};

    private final DataRegistry registry;
    private final Map<MapType, MapHandler> handlers;
    private final SyncLogger logger;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

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

    // 按同步模式处理地图物品, 完成后生成传输快照.
    @NotNull
    public CompletableFuture<Snapshot> encodeAsync(@NotNull Snapshot snapshot, @NotNull MapType type, @NotNull String ownerId, @NotNull IntFunction<CompletableFuture<StoredMap>> publish) {
        Map<Integer, CompletableFuture<StoredMap>> publications = new HashMap<>();
        // 同次保存中, 每张来源地图只采集发布一次; 失败时各物品分别保留原内容.
        IntFunction<CompletableFuture<StoredMap>> publishOnce = id -> publications.computeIfAbsent(id, nativeId -> {
            try {
                return publish.apply(nativeId);
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        });
        Map<Integer, CompletableFuture<Boolean>> renewals = new HashMap<>();
        return this.rewriteAsync(snapshot, components -> {
            // 中转地图沿用已有的同步模式.
            CompoundTag marker = this.marker(components);
            if (marker != null && marker.containsKey(MAP_TYPE)) {
                MapOrigin origin = this.origin(marker);
                return this.handler(origin.type()).forwardAsync(components, origin, renewals);
            }
            Tag mapId = components.get(MAP_ID);
            if (mapId == null) return CompletableFuture.completedFuture(components);
            return this.handler(type)
                    .compileAsync(components, new MapOrigin(type, ownerId, ((IntTag) mapId).getAsInt()), publishOnce)
                    .thenApply(compiled -> {
                        // 处理成功后写入来源标记, 保留同一命名空间中的其他字段.
                        CompoundTag origin = marker == null ? NBT.createCompound() : new CompoundTag(new HashMap<>(marker.tags));
                        origin.putString(MAP_TYPE, type.name());
                        origin.putString(ORIGIN_SERVER, ownerId);
                        origin.put(ORIGIN_ID, mapId);
                        return this.writeMarker(compiled, origin);
                    });
        }, LogConstants.DATA_MAP_COMPILE_FAILED);
    }

    // 按物品记录的模式处理地图, 等待所需地图就绪后返回快照.
    @NotNull
    public CompletableFuture<Snapshot> decodeAsync(@NotNull Snapshot snapshot, @NotNull String ownerId) {
        return this.rewriteAsync(snapshot, components -> {
            CompoundTag marker = this.marker(components);
            if (marker == null || !marker.containsKey(MAP_TYPE)) return CompletableFuture.completedFuture(components);
            MapOrigin origin = this.origin(marker);
            return this.handler(origin.type()).decodeAsync(components, origin, ownerId).thenApply(decoded -> {
                // 恢复原地图 ID 后才移除来源标记; 原地图丢失时保留副本标记.
                if (!ownerId.equals(origin.ownerId()) || !(decoded.get(MAP_ID) instanceof IntTag restoredId) || restoredId.getAsInt() != origin.id()) return decoded;
                CompoundTag remaining = new CompoundTag(new HashMap<>(marker.tags));
                remaining.remove(MAP_TYPE);
                remaining.remove(ORIGIN_SERVER);
                remaining.remove(ORIGIN_ID);
                return this.writeMarker(decoded, remaining);
            });
        }, LogConstants.DATA_MAP_DECODE_FAILED);
    }

    // 校验并读取同步模式和地图来源.
    private MapOrigin origin(CompoundTag marker) {
        if (!(marker.get(MAP_TYPE) instanceof StringTag type) || !(marker.get(ORIGIN_SERVER) instanceof StringTag server) || server.getAsString().isBlank() || !(marker.get(ORIGIN_ID) instanceof IntTag id)) {
            throw new IllegalArgumentException("invalid map origin metadata");
        }
        return new MapOrigin(MapType.valueOf(type.getAsString()), server.getAsString(), id.getAsInt());
    }

    // 并行处理地图物品, 完成后按原快照结构替换结果.
    private CompletableFuture<Snapshot> rewriteAsync(Snapshot snapshot, Function<CompoundTag, CompletableFuture<CompoundTag>> operation, String failureKey) {
        if (this.closed.isDone()) return CompletableFuture.completedFuture(snapshot);
        // 按标签对象引用保存结果, 让两次遍历对应到同一物品.
        Map<CompoundTag, CompletableFuture<CompoundTag>> prepared = new IdentityHashMap<>();
        this.rewrite(snapshot, components -> {
            prepared.computeIfAbsent(components, item -> {
                CompletableFuture<CompoundTag> result;
                try {
                    result = operation.apply(item);
                } catch (RuntimeException exception) {
                    result = CompletableFuture.failedFuture(exception);
                }
                // 超时只结束物品的等待, 发布仍按顺序完成, 避免旧数据覆盖新数据.
                return result.copy().orTimeout(5, TimeUnit.SECONDS).exceptionally(failure -> {
                    if (!this.closed.isDone()) {
                        this.logger.warnWithFileCause(LogCategory.DATA, snapshot.meta().player(), null, failure, failureKey, snapshot.meta().player().toString(), snapshot.meta().id().toString(), String.valueOf(failure));
                    }
                    return item;
                });
            });
            return components;
        });
        if (prepared.isEmpty()) return CompletableFuture.completedFuture(snapshot);
        // 关闭信号与处理结果任一完成即可继续, 快照引用随本次操作释放.
        return CompletableFuture.allOf(prepared.values().toArray(CompletableFuture[]::new))
                .applyToEither(this.closed, Function.identity())
                .thenApply(ignored -> {
                    if (this.closed.isDone()) return snapshot;
                    Snapshot rewritten = this.rewrite(snapshot, components -> prepared.get(components).getNow(components));
                    return this.closed.isDone() ? snapshot : rewritten;
                });
    }

    // 结束物品等待并返回原快照, 地图发布由服务关闭流程处理.
    public void close() {
        this.closed.complete(null);
    }

    @NotNull
    private MapHandler handler(MapType type) {
        MapHandler handler = this.handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("no map handler registered for " + type);
        }
        return handler;
    }

    // 读取 custom_data 中的来源标记.
    @Nullable
    private CompoundTag marker(CompoundTag components) {
        Tag custom = components.get(CUSTOM_DATA);
        if (custom == null) return null;
        Tag marker = ((CompoundTag) custom).get(NAMESPACE);
        if (marker == null) return null;
        if (!(marker instanceof CompoundTag compound)) {
            throw new IllegalArgumentException("map sparrow-sync data is not a compound");
        }
        return compound;
    }

    // 复制父节点后写入来源标记, 空节点直接移除.
    private CompoundTag writeMarker(CompoundTag components, CompoundTag marker) {
        CompoundTag custom = components.get(CUSTOM_DATA) instanceof CompoundTag data
                ? new CompoundTag(new HashMap<>(data.tags))
                : NBT.createCompound();
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

    // 只处理已注册的背包和末影箱数据, 没有变化时返回原快照.
    private Snapshot rewrite(Snapshot snapshot, UnaryOperator<CompoundTag> operation) {
        SnapshotData changed = snapshot.content();
        for (int i = 0; i < ITEM_DATA_KEYS.length; i++) {
            DataKey key = ITEM_DATA_KEYS[i];
            if (!this.registry.registered(key)) continue;
            if (!(snapshot.data(key) instanceof CompoundTag container)) continue;
            CompoundTag prepared = this.rewriteList(container, "items", false, operation);
            if (prepared == container) continue;
            changed = changed.with(key, prepared);
        }
        return changed == snapshot.content() ? snapshot : new Snapshot(snapshot.meta(), changed);
    }

    // 处理物品的地图信息, 递归遍历潜影盒等组件内的物品.
    private CompoundTag rewriteItem(CompoundTag item, UnaryOperator<CompoundTag> operation) {
        if (!(item.get("components") instanceof CompoundTag components)) return item;
        CompoundTag changed = components;
        String id = item.getString("id");
        if (id.equals("minecraft:filled_map") || id.equals("filled_map")) {
            changed = operation.apply(changed);
        }
        // 只遍历原版物品组件, 保留 custom_data 中的自定义数据.
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

    // 逐项处理物品, 有变化时才复制列表和父节点.
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
