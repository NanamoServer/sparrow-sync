package net.momirealms.sparrow.sync.map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
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
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.HideMapHandler;
import net.momirealms.sparrow.sync.map.handler.SyncMapHandler;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.CompoundTagProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.ItemStackTemplateProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.component.BundleContentsProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.component.ChargedProjectilesProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.component.UseRemainderProxy;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class MapSyncService {
    private final SparrowSync plugin;
    private final String ownerId;
    private final MinecraftServer server;
    private final NativeMapAdapter nativeMaps;
    private final MapPublisher publisher;
    private final MapReceiver receiver;
    private final MapPipeline pipeline;
    private volatile boolean closed; // 关服后各入口据此放弃后续工作

    public MapSyncService(@NotNull SparrowSync plugin, @NotNull String ownerId) {
        this.plugin = plugin;
        this.ownerId = ownerId;
        this.server = MinecraftServer.getServer();
        this.nativeMaps = new NativeMapAdapter(this.server.registryAccess(), VersionHelper.WORLD_VERSION);
        MapStorage storage = plugin.storageProvider().maps(ownerId);
        MapCache shared = new RedisMapCache(plugin.redisConnector().connection().async(), plugin.messageBrokerManager().broker(), ownerId, plugin.scheduler().async());
        this.publisher = new MapPublisher(storage, shared, plugin.scheduler().async());
        this.receiver = new MapReceiver(storage, shared, this.nativeMaps, this.server, ownerId, plugin.scheduler().async(), plugin.scheduler().sync(), plugin.logger());
        this.pipeline = new MapPipeline(plugin.dataRegistry(), List.of(new HideMapHandler(), new SyncMapHandler(this.receiver)), plugin.logger());
    }

    /**
     * 采集玩家携带的来源地图并启动异步发布, 固定本次保存使用的模式.
     * <p><strong>须在允许读取玩家和原生地图的线程调用</strong>.
     */
    @NotNull
    public Capture captureAndPublish(@NotNull Player player) {
        long start = System.nanoTime();
        MapType mode = PluginConfig.synchronization$map().type();
        if (mode != MapType.SYNC) return new Capture(mode, Map.of());
        Map<Integer, CompletableFuture<StoredMap>> publications = new HashMap<>();
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        if (this.plugin.dataRegistry().registered(InventoryDataType.INVENTORY)) {
            this.scan(handle.getInventory(), publications);
        }
        if (this.plugin.dataRegistry().registered(EnderChestDataType.ENDER_CHEST)) {
            this.scan(handle.getEnderChestInventory(), publications);
        }
        System.out.println("采集使用: " + (System.nanoTime() - start));
        return new Capture(mode, publications);
    }

    /** 等待本次地图发布结果, 将快照中的地图物品编译为传输形式. */
    @NotNull
    public CompletableFuture<Snapshot> compileAsync(@NotNull Snapshot snapshot, @NotNull Capture captured) {
        return this.pipeline.compileAsync(snapshot, captured.type(), this.ownerId, captured.publications());
    }

    /** 更新本服地图数据并选择物品 ID, 返回供玩家数据解码使用的快照. */
    @NotNull
    public CompletableFuture<Snapshot> decodeAsync(@NotNull Snapshot snapshot) {
        return this.pipeline.decodeAsync(snapshot, this.ownerId);
    }

    // 扫描一个原生物品栏, 将其中嵌套地图加入本次采集结果.
    private void scan(Container container, Map<Integer, CompletableFuture<StoredMap>> publications) {
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            this.scan(container.getItem(slot), publications);
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
                        MapData data = this.nativeMaps.capture(this.server.overworld(), nativeId);
                        if (data == null) {
                            throw new IllegalStateException("source map does not exist: " + source);
                        }
                        return this.publisher.publish(source, data);
                    } catch (RuntimeException exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                });
            }
        }
        // 递归范围与快照管线一致, 容器、收纳袋、装填物和使用余留物中的地图也参与保存
        ItemContainerContents container = this.component(item, DataComponents.CONTAINER);
        if (container != null) {
            for (Object nested : container.nonEmptyItems()) {
                this.scan(nested, publications);
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
        net.minecraft.nbt.Tag marker = CompoundTagProxy.INSTANCE.getTags(custom.copyTag()).get("sparrow-sync");
        // 损坏的来源标记也不能被当成本服原图上传, 最终由组件管线统一告警回退.
        return marker != null && (!(marker instanceof net.minecraft.nbt.CompoundTag compound) || CompoundTagProxy.INSTANCE.getTags(compound).containsKey("map-type"));
    }

    @NotNull
    public String ownerId() {
        return this.ownerId;
    }

    public void observe(int globalId) {
        if (!this.closed) {
            this.receiver.observe(globalId);
        }
    }

    public void invalidate(int globalId) {
        if (!this.closed) {
            this.receiver.refresh(globalId);
        }
    }

    // 停止地图副本数据的拉取与更新, 来源发布保留到关服最终保存结束.
    public void stopReceiving() {
        this.receiver.close();
    }

    // 限时等待已采集的来源候选结束发布, 随后关闭本服务.
    public void finishPublishing(long timeout, @NotNull TimeUnit unit) {
        if (!this.publisher.sealAndAwait(timeout, unit)) {
            this.plugin.logger().warn(LogCategory.DATA, LogConstants.DATA_MAP_PUBLISH_UNFINISHED, this.ownerId);
        }
        this.close();
    }

    // 释放待编译快照并停止服务后续工作, 数据库映射和原生副本继续留存
    public void close() {
        this.closed = true;
        this.pipeline.close();
        this.stopReceiving();
        this.publisher.close();
    }

    public boolean closed() {
        return this.closed;
    }

    // 保存期间持有的模式与发布任务, 供后续物品编译等待.
    public record Capture(@NotNull MapType type, @NotNull Map<Integer, CompletableFuture<StoredMap>> publications) {
    }
}
