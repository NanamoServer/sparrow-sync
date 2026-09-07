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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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

    // 等待地图发布获取到全局唯一ID后生成传输快照.
    @NotNull
    public CompletableFuture<Snapshot> compileAsync(@NotNull Snapshot snapshot, @NotNull MapType type, @NotNull String ownerId, @NotNull Map<Integer, CompletableFuture<StoredMap>> publications) {
        Map<Integer, CompletableFuture<Boolean>> renewals = new HashMap<>();
        return this.rewriteAsync(snapshot, components -> {
            // 已有模式的中转地图交给其处理器续行
            CompoundTag marker = this.marker(components);
            if (marker != null && marker.containsKey(MAP_TYPE)) {
                MapOrigin origin = this.origin(marker);
                return this.handler(origin.type()).forwardAsync(components, origin, renewals);
            }
            Tag mapId = components.get(MAP_ID);
            if (mapId == null) return CompletableFuture.completedFuture(components);
            return this.handler(type)
                    .compileAsync(components, new MapOrigin(type, ownerId, ((IntTag) mapId).getAsInt()), publications)
                    .thenApply(compiled -> {
                        // 在处理器成功后写入地图来源字段, 保留同命名空间的其他业务字段.
                        CompoundTag origin = marker == null ? NBT.createCompound() : new CompoundTag(new HashMap<>(marker.tags));
                        origin.putString(MAP_TYPE, type.name());
                        origin.putString(ORIGIN_SERVER, ownerId);
                        origin.put(ORIGIN_ID, mapId);
                        return this.writeMarker(compiled, origin);
                    });
        }, LogConstants.DATA_MAP_COMPILE_FAILED);
    }

    // 按物品的来源模式准备接收数据, 等本服地图副本可用后返回快照.
    @NotNull
    public CompletableFuture<Snapshot> decodeAsync(@NotNull Snapshot snapshot, @NotNull String ownerId) {
        return this.rewriteAsync(snapshot, components -> {
            CompoundTag marker = this.marker(components);
            if (marker == null || !marker.containsKey(MAP_TYPE)) return CompletableFuture.completedFuture(components);
            MapOrigin origin = this.origin(marker);
            return this.handler(origin.type()).decodeAsync(components, origin, ownerId).thenApply(decoded -> {
                // 只有实际恢复来源地图 ID 才清理标记, 返回来源服时若找不到来源地图, 则保留本服地图副本的同步标识.
                if (!ownerId.equals(origin.ownerId()) || !(decoded.get(MAP_ID) instanceof IntTag restoredId) || restoredId.getAsInt() != origin.id()) return decoded;
                CompoundTag remaining = new CompoundTag(new HashMap<>(marker.tags));
                remaining.remove(MAP_TYPE);
                remaining.remove(ORIGIN_SERVER);
                remaining.remove(ORIGIN_ID);
                return this.writeMarker(decoded, remaining);
            });
        }, LogConstants.DATA_MAP_DECODE_FAILED);
    }

    // 校验并读取物品记录的模式和地图来源.
    private MapOrigin origin(CompoundTag marker) {
        if (!(marker.get(MAP_TYPE) instanceof StringTag type) || !(marker.get(ORIGIN_SERVER) instanceof StringTag server) || server.getAsString().isBlank() || !(marker.get(ORIGIN_ID) instanceof IntTag id)) {
            throw new IllegalArgumentException("invalid map origin metadata");
        }
        return new MapOrigin(MapType.valueOf(type.getAsString()), server.getAsString(), id.getAsInt());
    }

    // 并行准备单张地图结果, 完成后沿原快照结构生成改写结果.
    private CompletableFuture<Snapshot> rewriteAsync(Snapshot snapshot, Function<CompoundTag, CompletableFuture<CompoundTag>> operation, String failureKey) {
        if (this.closed.isDone()) return CompletableFuture.completedFuture(snapshot);
        // 按标签对象身份记住准备结果, 两次遍历可准确对应同一物品的组件
        Map<CompoundTag, CompletableFuture<CompoundTag>> prepared = new IdentityHashMap<>();
        this.rewrite(snapshot, components -> {
            prepared.computeIfAbsent(components, item -> {
                CompletableFuture<CompoundTag> result;
                try {
                    result = operation.apply(item);
                } catch (RuntimeException exception) {
                    result = CompletableFuture.failedFuture(exception);
                }
                // 超时只结束本次物品等待, 不截断底层发布链, 防止旧写入迟到越过新写入.
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
        // 关闭信号只参与完成竞争, 快照引用留在本次操作中, 完成后即可释放.
        return CompletableFuture.allOf(prepared.values().toArray(CompletableFuture[]::new))
                .applyToEither(this.closed, Function.identity())
                .thenApply(ignored -> {
                    if (this.closed.isDone()) return snapshot;
                    Snapshot rewritten = this.rewrite(snapshot, components -> prepared.get(components).getNow(components));
                    return this.closed.isDone() ? snapshot : rewritten;
                });
    }

    // 结束当前管线的物品等待并返回原快照, 底层发布由服务生命周期另行收尾
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

    // 读取物品的来源命名空间
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

    // 以新父节点写入来源命名空间, 空命名空间就移除.
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

    // 在已启用的背包与末影箱数据中查找地图, 按需要复制快照节点.
    private Snapshot rewrite(Snapshot snapshot, UnaryOperator<CompoundTag> operation) {
        Map<DataKey, Tag> changed = null;
        for (int i = 0; i < ITEM_DATA_KEYS.length; i++) {
            DataKey key = ITEM_DATA_KEYS[i];
            if (!this.registry.registered(key)) continue;
            if (!(snapshot.data(key) instanceof CompoundTag container)) continue;
            CompoundTag prepared = this.rewriteList(container, "items", false, operation);
            if (prepared == container) continue;
            if (changed == null) changed = new LinkedHashMap<>(snapshot.data());
            changed.put(key, prepared);
        }
        return changed == null ? snapshot : new Snapshot(snapshot.meta(), changed);
    }

    // 处理一件地图及原版组件承载的嵌套物品.
    private CompoundTag rewriteItem(CompoundTag item, UnaryOperator<CompoundTag> operation) {
        if (!(item.get("components") instanceof CompoundTag components)) return item;
        CompoundTag changed = components;
        String id = item.getString("id");
        if (id.equals("minecraft:filled_map") || id.equals("filled_map")) {
            changed = operation.apply(changed);
        }
        // 嵌套遍历限定原版物品组件, custom_data 中的任意业务 NBT 按原值保留
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

    // 逐项处理物品列表, 仅在某项变化后复制列表与父节点.
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
