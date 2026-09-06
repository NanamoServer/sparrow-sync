package net.momirealms.sparrow.sync.map;

import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.DyeColor;
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
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NativeMapAdapterTest {
    private static HolderLookup.Provider registries;
    @TempDir
    Path directory;
    private Object previousMinecraft;
    private Object previousBukkit;
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
        }
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
