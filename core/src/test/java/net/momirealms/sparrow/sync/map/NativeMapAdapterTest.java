package net.momirealms.sparrow.sync.map;

import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.saveddata.maps.MapItemSavedDataProxy;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.util.VersionHelper;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

class NativeMapAdapterTest {
    private static HolderLookup.Provider registries;
    @TempDir
    Path directory;
    private Object previousMinecraft;
    private Object previousBukkit;
    private Object previousConfig;
    private Object previousServerConfig;
    private DimensionDataStorage storage;
    private ServerLevel level;
    private NativeMapAdapter adapter;
    private final MapIdentity identity = new MapIdentity("集群", new MapSource("A-world", 1), -1);

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
        this.previousConfig = replaceStatic(PluginConfig.class, "config", new PluginConfig.ConfigDefinition());
        this.previousServerConfig = replaceStatic(ServerConfig.class, "config", new ServerConfig.ConfigDefinition());
        DedicatedServer server = NmsPlayerFixture.allocate(DedicatedServer.class);
        this.previousMinecraft = replaceStatic(MinecraftServer.class, "SERVER", server);
        CraftServer craft = NmsPlayerFixture.allocate(CraftServer.class);
        SimplePluginManager manager = NmsPlayerFixture.allocate(SimplePluginManager.class);
        manager.paperPluginManager = NmsPlayerFixture.allocate(TestPluginManager.class);
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
    }

    @AfterEach
    void restore() throws Exception {
        try {
            if (this.storage != null) {
                this.storage.close();
            }
        } finally {
            replaceStatic(MinecraftServer.class, "SERVER", this.previousMinecraft);
            replaceStatic(Bukkit.class, "server", this.previousBukkit);
            replaceStatic(PluginConfig.class, "config", this.previousConfig);
            replaceStatic(ServerConfig.class, "config", this.previousServerConfig);
        }
    }

    @Test
    void syncReturnPreservesCurrentOriginalAndMissingOrChangedOwnerKeepsNegativeReplica() throws Exception {
        MapSyncService service = this.service("A-world", new DataRegistry(), null);
        MapItemSavedData source = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        source.colors[0] = 90;
        this.level.setMapData(new MapId(1), source);
        StoredMap old = new StoredMap(MapFlowTestSupport.IDENTITY, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(10)));
        assertEquals(1, this.receipt(service, old).getAsInt());
        assertSame(source, this.level.getMapData(new MapId(1)));
        assertEquals(90, source.colors[0]);
        assertNull(this.level.getMapData(new MapId(-1)));

        this.storage.cache.clear();
        assertEquals(-1, this.receipt(service, old).getAsInt());
        assertEquals(10, this.level.getMapData(new MapId(-1)).colors[0]);
        assertNull(this.level.getMapData(new MapId(1)));
        this.level.setMapData(new MapId(1), source);
        MapSyncService changedOwner = this.service("B-world", new DataRegistry(), null);
        assertEquals(-1, this.receipt(changedOwner, old).getAsInt());
        assertEquals(90, source.colors[0]);
        this.storage.saveAndJoin();
        assertTrue(Files.exists(this.directory.resolve("map_-1.dat")));
    }

    @Test
    void playerSaveCapturesOnlyEnabledScopesAndDeduplicatesNestedCopiesWithoutMutatingItems() throws Exception {
        DataRegistry registry = new DataRegistry();
        registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        MapFlowTestSupport.Storage database = new MapFlowTestSupport.Storage();
        MapFlowTestSupport.Tasks worker = new MapFlowTestSupport.Tasks();
        MapPublisher publisher = new MapPublisher(() -> CompletableFuture.completedFuture(database), new MapFlowTestSupport.Shared(), worker);
        MapSyncService service = this.service("A-world", registry, publisher);
        CraftPlayer player = NmsPlayerFixture.create();
        Inventory inventory = new Inventory(player.getHandle(), new EntityEquipment());
        PlayerEnderChestContainer ender = new PlayerEnderChestContainer(player.getHandle());
        NmsPlayerFixture.set(net.minecraft.world.entity.player.Player.class, player.getHandle(), "inventory", inventory);
        NmsPlayerFixture.set(net.minecraft.world.entity.player.Player.class, player.getHandle(), "enderChestInventory", ender);
        MapItemSavedData source = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        source.colors[0] = 12;
        this.level.setMapData(new MapId(1), source);
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, new MapId(1));
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(map.copy(), map.copy())));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(bundle)));
        inventory.setItem(0, map);
        inventory.setItem(1, box);
        ItemStack other = map.copy();
        other.set(DataComponents.MAP_ID, new MapId(2));
        ender.setItem(0, other);
        Map<Integer, CompletableFuture<StoredMap>> captured = service.capture(player, MapType.SYNC);
        assertEquals(java.util.Set.of(1), captured.keySet());
        source.colors[0] = 15;
        worker.runAll();
        assertEquals(12, captured.get(1).join().data().getTag().getByteArray("colors")[0]);
        assertEquals(1, database.registrations);
        assertEquals(1, map.get(DataComponents.MAP_ID).id());
        assertNull(map.get(DataComponents.CUSTOM_DATA));
        assertTrue(service.capture(player, MapType.HIDE).isEmpty());
        inventory.clearContent();
        assertTrue(service.capture(player, MapType.SYNC).isEmpty());
        registry.register(NmsPlayerFixture.allocate(EnderChestDataType.class));
        assertTrue(service.capture(player, MapType.SYNC).get(2).isCompletedExceptionally());

        net.minecraft.nbt.CompoundTag marker = new net.minecraft.nbt.CompoundTag();
        marker.putString("map-type", "SYNC");
        marker.putString("origin-server", "foreign");
        marker.putInt("origin-id", 1);
        net.minecraft.nbt.CompoundTag custom = new net.minecraft.nbt.CompoundTag();
        custom.put("sparrow-sync", marker);
        map.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        map.set(DataComponents.MAP_ID, new MapId(-1));
        ender.clearContent();
        inventory.setItem(0, map);
        assertTrue(service.capture(player, MapType.SYNC).isEmpty());
        assertEquals(1, database.registrations);
    }

    private MapSyncService service(String owner, DataRegistry registry, MapPublisher publisher) {
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, PluginConfig.synchronization$map(), "enabled", true);
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, PluginConfig.synchronization$map(), "mapOwnerId", owner);
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "logger", MapFlowTestSupport.logger(new ArrayList<>()));
        NmsPlayerFixture.set(SparrowSync.class, plugin, "dataRegistry", registry);
        MapSyncService service = NmsPlayerFixture.allocate(MapSyncService.class);
        NmsPlayerFixture.set(MapSyncService.class, service, "plugin", plugin);
        NmsPlayerFixture.set(MapSyncService.class, service, "ownerId", owner);
        NmsPlayerFixture.set(MapSyncService.class, service, "worldUuid", UUID.randomUUID());
        NmsPlayerFixture.set(MapSyncService.class, service, "level", this.level);
        NmsPlayerFixture.set(MapSyncService.class, service, "nativeMaps", this.adapter);
        NmsPlayerFixture.set(MapSyncService.class, service, "publisher", publisher);
        return service;
    }

    private IntSupplier receipt(MapSyncService service, StoredMap map) throws Exception {
        Method prepare = MapSyncService.class.getDeclaredMethod("prepareReplica", StoredMap.class);
        prepare.setAccessible(true);
        return (IntSupplier) prepare.invoke(service, map);
    }

    @Test
    void capturedPixelsAndBannersAreIndependentAndReplicaHasAnUnknownDimension() throws Exception {
        MapItemSavedData original = MapItemSavedData.createFresh(300, -200, (byte) 2, true, true, Level.OVERWORLD);
        original.colors[0] = 24;
        MapBanner banner = new MapBanner(new BlockPos(300, 64, -200), DyeColor.RED, Optional.empty());
        MapItemSavedDataProxy.INSTANCE.getBannerMarkers(original).put(banner.getId(), banner);
        MapData captured = this.adapter.capture(original);
        original.colors[0] = 30;
        MapItemSavedDataProxy.INSTANCE.getBannerMarkers(original).clear();
        MapItemSavedData replica = this.adapter.prepareReplica(this.identity, captured);
        assertEquals(24, replica.colors[0]);
        assertEquals(1, MapItemSavedDataProxy.INSTANCE.getBannerMarkers(replica).size());
        assertEquals(original.centerX, replica.centerX);
        assertEquals(original.centerZ, replica.centerZ);
        assertEquals(original.scale, replica.scale);
        assertNotEquals(Level.OVERWORLD, replica.dimension);
        assertEquals("minecraft:overworld", captured.getTag().getString("dimension"));
    }

    @Test
    void nativeStoragePersistsNegativeIdAndReloadsWithoutAdapterInstallation() throws Exception {
        MapData content = new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(17));
        MapItemSavedData prepared = this.adapter.prepareReplica(this.identity, content);
        assertSame(prepared, this.adapter.installReplica(this.level, this.identity, prepared));
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
        assertNull(this.adapter.capture(this.level, 12345));
    }

    @Test
    void updatePreservesViewPixelsAndLocalDecorations() throws Exception {
        MapItemSavedData prepared = this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(4)));
        MapItemSavedData installed = this.adapter.installReplica(this.level, this.identity, prepared);
        Object view = installed.mapView;
        byte[] pixels = installed.colors;
        MapDecoration frame = new MapDecoration(MapDecorationTypes.FRAME, (byte) 1, (byte) 2, (byte) 0, Optional.empty());
        installed.decorations.put("frame-42", frame);
        installed.setDirty(false);
        MapItemSavedData next = this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(5)));
        assertSame(installed, this.adapter.installReplica(this.level, this.identity, next));
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
    void refusesForeignNativeCollisionAndFutureData() throws Exception {
        MapItemSavedData original = MapItemSavedData.createFresh(0, 0, (byte) 0, false, false, Level.OVERWORLD);
        this.level.setMapData(new MapId(-1), original);
        MapItemSavedData prepared = this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION, MapDataTest.content(6)));
        assertThrows(IllegalStateException.class, () -> this.adapter.installReplica(this.level, this.identity, prepared));
        assertSame(original, this.level.getMapData(new MapId(-1)));
        assertThrows(IOException.class, () -> this.adapter.prepareReplica(this.identity, new MapData(VersionHelper.WORLD_VERSION + 1, MapDataTest.content(6))));
    }

    private static Object replaceStatic(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }

    private static final class TestPluginManager extends PaperPluginManagerImpl {
        private TestPluginManager() {
            super(null, null, null);
        }

        @Override
        public void callEvent(Event event) {
        }
    }
}
