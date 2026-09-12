package net.momirealms.sparrow.sync.map;

import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.StringTag;
import io.netty.buffer.Unpooled;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapFrame;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.saveddata.maps.MapItemSavedDataProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.SavedDataStorageProxy;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.util.VersionHelper;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.minecraft.resources.RegistryOps;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.map.handler.SyncMapHandler;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.HideMapHandler;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class NativeMapAdapterTest {
    @RegisterExtension
    private final MapFlowTestSupport.PluginInstance pluginInstance = new MapFlowTestSupport.PluginInstance();

    private static HolderLookup.Provider registries;
    @TempDir
    Path directory;
    private Object previousMinecraft;
    private Object previousBukkit;
    private Object previousConfig;
    private Object previousServerConfig;
    private Object previousRegistryOps;
    private DimensionDataStorage storage;
    private ServerLevel level;
    private NativeMapAdapter adapter;
    private NativeMapStorage nativeStorage;
    private TestPluginManager pluginManager;
    private final MapIdentity identity = new MapIdentity(new MapSource("A-world", 1), -1);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
        BukkitProxy.init("1.21.8", List.of("paper"));
    }

    @BeforeEach
    @SuppressWarnings("removal")
    void prepare() throws Exception {
        this.previousRegistryOps = replaceStatic(MinecraftRegistryOps.class, "sparrowNbt", RegistryOps.create(NBTOps.INSTANCE, registries));
        this.previousConfig = replaceStatic(PluginConfig.class, "config", new PluginConfig.ConfigDefinition());
        this.previousServerConfig = replaceStatic(ServerConfig.class, "config", new ServerConfig.ConfigDefinition());
        DedicatedServer server = NmsPlayerFixture.allocate(DedicatedServer.class);
        this.previousMinecraft = replaceStatic(MinecraftServer.class, "SERVER", server);
        CraftServer craft = NmsPlayerFixture.allocate(CraftServer.class);
        DedicatedPlayerList players = NmsPlayerFixture.allocate(DedicatedPlayerList.class);
        NmsPlayerFixture.set(PlayerList.class, players, "playersByName", new HashMap<>());
        NmsPlayerFixture.set(CraftServer.class, craft, "playerList", players);
        SimplePluginManager manager = NmsPlayerFixture.allocate(SimplePluginManager.class);
        this.pluginManager = NmsPlayerFixture.allocate(TestPluginManager.class);
        manager.paperPluginManager = this.pluginManager;
        NmsPlayerFixture.set(CraftServer.class, craft, "pluginManager", manager);
        this.previousBukkit = replaceStatic(Bukkit.class, "server", craft);
        this.level = NmsPlayerFixture.allocate(ServerLevel.class);
        NmsPlayerFixture.set(ServerLevel.class, this.level, "server", server);
        NmsPlayerFixture.set(ServerLevel.class, this.level, "uuid", UUID.randomUUID());
        NmsPlayerFixture.set(MinecraftServer.class, server, "levels", Map.of(Level.OVERWORLD, this.level));
        this.storage = new DimensionDataStorage(new SavedData.Context(this.level, 0L), this.directory, DataFixers.getDataFixer(), registries);
        ServerChunkCache chunks = NmsPlayerFixture.allocate(ServerChunkCache.class);
        NmsPlayerFixture.set(ServerChunkCache.class, chunks, "dataStorage", this.storage);
        NmsPlayerFixture.set(ServerLevel.class, this.level, "chunkSource", chunks);
        this.adapter = new NativeMapAdapter(registries, VersionHelper.WORLD_VERSION);
        this.nativeStorage = new NativeMapStorage(server, VersionHelper.WORLD_VERSION);
    }

    @AfterEach
    void restore() throws Exception {
        try {
            if (this.storage != null) {
                this.storage.close();
            }
        } finally {
            replaceStatic(MinecraftRegistryOps.class, "sparrowNbt", this.previousRegistryOps);
            replaceStatic(MinecraftServer.class, "SERVER", this.previousMinecraft);
            replaceStatic(Bukkit.class, "server", this.previousBukkit);
            replaceStatic(PluginConfig.class, "config", this.previousConfig);
            replaceStatic(ServerConfig.class, "config", this.previousServerConfig);
        }
    }

    @Test
    void capturedModeStaysFixedWhenConfigurationReloadsBeforeCompilation() {
        DataRegistry registry = new DataRegistry();
        registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        MapSyncService service = this.service("A-world", registry, null);
        NmsPlayerFixture.set(MapSyncService.class, service, "pipeline", new MapPipeline(registry, List.of(new HideMapHandler()), MapFlowTestSupport.logger(new ArrayList<>())));
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, PluginConfig.synchronization$map(), "synchronization_mode", MapType.HIDE);
        CraftPlayer player = NmsPlayerFixture.create();
        NmsPlayerFixture.set(net.minecraft.world.entity.player.Player.class, player.getHandle(), "inventory", new Inventory(player.getHandle(), new EntityEquipment()));
        MapType captured = PluginConfig.synchronization$map().synchronization_mode();
        assertEquals(MapType.HIDE, captured);
        // 保存已经开始, 配置重载只影响下一次采集.
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, PluginConfig.synchronization$map(), "synchronization_mode", MapType.SYNC);
        CompoundTag components = NBT.createCompound();
        components.putInt("minecraft:map_id", 1);
        CompoundTag item = NBT.createCompound();
        item.putString("id", "minecraft:filled_map");
        item.put("components", components);
        ListTag items = NBT.createList();
        items.add(item);
        CompoundTag inventory = NBT.createCompound();
        inventory.put("items", items);
        Snapshot snapshot = new Snapshot(new SnapshotMeta(UUID.randomUUID(), player.getUniqueId(), 1, SaveCause.WORLD_SAVE, false, "A", VersionHelper.WORLD_VERSION), Map.of(InventoryDataType.INVENTORY, inventory));
        Snapshot compiled = service.compileAsync(snapshot, captured, player.getName()).join();
        CompoundTag encoded = ((CompoundTag) compiled.data(InventoryDataType.INVENTORY)).getList("items").getCompound(0).getCompound("components");
        assertNull(encoded.get("minecraft:map_id"));
        assertEquals("HIDE", encoded.getCompound("minecraft:custom_data").getCompound("sparrow-sync").getString("map-type"));
        assertEquals(1, components.getInt("minecraft:map_id"));
        assertEquals(snapshot.allData(), service.decodeAsync(compiled).join().allData());
    }

    @Test
    void syncReturnPreservesCurrentOriginalAndMissingOrChangedOwnerKeepsNegativeReplica() throws Exception {
        MapItemSavedData source = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        source.colors[0] = 90;
        this.level.setMapData(new MapId(1), source);
        StoredMap old = new StoredMap(MapFlowTestSupport.IDENTITY, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(10)));
        assertEquals(1, this.receive("A-world", old).join());
        assertSame(source, this.level.getMapData(new MapId(1)));
        assertEquals(90, source.colors[0]);
        assertNull(this.level.getMapData(new MapId(-1)));

        this.storage.cache.clear();
        assertEquals(-1, this.receive("A-world", old).join());
        assertEquals(10, this.level.getMapData(new MapId(-1)).colors[0]);
        assertNull(this.level.getMapData(new MapId(1)));
        this.level.setMapData(new MapId(1), source);
        assertEquals(-1, this.receive("B-world", old).join());
        assertEquals(90, source.colors[0]);
        this.storage.saveAndJoin();
        assertTrue(Files.exists(this.directory.resolve("map_-1.dat")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "different-source", "vanilla"})
    void observedReplicaUsesSharedIdentityAndPreservesNativeObject(String localIdentity) throws Exception {
        MapData oldContent = new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(10));
        MapItemSavedData old = this.adapter.prepareReplica(this.identity, oldContent);
        String dimension = switch (localIdentity) {
            case "legacy" -> this.identity.replicaDimension().replace("sparrow-sync:map/", "sparrow-sync:map/6d61696e/");
            case "different-source" -> new MapIdentity(new MapSource("retired-world", 9), -1).replicaDimension();
            default -> "minecraft:overworld";
        };
        NmsPlayerFixture.set(MapItemSavedData.class, old, "dimension", Level.RESOURCE_KEY_CODEC.parse(NbtOps.INSTANCE, StringTag.valueOf(dimension)).getOrThrow());
        old.uniqueId = UUID.randomUUID();
        this.level.setMapData(new MapId(-1), old);
        Object view = old.mapView;
        byte[] colors = old.colors;
        MapFlowTestSupport.Storage database = new MapFlowTestSupport.Storage();
        database.current = new StoredMap(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(20)));
        MapReceiver receiver = new MapReceiver(database, new MapFlowTestSupport.Shared(), this.adapter, this.level.getServer(), "B-world", MapFlowTestSupport.logger(new ArrayList<>()));
        MapFlowTestSupport.scheduler(Runnable::run, Runnable::run);
        // 首次观察按全局 ID 查库, 原地修正本地副本的身份与内容.
        receiver.observe(-1);
        assertEquals(1, database.reads);
        assertSame(old, this.level.getMapData(new MapId(-1)));
        assertSame(view, old.mapView);
        assertSame(colors, old.colors);
        assertNull(old.uniqueId);
        assertEquals(this.identity.replicaDimension(), MapFlowTestSupport.dimension(this.level.getMapData(new MapId(-1))));
        assertEquals(20, old.colors[0]);
        assertEquals(-1, receiver.receive(this.identity).join());
        MapItemSavedData current = this.level.getMapData(new MapId(-1));
        current.setDirty(false);
        database.current = new StoredMap(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(30)));
        receiver.refresh(-1);
        assertEquals(30, current.colors[0]);
        assertTrue(current.isDirty());
        this.storage.saveAndJoin();
        this.storage.cache.clear();
        assertEquals(this.identity.replicaDimension(), MapFlowTestSupport.dimension(this.level.getMapData(new MapId(-1))));
        assertEquals(30, this.level.getMapData(new MapId(-1)).colors[0]);
        receiver.close();
    }

    @Test
    void encodedItemsFixTheScopeAndSamplePixelsWhenCompilationStarts() {
        DataRegistry registry = new DataRegistry();
        registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        MapFlowTestSupport.Storage database = new MapFlowTestSupport.Storage();
        MapFlowTestSupport.Tasks worker = new MapFlowTestSupport.Tasks();
        MapPublisher publisher = new MapPublisher(database, new MapFlowTestSupport.Shared(), "A-world", worker);
        MapSyncService service = this.service("A-world", registry, publisher);
        CraftPlayer player = NmsPlayerFixture.create();
        Inventory inventory = new Inventory(player.getHandle(), new EntityEquipment());
        NmsPlayerFixture.set(net.minecraft.world.entity.player.Player.class, player.getHandle(), "inventory", inventory);
        MapItemSavedData source = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        source.colors[0] = 12;
        this.level.setMapData(new MapId(1), source);
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, new MapId(1));
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(map.copy(), map.copy())));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(ItemStack.EMPTY, bundle, ItemStack.EMPTY, map.copy())));
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(box));
        ItemStack consumable = new ItemStack(Items.STONE);
        consumable.set(DataComponents.USE_REMAINDER, new UseRemainder(crossbow));
        inventory.setItem(0, map);
        inventory.setItem(1, consumable);
        InventoryDataType.Inventory captured = (InventoryDataType.Inventory) this.capture(player, registry).value(InventoryDataType.INVENTORY);
        // 玩家物品在采集时固定, 来源像素取编码之后开始地图处理时的内容.
        inventory.clearContent();
        source.colors[0] = 15;
        Snapshot snapshot = this.encodedItems(captured.contents());
        assertEquals(0, database.registrations);
        source.colors[0] = 18;
        CompletableFuture<Snapshot> compiled = CompletableFuture.supplyAsync(() -> service.compileAsync(snapshot, MapType.SYNC, snapshot.meta().player().toString())).join();
        source.colors[0] = 21;
        worker.runAll();
        assertEquals(18, database.current.data().getTag().getByteArray("colors")[0]);
        assertEquals(1, database.registrations);
        ListTag encodedItems = ((CompoundTag) compiled.join().data(InventoryDataType.INVENTORY)).getList("items");
        CompoundTag encoded = encodedItems.getCompound(0).getCompound("components");
        assertEquals(-1, encoded.getInt("minecraft:map_id"));
        CompoundTag remainder = encodedItems.getCompound(1).getCompound("components").getCompound("minecraft:use_remainder");
        CompoundTag loadedBox = remainder.getCompound("components").getList("minecraft:charged_projectiles").getCompound(0);
        ListTag slots = loadedBox.getCompound("components").getList("minecraft:container");
        assertEquals(1, slots.getCompound(0).getInt("slot"));
        assertEquals(3, slots.getCompound(1).getInt("slot"));
        ListTag bundled = slots.getCompound(0).getCompound("item").getCompound("components").getList("minecraft:bundle_contents");
        assertEquals(-1, bundled.getCompound(0).getCompound("components").getInt("minecraft:map_id"));
        assertEquals(-1, bundled.getCompound(1).getCompound("components").getInt("minecraft:map_id"));
        assertEquals(-1, slots.getCompound(1).getCompound("item").getCompound("components").getInt("minecraft:map_id"));
        assertEquals(1, map.get(DataComponents.MAP_ID).id());
        assertNull(map.get(DataComponents.CUSTOM_DATA));
        assertEquals(1, ((CompoundTag) snapshot.data(InventoryDataType.INVENTORY)).getList("items").getCompound(0).getCompound("components").getInt("minecraft:map_id"));
        publisher.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "malformed", "empty-namespace", "unrelated"})
    void encodedSourceMarkersPreserveCustomDataAndControlPublication(String kind) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putByteArray("payload", new byte[65536]);
        tag.putString("other-plugin", "retained");
        if (kind.equals("malformed")) {
            tag.putString("sparrow-sync", "broken");
        } else if (!kind.equals("unrelated")) {
            net.minecraft.nbt.CompoundTag origin = new net.minecraft.nbt.CompoundTag();
            if (kind.equals("foreign")) {
                origin.putString("map-type", "SYNC");
                origin.putString("origin-server", "other");
                origin.putInt("origin-id", 1);
            }
            tag.put("sparrow-sync", origin);
        }
        CustomData custom = CustomData.of(tag);
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.CUSTOM_DATA, custom);
        map.set(DataComponents.MAP_ID, new MapId(kind.equals("foreign") ? -1 : 1));
        this.level.setMapData(new MapId(1), MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD));
        DataRegistry registry = new DataRegistry();
        registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        MapFlowTestSupport.Storage database = new MapFlowTestSupport.Storage();
        MapPublisher publisher = new MapPublisher(database, new MapFlowTestSupport.Shared(), "A-world", Runnable::run);
        MapSyncService service = this.service("A-world", registry, publisher);
        Snapshot snapshot = this.encodedItems(map);
        Snapshot compiled = service.compileAsync(snapshot, MapType.SYNC, snapshot.meta().player().toString()).join();
        assertEquals(kind.equals("foreign") || kind.equals("malformed") ? 0 : 1, database.registrations);
        CompoundTag encoded = ((CompoundTag) compiled.data(InventoryDataType.INVENTORY)).getList("items").getCompound(0).getCompound("components").getCompound("minecraft:custom_data");
        assertEquals("retained", encoded.getString("other-plugin"));
        assertEquals(65536, encoded.getByteArray("payload").length);
        assertSame(custom, map.get(DataComponents.CUSTOM_DATA));
        assertEquals(tag, custom.copyTag());
        if (kind.equals("foreign") || kind.equals("malformed")) {
            assertSame(snapshot, compiled);
        }
        publisher.close();
    }

    private Snapshot encodedItems(ItemStack... items) {
        CompoundTag inventory = NBT.createCompound();
        inventory.put("items", ItemCodec.saveItems(items));
        return new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1, SaveCause.WORLD_SAVE, false, "A", VersionHelper.WORLD_VERSION), Map.of(InventoryDataType.INVENTORY, inventory));
    }
    private MapSyncService service(String owner, DataRegistry registry, MapPublisher publisher) {
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", MapFlowTestSupport.logger(new ArrayList<>()));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataRegistry", registry);
        MapSyncService service = NmsPlayerFixture.allocate(MapSyncService.class);
        NmsPlayerFixture.set(MapSyncService.class, service, "plugin", plugin);
        NmsPlayerFixture.set(MapSyncService.class, service, "ownerId", owner);
        NmsPlayerFixture.set(MapSyncService.class, service, "nativeMaps", this.adapter);
        NmsPlayerFixture.set(MapSyncService.class, service, "nativeStorage", this.nativeStorage);
        NmsPlayerFixture.set(MapSyncService.class, service, "publisher", publisher);
        MapReceiver receiver = new MapReceiver(new MapFlowTestSupport.Storage(), new MapFlowTestSupport.Shared(), this.adapter, this.level.getServer(), owner, plugin.logger());
        NmsPlayerFixture.set(MapSyncService.class, service, "receiver", receiver);
        NmsPlayerFixture.set(MapSyncService.class, service, "pipeline", new MapPipeline(registry, List.of(new HideMapHandler(), new SyncMapHandler(receiver)), plugin.logger()));
        return service;
    }

    private PlayerDataPipeline.CaptureResult.Ready capture(CraftPlayer player, DataRegistry registry) {
        DataRegistry layout = new DataRegistry();
        registry.types().forEach(layout::register);
        layout.freeze();
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataRegistry", layout);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", MapFlowTestSupport.logger(new ArrayList<>()));
        PlayerDataPipeline pipeline = new PlayerDataPipeline(plugin);
        pipeline.onLoad();
        return assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, pipeline.capture(player, CaptureMode.SYNC));
    }

    private CompletableFuture<Integer> receive(String ownerId, StoredMap map) {
        MapFlowTestSupport.Storage storage = new MapFlowTestSupport.Storage();
        storage.current = map;
        MapReceiver receiver = new MapReceiver(storage, new MapFlowTestSupport.Shared(), this.adapter, this.level.getServer(), ownerId, MapFlowTestSupport.logger(new ArrayList<>()));
        MapFlowTestSupport.scheduler(Runnable::run, Runnable::run);
        return receiver.receive(map.identity());
    }

    @Test
    void cachePreparesExistingAndNewMapsWithoutReplacingNativeObjects() {
        MapItemSavedData existing = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        MapItemSavedDataProxy proxy = MapItemSavedDataProxy.INSTANCE;
        MapBanner banner = new MapBanner(BlockPos.ZERO, DyeColor.RED, Optional.empty());
        MapFrame frame = new MapFrame(BlockPos.ZERO, 0, 1);
        proxy.getBannerMarkers(existing).put(banner.getId(), banner);
        proxy.getFrameMarkers(existing).put(frame.getId(), frame);
        Object view = existing.mapView;
        byte[] pixels = existing.colors;
        Map<Object, Optional<?>> original = new HashMap<>();
        original.put(proxy.type(new MapId(1)), Optional.of(existing));
        original.put(proxy.type(new MapId(99)), Optional.empty());
        SavedDataStorageProxy.INSTANCE.setCache(this.storage, original);
        this.nativeStorage = new NativeMapStorage(this.level.getServer(), VersionHelper.WORLD_VERSION);
        assertInstanceOf(ConcurrentMap.class, this.storage.cache);
        assertSame(existing, this.nativeStorage.cached(1));
        assertSame(view, existing.mapView);
        assertSame(pixels, existing.colors);
        assertEquals(banner, proxy.getBannerMarkers(existing).get(banner.getId()));
        assertEquals(frame, proxy.getFrameMarkers(existing).get(frame.getId()));
        assertEquals(Optional.empty(), this.storage.cache.get(MapItemSavedData.type(new MapId(99))));
        MapItemSavedData fresh = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        this.level.setMapData(new MapId(2), fresh);
        assertSame(fresh, this.nativeStorage.cached(2));
        assertInstanceOf(ConcurrentMap.class, proxy.getBannerMarkers(fresh));
        assertInstanceOf(ConcurrentMap.class, proxy.getFrameMarkers(fresh));
        var iterator = proxy.getBannerMarkers(existing).values().iterator();
        assertEquals(banner, iterator.next());
        iterator.remove();
        assertTrue(proxy.getBannerMarkers(existing).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void uncachedMapReadsFileWithoutLoadingNativeMapOrFiringEvents(boolean compressed) throws Exception {
        MapItemSavedData source = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        source.colors[0] = 36;
        this.level.setMapData(new MapId(1), source);
        this.storage.saveAndJoin();
        Path path = this.directory.resolve("map_1.dat");
        if (!compressed) {
            NbtIo.write(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()), path);
        }
        this.storage.cache.clear();
        int events = this.pluginManager.events;
        MapData data = CompletableFuture.supplyAsync(() -> {
            try {
                return this.adapter.capture(this.nativeStorage, 1);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(36, data.getTag().getByteArray("colors")[0]);
        assertTrue(this.storage.cache.isEmpty());
        assertEquals(events, this.pluginManager.events);
        this.level.setMapData(new MapId(1), source);
        source.colors[0] = 49;
        assertEquals(49, this.adapter.capture(this.nativeStorage, 1).getTag().getByteArray("colors")[0]);
        assertEquals(36, data.getTag().getByteArray("colors")[0]);
    }

    @Test
    void corruptMapFileFailsWithoutPoisoningNativeCache() throws Exception {
        Files.write(this.directory.resolve("map_3.dat"), new byte[]{10, 0});
        assertThrows(IOException.class, () -> this.adapter.capture(this.nativeStorage, 3));
        assertTrue(this.storage.cache.isEmpty());
    }

    @Test
    void asyncCodecToleratesConcurrentNativeMarkersAndPixelUpdates() throws Exception {
        MapItemSavedData source = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        this.level.setMapData(new MapId(1), source);
        MapItemSavedDataProxy proxy = MapItemSavedDataProxy.INSTANCE;
        Map<String, MapBanner> banners = proxy.getBannerMarkers(source);
        Map<String, MapFrame> frames = proxy.getFrameMarkers(source);
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            started.countDown();
            int i = 0;
            while (running.get()) {
                BlockPos pos = new BlockPos(i & 31, 64, 0);
                MapBanner banner = new MapBanner(pos, DyeColor.BLUE, Optional.empty());
                MapFrame frame = new MapFrame(pos, 0, i & 31);
                banners.put(banner.getId(), banner);
                frames.put(frame.getId(), frame);
                source.setColor(i & 127, 0, (byte) i++);
                banners.remove(banner.getId());
                frames.remove(frame.getId());
            }
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 200; i++) {
                MapData captured = this.adapter.capture(this.nativeStorage, 1);
                assertEquals(MapData.PIXEL_COUNT, captured.getTag().getByteArray("colors").length);
            }
        } finally {
            running.set(false);
            writer.get(5, TimeUnit.SECONDS);
        }
        assertSame(source, this.nativeStorage.cached(1));
    }

    @Test
    void nativeStoragePersistsNegativeIdAndReloadsWithoutAdapterUpdates() throws Exception {
        MapData content = new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(17));
        MapItemSavedData prepared = this.adapter.prepareReplica(this.identity, content);
        assertSame(prepared, this.adapter.updateReplica(this.level, this.identity, prepared));
        this.storage.saveAndJoin();
        assertTrue(Files.exists(this.directory.resolve("map_-1.dat")));
        this.storage.cache.clear();
        MapItemSavedData loaded = this.level.getMapData(new MapId(-1));
        assertNotNull(loaded);
        assertNotSame(prepared, loaded);
        assertEquals(-1, loaded.id.id());
        assertEquals(17, loaded.colors[0]);
        assertEquals(prepared.dimension, loaded.dimension);
        assertEquals(prepared.centerX, loaded.centerX);
        assertNull(this.adapter.capture(this.nativeStorage, 12345));
    }

    @Test
    void nativeMapPacketsRefreshMultipleViewersAndRemainReadableAfterReload() throws Exception {
        MapItemSavedData replica = this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(11)));
        this.adapter.updateReplica(this.level, this.identity, replica);
        CraftPlayer handheldViewer = NmsPlayerFixture.create();
        CraftPlayer frameViewer = NmsPlayerFixture.create();
        handheldViewer.getHandle().setId(1);
        frameViewer.getHandle().setId(2);
        replica.getHoldingPlayer(handheldViewer.getHandle());
        replica.getHoldingPlayer(frameViewer.getHandle());
        ClientboundMapItemDataPacket first = (ClientboundMapItemDataPacket) replica.getUpdatePacket(new MapId(-1), handheldViewer.getHandle());
        replica.getUpdatePacket(new MapId(-1), frameViewer.getHandle());
        MapItemSavedData client = MapItemSavedData.createForClient((byte) 2, false, Level.OVERWORLD);
        first.applyToMap(client);
        assertEquals(11, client.colors[0]);
        Object view = replica.mapView;
        MapItemSavedData updated = this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(29)));
        this.adapter.updateReplica(this.level, this.identity, updated);
        for (CraftPlayer viewer : List.of(handheldViewer, frameViewer)) {
            ClientboundMapItemDataPacket packet = (ClientboundMapItemDataPacket) replica.getUpdatePacket(new MapId(-1), viewer.getHandle());
            assertNotNull(packet);
            assertTrue(packet.colorPatch().isPresent());
            assertEquals(1, packet.colorPatch().orElseThrow().mapColors().length);
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                ClientboundMapItemDataPacket.STREAM_CODEC.encode(buffer, packet);
                ClientboundMapItemDataPacket decoded = ClientboundMapItemDataPacket.STREAM_CODEC.decode(buffer);
                assertEquals(-1, decoded.mapId().id());
                decoded.applyToMap(client);
                assertEquals(29, client.colors[0]);
            } finally {
                buffer.release();
            }
        }
        assertSame(view, replica.mapView);
        assertEquals(1, replica.mapView.getRenderers().size());
        this.storage.saveAndJoin();
        this.storage.cache.clear();
        assertEquals(this.identity.replicaDimension(), MapFlowTestSupport.dimension(this.level.getMapData(new MapId(-1))));
        MapItemSavedData reloaded = this.level.getMapData(new MapId(-1));
        assertEquals(29, reloaded.colors[0]);
        reloaded.getHoldingPlayer(handheldViewer.getHandle());
        assertNotNull(reloaded.getUpdatePacket(new MapId(-1), handheldViewer.getHandle()));
        assertNull(this.level.getMapData(new MapId(1)));
    }

    @Test
    void unchangedReplicaDoesNotDirtyTheFileOrResendPixels() throws Exception {
        MapData data = new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(11));
        MapItemSavedData replica = this.adapter.updateReplica(this.level, this.identity, this.adapter.prepareReplica(this.identity, data));
        CraftPlayer viewer = this.viewer(replica, 1);
        Object view = replica.mapView;
        byte[] pixels = replica.colors;
        replica.setDirty(false);
        this.adapter.updateReplica(this.level, this.identity, this.adapter.prepareReplica(this.identity, data));
        assertSame(view, replica.mapView);
        assertSame(pixels, replica.colors);
        assertFalse(replica.isDirty());
        for (int i = 0; i < 6; i++) {
            assertNull(replica.getUpdatePacket(new MapId(-1), viewer.getHandle()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "rectangle", "last-pixel", "full"})
    void pixelUpdatesUseTheSmallestBoundingRectangleForEachViewer(String shape) throws Exception {
        MapData data = new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(11));
        MapItemSavedData replica = this.adapter.updateReplica(this.level, this.identity, this.adapter.prepareReplica(this.identity, data));
        CraftPlayer first = this.viewer(replica, 1);
        CraftPlayer second = this.viewer(replica, 2);
        MapItemSavedData next = this.adapter.prepareReplica(this.identity, data);
        int x = shape.equals("last-pixel") ? 127 : shape.equals("full") ? 0 : 3;
        int y = shape.equals("last-pixel") ? 127 : shape.equals("full") ? 0 : 4;
        int width = shape.equals("full") ? 128 : shape.equals("rectangle") ? 6 : 1;
        int height = shape.equals("full") ? 128 : shape.equals("rectangle") ? 7 : 1;
        if (shape.equals("full")) {
            Arrays.fill(next.colors, (byte) 29);
        } else {
            next.colors[x + y * 128] = 29;
            next.colors[x + width - 1 + (y + height - 1) * 128] = 29;
        }
        replica.setDirty(false);
        this.adapter.updateReplica(this.level, this.identity, next);
        assertTrue(replica.isDirty());
        for (CraftPlayer viewer : List.of(first, second)) {
            ClientboundMapItemDataPacket packet = (ClientboundMapItemDataPacket) replica.getUpdatePacket(new MapId(-1), viewer.getHandle());
            MapItemSavedData.MapPatch patch = packet.colorPatch().orElseThrow();
            assertEquals(x, patch.startX());
            assertEquals(y, patch.startY());
            assertEquals(width, patch.width());
            assertEquals(height, patch.height());
            assertEquals(width * height, patch.mapColors().length);
        }
        assertArrayEquals(next.colors, replica.colors);
    }

    @Test
    void pixelRectangleIncludesChangesStillWaitingToBeSent() throws Exception {
        MapData data = new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(11));
        MapItemSavedData replica = this.adapter.updateReplica(this.level, this.identity, this.adapter.prepareReplica(this.identity, data));
        CraftPlayer viewer = this.viewer(replica, 1);
        replica.setColor(1, 2, (byte) 17);
        MapItemSavedData next = this.adapter.prepareReplica(this.identity, data);
        next.colors[1 + 2 * 128] = 17;
        next.colors[10 + 20 * 128] = 29;
        this.adapter.updateReplica(this.level, this.identity, next);
        ClientboundMapItemDataPacket packet = (ClientboundMapItemDataPacket) replica.getUpdatePacket(new MapId(-1), viewer.getHandle());
        MapItemSavedData.MapPatch patch = packet.colorPatch().orElseThrow();
        assertEquals(1, patch.startX());
        assertEquals(2, patch.startY());
        assertEquals(10, patch.width());
        assertEquals(19, patch.height());
    }

    @Test
    void metadataOnlyUpdatesPersistAndSendHeadersWithoutPixelPatches() throws Exception {
        MapData data = new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(11));
        MapItemSavedData replica = this.adapter.updateReplica(this.level, this.identity, this.adapter.prepareReplica(this.identity, data));
        CraftPlayer viewer = this.viewer(replica, 1);
        replica.setDirty(false);
        MapItemSavedData next = this.adapter.prepareReplica(this.identity, data);
        next.scale = 3;
        next.locked = true;
        next.centerX = 128;
        next.centerZ = -256;
        next.trackingPosition = false;
        next.unlimitedTracking = true;
        this.adapter.updateReplica(this.level, this.identity, next);
        assertTrue(replica.isDirty());
        ClientboundMapItemDataPacket packet = this.decorationPacket(replica, viewer);
        assertEquals(3, packet.scale());
        assertTrue(packet.locked());
        assertTrue(packet.colorPatch().isEmpty());
        this.storage.saveAndJoin();
        this.storage.cache.clear();
        MapItemSavedData reloaded = this.level.getMapData(new MapId(-1));
        assertEquals(3, reloaded.scale);
        assertTrue(reloaded.locked);
        assertEquals(128, reloaded.centerX);
        assertEquals(-256, reloaded.centerZ);
        assertFalse(reloaded.trackingPosition);
        assertTrue(reloaded.unlimitedTracking);
        assertArrayEquals(next.colors, reloaded.colors);
    }

    @Test
    void bannerOnlyUpdatesPreserveLocalDecorationsAndRepairBannerIcons() throws Exception {
        MapData data = new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(11));
        MapItemSavedData replica = this.adapter.updateReplica(this.level, this.identity, this.adapter.prepareReplica(this.identity, data));
        CraftPlayer viewer = this.viewer(replica, 1);
        MapDecoration frame = new MapDecoration(MapDecorationTypes.FRAME, (byte) 1, (byte) 2, (byte) 0, Optional.empty());
        replica.decorations.put("frame-42", frame);
        CompoundTag tag = data.getTag();
        CompoundTag banner = NBT.createCompound();
        banner.putIntArray("pos", new int[]{64, 64, -128});
        banner.putString("color", "red");
        ListTag banners = NBT.createList();
        banners.add(banner);
        tag.put("banners", banners);
        MapData withBanner = new MapData(VersionHelper.WORLD_VERSION, tag);
        replica.setDirty(false);
        MapItemSavedData next = this.adapter.prepareReplica(this.identity, withBanner);
        this.adapter.updateReplica(this.level, this.identity, next);
        assertTrue(replica.isDirty());
        assertEquals(1, MapItemSavedDataProxy.INSTANCE.getBannerMarkers(replica).size());
        assertSame(frame, replica.decorations.get("frame-42"));
        assertTrue(this.decorationPacket(replica, viewer).colorPatch().isEmpty());
        String key = next.decorations.keySet().iterator().next();
        replica.decorations.put(key, frame);
        this.adapter.updateReplica(this.level, this.identity, this.adapter.prepareReplica(this.identity, withBanner));
        assertEquals(next.decorations.get(key), replica.decorations.get(key));
        assertTrue(this.decorationPacket(replica, viewer).colorPatch().isEmpty());
        replica.setDirty(false);
        this.adapter.updateReplica(this.level, this.identity, this.adapter.prepareReplica(this.identity, data));
        assertTrue(replica.isDirty());
        assertTrue(MapItemSavedDataProxy.INSTANCE.getBannerMarkers(replica).isEmpty());
        assertFalse(replica.decorations.containsKey(key));
        assertSame(frame, replica.decorations.get("frame-42"));
        assertTrue(this.decorationPacket(replica, viewer).colorPatch().isEmpty());
        this.storage.saveAndJoin();
        this.storage.cache.clear();
        assertTrue(MapItemSavedDataProxy.INSTANCE.getBannerMarkers(this.level.getMapData(new MapId(-1))).isEmpty());
    }

    private CraftPlayer viewer(MapItemSavedData replica, int entityId) {
        CraftPlayer viewer = NmsPlayerFixture.create();
        viewer.getHandle().setId(entityId);
        replica.getHoldingPlayer(viewer.getHandle());
        assertNotNull(replica.getUpdatePacket(new MapId(-1), viewer.getHandle()));
        return viewer;
    }

    private ClientboundMapItemDataPacket decorationPacket(MapItemSavedData replica, CraftPlayer viewer) {
        for (int i = 0; i < 6; i++) {
            ClientboundMapItemDataPacket packet = (ClientboundMapItemDataPacket) replica.getUpdatePacket(new MapId(-1), viewer.getHandle());
            if (packet != null) return packet;
        }
        throw new AssertionError("native decoration update was not sent");
    }

    @Test
    void updatePreservesViewPixelsAndLocalDecorations() throws Exception {
        MapItemSavedData prepared = this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(4)));
        MapItemSavedData installed = this.adapter.updateReplica(this.level, this.identity, prepared);
        Object view = installed.mapView;
        byte[] pixels = installed.colors;
        MapDecoration frame = new MapDecoration(MapDecorationTypes.FRAME, (byte) 1, (byte) 2, (byte) 0, Optional.empty());
        installed.decorations.put("frame-42", frame);
        installed.setDirty(false);
        MapItemSavedData next = this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(5)));
        assertSame(installed, this.adapter.updateReplica(this.level, this.identity, next));
        assertSame(view, installed.mapView);
        assertSame(pixels, installed.colors);
        assertEquals(5, installed.colors[0]);
        assertSame(frame, installed.decorations.get("frame-42"));
        assertTrue(installed.isDirty());
    }

    @Test
    void upgradesLegacyBannerNamesUsingTheSavedDataEnvelope() throws Exception {
        CompoundTag tag = MapDataTest.content(12);
        ListTag banners = NBT.createList();
        CompoundTag banner = NBT.createCompound();
        banner.putIntArray("pos", new int[]{64, 64, -128});
        banner.putString("color", "red");
        banner.putString("name", "{\"text\":\"Legacy\"}");
        banners.add(banner);
        tag.put("banners", banners);
        MapItemSavedData replica = this.adapter.prepareReplica(this.identity, new MapData(4189, tag));
        MapBanner restored = MapItemSavedDataProxy.INSTANCE.getBannerMarkers(replica).values().iterator().next();
        assertEquals("Legacy", restored.name().orElseThrow().getString());
        assertEquals(12, replica.colors[0]);
    }

    @Test
    void replacesLocalReplicaIdentityButRejectsFutureData() throws Exception {
        MapItemSavedData original = MapItemSavedData.createFresh(0, 0, (byte) 0, false, false, Level.OVERWORLD);
        this.level.setMapData(new MapId(-1), original);
        MapItemSavedData prepared = this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(6)));
        assertSame(original, this.adapter.updateReplica(this.level, this.identity, prepared));
        assertSame(original, this.level.getMapData(new MapId(-1)));
        assertEquals(this.identity.replicaDimension(), MapFlowTestSupport.dimension(this.level.getMapData(new MapId(-1))));
        assertEquals(6, original.colors[0]);
        assertThrows(IOException.class, () -> this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION + 1, MapDataTest.content(6))));
    }

    @Test
    void sourceReturnRepairsNegativeReplicaAndPreservesPositiveOriginal() {
        MapItemSavedData source = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        source.colors[0] = 90;
        this.level.setMapData(new MapId(1), source);
        MapItemSavedData replica = MapItemSavedData.createForClient((byte) 0, false, Level.OVERWORLD);
        replica.colors[0] = 10;
        this.level.setMapData(new MapId(-1), replica);
        StoredMap published = new StoredMap(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(20)));
        assertEquals(1, this.receive(this.identity.source().ownerId(), published).join());
        assertSame(source, this.level.getMapData(new MapId(1)));
        assertEquals(90, source.colors[0]);
        assertSame(replica, this.level.getMapData(new MapId(-1)));
        assertEquals(20, replica.colors[0]);
        assertEquals(this.identity.replicaDimension(), MapFlowTestSupport.dimension(this.level.getMapData(new MapId(-1))));
    }

    private static Object replaceStatic(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }

    private static final class TestPluginManager extends PaperPluginManagerImpl {
        private int events;
        private TestPluginManager() {
            super(null, null, null);
        }

        @Override
        public void callEvent(Event event) {
            this.events++;
        }
    }
}
