package net.momirealms.sparrow.sync.map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.HideMapHandler;
import net.momirealms.sparrow.sync.map.handler.SyncMapHandler;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.CompoundTagProxy;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/** 玩家采集与地图基础服务的生命周期装配, 不增加地图采集定时器. */
public final class MapSyncService {
    private final SparrowSync plugin;
    private final String ownerId;
    private final UUID worldUuid;
    private final ServerLevel level;
    private final NativeMapAdapter nativeMaps;
    private final MapCache shared;
    private final MapPublisher publisher;
    private final MapReceiver receiver;
    private final MapPipeline pipeline;
    private CompletableFuture<MapStorage> storage;

    public MapSyncService(@NotNull SparrowSync plugin, @NotNull String ownerId, @NotNull UUID worldUuid) {
        this.plugin = plugin;
        this.ownerId = ownerId;
        this.worldUuid = worldUuid;
        MinecraftServer server = MinecraftServer.getServer();
        this.level = server.overworld();
        this.nativeMaps = new NativeMapAdapter(server.registryAccess(), VersionHelper.WORLD_VERSION);
        this.shared = new RedisMapCache(plugin.redisConnector().connection().async(), plugin.messageBrokerManager().broker(), ServerConfig.clusterId(), ownerId, plugin.scheduler().async());
        this.publisher = new MapPublisher(this::storage, this.shared, plugin.scheduler().async());
        this.receiver = new MapReceiver(this::storage, this.shared, this::prepareReplica, plugin.scheduler().async(), plugin.scheduler().sync(), plugin.logger());
        MapInvalidationMessage.listener(id -> {
            if (this.active()) this.receiver.invalidate(id);
        });
        this.pipeline = new MapPipeline(plugin.dataRegistry(), List.of(new HideMapHandler(), new SyncMapHandler(ServerConfig.clusterId(), this.receiver)), plugin.logger());
    }

    private IntSupplier prepareReplica(StoredMap map) throws IOException {
        MapItemSavedData prepared = this.nativeMaps.prepareReplica(map.identity(), map.data());
        return () -> {
            if (!this.active()) {
                throw new IllegalStateException("map synchronization is disabled or its owner changed");
            }
            MapSource source = map.identity().source();
            if (this.ownerId.equals(source.ownerId())) {
                if (this.level.getMapData(new MapId(source.id())) != null) return source.id();
                this.plugin.logger().warn(LogCategory.DATA, LogConstants.DATA_MAP_SOURCE_MISSING, this.ownerId, String.valueOf(source.id()), String.valueOf(map.identity().globalId()));
            }
            this.nativeMaps.installReplica(this.level, map.identity(), prepared);
            return map.identity().globalId();
        };
    }

    @NotNull
    public String ownerId() {
        return this.ownerId;
    }

    @NotNull
    public MapPipeline pipeline() {
        return this.pipeline;
    }

    private boolean active() {
        PluginConfig.MapOptions options = PluginConfig.synchronization$map();
        return options.enabled() && this.ownerId.equals(options.resolveOwnerId(ServerConfig.serverId(), this.worldUuid));
    }

    private synchronized CompletableFuture<MapStorage> storage() {
        if (!this.active()) return CompletableFuture.failedFuture(new IllegalStateException("map synchronization is disabled or its owner changed"));
        if (this.storage == null || this.storage.isCompletedExceptionally()) {
            this.storage = this.plugin.storageProvider().maps(ServerConfig.clusterId(), this.ownerId);
        }
        return this.storage;
    }

    /** 调用方处于玩家拥有线程; 退出保存也在 Quit 后下一 Region tick、入异步队列前调用. */
    @NotNull
    public Map<Integer, CompletableFuture<StoredMap>> capture(@NotNull Player player, @NotNull MapType mode) {
        if (mode != MapType.SYNC) return Map.of();
        Map<Integer, CompletableFuture<StoredMap>> captured = new HashMap<>();
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        if (this.plugin.dataRegistry().registered(InventoryDataType.INVENTORY)) {
            this.scan(handle.getInventory(), captured);
        }
        if (this.plugin.dataRegistry().registered(EnderChestDataType.ENDER_CHEST)) {
            this.scan(handle.getEnderChestInventory(), captured);
        }
        return captured;
    }

    private void scan(Container container, Map<Integer, CompletableFuture<StoredMap>> captured) {
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            this.scan(container.getItem(slot), captured);
        }
    }

    private void scan(ItemStack item, Map<Integer, CompletableFuture<StoredMap>> captured) {
        if (item.isEmpty()) return;
        if (item.is(Items.FILLED_MAP)) {
            MapId id = item.get(DataComponents.MAP_ID);
            if (id != null && !this.marked(item)) {
                captured.computeIfAbsent(id.id(), nativeId -> {
                    try {
                        return this.publisher.capture(new MapSource(this.ownerId, nativeId), () -> this.nativeMaps.capture(this.level, nativeId));
                    } catch (RuntimeException exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                });
            }
        }
        ItemContainerContents container = item.get(DataComponents.CONTAINER);
        if (container != null) {
            for (ItemStack nested : container.nonEmptyItems()) {
                this.scan(nested, captured);
            }
        }
        BundleContents bundle = item.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack nested : bundle.items()) {
                this.scan(nested, captured);
            }
        }
        ChargedProjectiles projectiles = item.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles != null) {
            List<ItemStack> items = projectiles.getItems();
            int size = items.size();
            for (int i = 0; i < size; i++) {
                this.scan(items.get(i), captured);
            }
        }
        UseRemainder remainder = item.get(DataComponents.USE_REMAINDER);
        if (remainder != null) {
            this.scan(remainder.convertInto(), captured);
        }
    }

    private boolean marked(ItemStack item) {
        CustomData custom = item.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return false;
        net.minecraft.nbt.Tag marker = CompoundTagProxy.INSTANCE.getTags(custom.copyTag()).get("sparrow-sync");
        // 损坏的来源标记也不能被当成本服原图上传, 最终由组件管线统一告警回退.
        return marker != null && (!(marker instanceof net.minecraft.nbt.CompoundTag compound) || CompoundTagProxy.INSTANCE.getTags(compound).containsKey("map-type"));
    }
}
