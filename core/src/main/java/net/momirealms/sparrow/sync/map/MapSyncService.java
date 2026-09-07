package net.momirealms.sparrow.sync.map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.cache.MapCache;
import net.momirealms.sparrow.sync.map.cache.RedisMapCache;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.HideMapHandler;
import net.momirealms.sparrow.sync.map.handler.SyncMapHandler;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.CompoundTagProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.ItemStackTemplateProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.component.BundleContentsProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.component.ChargedProjectilesProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.component.CustomDataProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.component.ItemContainerContentsProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.component.UseRemainderProxy;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class MapSyncService {
    private final SparrowSync plugin;
    private final String ownerId;
    private final MinecraftServer server;
    private final NativeMapAdapter nativeMaps;
    private final NativeMapStorage nativeStorage;
    private final MapPublisher publisher;
    private final MapReceiver receiver;
    private final MapPipeline pipeline;

    public MapSyncService(@NotNull SparrowSync plugin, @NotNull String ownerId) {
        this.plugin = plugin;
        this.ownerId = ownerId;
        this.server = MinecraftServer.getServer();
        this.nativeMaps = new NativeMapAdapter(this.server.registryAccess(), VersionHelper.WORLD_VERSION);
        this.nativeStorage = new NativeMapStorage(this.server, VersionHelper.WORLD_VERSION);
        MapStorage storage = plugin.storageProvider().maps();
        MapCache shared = new RedisMapCache(plugin.redisConnector().connection().async(), plugin.messageBrokerManager().broker(), plugin.scheduler().async());
        this.publisher = new MapPublisher(storage, shared, ownerId, plugin.scheduler().async());
        this.receiver = new MapReceiver(storage, shared, this.nativeMaps, this.server, ownerId, plugin.logger());
        this.pipeline = new MapPipeline(plugin.dataRegistry(), List.of(new HideMapHandler(), new SyncMapHandler(this.receiver)), plugin.logger());
    }

    /**
     * 在玩家串行线程扫描本次物品副本并采集来源地图, 物品范围固定于本次玩家采集.
     * 地图模式由保存入口固定; 来源地图像素以本次异步采集时的内容为准.
     */
    @NotNull
    public Capture captureAndPublish(@NotNull PlayerDataPipeline.CaptureResult.Ready captured, @NotNull MapType mode) {
        if (mode != MapType.SYNC) return new Capture(mode, Map.of());
        Map<Integer, CompletableFuture<StoredMap>> publications = new HashMap<>();
        if (captured.value(InventoryDataType.INVENTORY) instanceof InventoryDataType.Inventory inventory) {
            this.scan(inventory.contents(), publications);
        }
        if (captured.value(EnderChestDataType.ENDER_CHEST) instanceof ItemCodec.LoadedItems enderChest) {
            this.scan(enderChest.items(), publications);
        }
        return new Capture(mode, publications);
    }

    /** 等待本次地图发布结果, 将快照中的地图物品编码为传输形式. */
    @NotNull
    public CompletableFuture<Snapshot> compileAsync(@NotNull Snapshot snapshot, @NotNull Capture captured) {
        return this.pipeline.encodeAsync(snapshot, captured.type(), this.ownerId, captured.publications());
    }

    /** 更新本服地图数据并选择物品 ID, 返回供玩家数据解码使用的快照. */
    @NotNull
    public CompletableFuture<Snapshot> decodeAsync(@NotNull Snapshot snapshot) {
        return this.pipeline.decodeAsync(snapshot, this.ownerId);
    }

    // 采集缓冲中的空槽为 null, 嵌套组件继续沿只读 NMS 物品对象递归.
    private void scan(ItemStack[] items, Map<Integer, CompletableFuture<StoredMap>> publications) {
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            if (item != null) this.scan(item, publications);
        }
    }

    // 递归读取原版物品载体, 采集未携带来源标记的本服地图.
    private void scan(Object item, Map<Integer, CompletableFuture<StoredMap>> publications) {
        Item type;
        if (item instanceof ItemStack stack) {
            if (stack.isEmpty()) return;
            type = ItemStackProxy.INSTANCE.getItem(stack);
        } else {
            // 26.1 起嵌套内容为非空 ItemStackTemplate, 只读访问其类型和组件.
            type = ItemStackTemplateProxy.INSTANCE.item(item).value();
        }
        if (type == Items.FILLED_MAP) {
            MapId id = this.component(item, DataComponents.MAP_ID);
            if (id != null && !this.marked(item)) {
                publications.computeIfAbsent(id.id(), nativeId -> {
                    try {
                        MapSource source = new MapSource(this.ownerId, nativeId);
                        MapData data = this.nativeMaps.capture(this.nativeStorage, nativeId);
                        if (data == null) {
                            throw new IllegalStateException("source map does not exist: " + source);
                        }
                        return this.publisher.publish(source, data);
                    } catch (IOException | RuntimeException exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                });
            }
        }
        // 递归范围与快照管线一致, 容器、收纳袋、装填物和使用余留物中的地图也参与保存
        ItemContainerContents container = this.component(item, DataComponents.CONTAINER);
        if (container != null) {
            List<?> items = ItemContainerContentsProxy.INSTANCE.getItems(container);
            int size = items.size();
            // NMS 物品列表按只读访问, 26.1 起空槽由 Optional 表示.
            if (VersionHelper.isOrAbove26_1()) {
                for (int i = 0; i < size; i++) {
                    Object nested = ((Optional<?>) items.get(i)).orElse(null);
                    if (nested != null) {
                        this.scan(nested, publications);
                    }
                }
            } else {
                for (int i = 0; i < size; i++) {
                    this.scan(items.get(i), publications);
                }
            }
        }
        BundleContents bundle = this.component(item, DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            List<?> items = BundleContentsProxy.INSTANCE.getItems(bundle);
            int size = items.size();
            for (int i = 0; i < size; i++) {
                this.scan(items.get(i), publications);
            }
        }
        ChargedProjectiles projectiles = this.component(item, DataComponents.CHARGED_PROJECTILES);
        if (projectiles != null) {
            List<?> items = ChargedProjectilesProxy.INSTANCE.getItems(projectiles);
            int size = items.size();
            for (int i = 0; i < size; i++) {
                this.scan(items.get(i), publications);
            }
        }
        UseRemainder remainder = this.component(item, DataComponents.USE_REMAINDER);
        if (remainder != null) {
            this.scan(UseRemainderProxy.INSTANCE.getConvertInto(remainder), publications);
        }
    }

    // 以同一入口读取物品栈和新版嵌套模板的组件.
    @Nullable
    private <T> T component(Object item, DataComponentType<T> type) {
        return item instanceof ItemStack stack ? stack.get(type) : ItemStackTemplateProxy.INSTANCE.get(item, type);
    }

    // 判断物品是否已经携带地图来源信息, 使外服和损坏标记进入管线处理.
    private boolean marked(Object item) {
        CustomData custom = this.component(item, DataComponents.CUSTOM_DATA);
        if (custom == null) return false;
        // 只读检查 NMS 物品组件中的来源字段.
        net.minecraft.nbt.Tag marker = CompoundTagProxy.INSTANCE.getTags(CustomDataProxy.INSTANCE.getTag(custom)).get("sparrow-sync");
        // 损坏的来源标记也不能被当成本服来源地图上传, 最终由组件管线统一告警回退.
        return marker != null && (!(marker instanceof net.minecraft.nbt.CompoundTag compound) || CompoundTagProxy.INSTANCE.getTags(compound).containsKey("map-type"));
    }

    public void observe(int globalId) {
        this.receiver.observe(globalId);
    }

    public void invalidate(int globalId) {
        this.receiver.refresh(globalId);
    }

    // 停止地图副本数据的拉取与更新, 来源发布保留到关服最终保存结束.
    public void stopReceiving() {
        this.receiver.close();
    }

    // 限时等待已采集的来源地图数据完成发布, 随后关闭本服务.
    public void finishPublishing(long timeout, @NotNull TimeUnit unit) {
        if (!this.publisher.sealAndAwait(timeout, unit)) {
            this.plugin.logger().warn(LogCategory.DATA, LogConstants.DATA_MAP_PUBLISH_UNFINISHED, this.ownerId);
        }
        this.close();
    }

    // 释放待编码的物品快照并停止服务后续工作, 数据库映射和本服地图副本继续留存
    public void close() {
        this.pipeline.close();
        this.stopReceiving();
        this.publisher.close();
    }

    // 保存期间持有的模式与发布任务, 供后续物品编码等待.
    public record Capture(@NotNull MapType type, @NotNull Map<Integer, CompletableFuture<StoredMap>> publications) {
    }
}
