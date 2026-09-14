package net.momirealms.sparrow.sync.compatibility.migration.invsync;

import com.google.gson.Gson;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.compatibility.CompatibilityManager;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationDataTypes;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationAssertions;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotDecoder;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class InvSyncSourceTest {
    private final List<MigrationSource.PlayerData> accepted = new ArrayList<>();
    private final List<byte[]> rejected = new ArrayList<>();
    private final Map<Field, Object> previous = new LinkedHashMap<>();
    private DataRegistry registry;
    private Object storage;
    private Plugin hookedInvSync;
    private Thread decoderThread;
    private Thread sinkThread;
    private boolean interruptDecode;
    private final MigrationSource.Sink sink = new MigrationSource.Sink() {
        public void accept(MigrationSource.@NonNull PlayerData data) { sinkThread = Thread.currentThread(); accepted.add(data); }
        public void reject(@NonNull UUID player, String name, @NonNull String stage, @NonNull Throwable error, byte[] raw) { rejected.add(raw); }
    };

    @BeforeEach
    void setup() throws Exception {
        BukkitProxy.init("1.21.8", List.of("paper"));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        this.replace(CraftRegistry.class, "registry", RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        this.replace(MinecraftRegistryOps.class, "sparrowNbt", VanillaRegistries.createLookup().createSerializationContext(NBTOps.INSTANCE));
        this.replace(Bukkit.class, "server", Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUnsafe" -> CraftMagicNumbers.INSTANCE;
            case "getPluginManager" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{org.bukkit.plugin.PluginManager.class}, (manager, operation, arguments) -> switch (operation.getName()) {
                case "getPlugin" -> "InvSync".equals(arguments[0]) ? this.hookedInvSync : null;
                case "isPluginEnabled" -> "InvSync".equals(arguments[0]) && this.hookedInvSync != null;
                case "getPlugins" -> new Plugin[0];
                default -> throw new AssertionError(operation);
            });
            case "getAdvancement" -> null;
            case "getLogger" -> Logger.getAnonymousLogger();
            case "getVersion" -> "Paper (MC: 1.21.8)";
            case "getBukkitVersion" -> "1.21.8-R0.1-SNAPSHOT";
            default -> throw new AssertionError(method);
        }));
        this.replace(SparrowSync.class, "instance", NmsPlayerFixture.allocate(SparrowSync.class));
        this.registry = MigrationDataTypes.createRegistry();
    }

    private void replace(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        this.previous.put(field, field.get(null));
        field.set(null, value);
    }

    @AfterEach
    void restore() throws Exception {
        for (Map.Entry<Field, Object> entry : this.previous.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
        Thread.interrupted();
    }

    private Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class, SourceApi.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getStorageManager" -> this.storage;
            case "getGson" -> {
                this.decoderThread = Thread.currentThread();
                if (this.interruptDecode) throw new InterruptedException();
                yield new Gson();
            }
            default -> throw new AssertionError(method);
        });
    }

    @Test
    void disabledRuntimeTypesSurviveMigrationZip(@TempDir Path directory) throws Exception {
        DataRegistry runtime = new DataRegistry();
        runtime.register(new InventoryDataType());
        runtime.freeze();
        SourceData source = new SourceData();
        source.inventory = items(Map.of(0, "{id:'minecraft:diamond',count:3}"));
        this.storage = new MysqlManager(source);
        this.hookedInvSync = this.plugin();
        PluginLogger console = (PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> {
            if (!method.getName().equals("info")) {
                throw new AssertionError(Arrays.toString(args));
            }
            return null;
        });
        for (var entry : Map.of("dataRegistry", runtime, "logger", new SyncLogger(console)).entrySet()) {
            Field field = SparrowSync.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(SparrowSync.instance(), entry.getValue());
        }
        CompatibilityManager compatibility = new CompatibilityManager(SparrowSync.instance());
        compatibility.onDelayedEnable();
        MigrationSource migration = compatibility.invSyncMigration();
        assertNotNull(migration);
        migration.read(this.sink);
        assertTrue(this.rejected.isEmpty());
        assertEquals(1, this.accepted.size());
        this.storage = new MysqlManager(source);
        var snapshot = MigrationAssertions.assertZipRoundTrip(directory, migration, this.accepted.getFirst().data());
        var decoded = new SnapshotDecoder(runtime).decodeForApply(snapshot);
        assertNotNull(decoded.value(InventoryDataType.INVENTORY));
        for (var key : List.of(HealthDataType.HEALTH, AttributesDataType.ATTRIBUTES, HungerDataType.HUNGER, ExperienceDataType.EXPERIENCE)) {
            assertNotNull(snapshot.allData().get(key));
            assertNull(runtime.type(key));
            assertNull(decoded.value(key));
        }
        assertEquals(1, runtime.size());
    }

    @Test
    void mysqlReadsOnlyV2OneBodyAtATimeWithoutLegacyApi() throws Exception {
        MysqlManager mysql = new MysqlManager(new SourceData(), new SourceData());
        this.storage = mysql;
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        assertEquals(2, this.accepted.size());
        assertEquals(List.of("uuid"), mysql.playerDataDao.query.columns);
        assertTrue(mysql.playerDataDao.query.cursor.closed);
        assertTrue(mysql.playerUUIDDataDao.cursor.closed);
        assertEquals(0, this.rejected.size());
        assertNull(this.accepted.getFirst().user());
        assertNull(this.accepted.getFirst().timestamp());
        assertEquals(4, this.accepted.getFirst().data().size());
    }

    @Test
    void missingMemoryOnlyFieldsUseDocumentedDefaults() throws Exception {
        this.storage = new MysqlManager(new SourceData());
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        var data = this.accepted.getFirst().data();
        assertEquals(new HungerDataType.Hunger(13, 0, 0, 0), new HungerDataType().decode(data.get(HungerDataType.HUNGER)));
        assertEquals(new ExperienceDataType.Experience(0, 25, 0.75f), new ExperienceDataType().decode(data.get(ExperienceDataType.EXPERIENCE)));
        var attributes = new AttributesDataType().decode(data.get(AttributesDataType.ATTRIBUTES));
        assertEquals(1, attributes.values().length);
        assertEquals(36, attributes.values()[0].base());
    }

    @Test
    void readDecodeAndSinkStayOnTheMigrationWorker() throws Exception {
        SourceData source = new SourceData();
        source.inventory = items(Map.of(36, "{id:'minecraft:diamond_boots',count:1}", 39, "{id:'minecraft:diamond_helmet',count:1}", 40, "{id:'minecraft:shield',count:1}"));
        MysqlManager mysql = new MysqlManager(source);
        this.storage = mysql;
        Thread worker;
        try (var executor = Executors.newSingleThreadExecutor()) {
            worker = executor.submit(() -> {
                new InvSyncSource(this.plugin(), this.registry).read(this.sink);
                return Thread.currentThread();
            }).get();
        }
        var inventory = NmsPlayerFixture.allocate(InventoryDataType.class).decode(this.accepted.getFirst().data().get(InventoryDataType.INVENTORY));
        assertEquals(41, inventory.contents().length);
        assertTrue(inventory.contents()[36].is(Items.DIAMOND_BOOTS));
        assertTrue(inventory.contents()[39].is(Items.DIAMOND_HELMET));
        assertTrue(inventory.contents()[40].is(Items.SHIELD));
        assertEquals(0, inventory.heldSlot());
        assertNotEquals(Thread.currentThread(), this.decoderThread);
        assertSame(worker, mysql.readThread);
        assertSame(worker, this.decoderThread);
        assertSame(worker, this.sinkThread);
    }

    @Test
    void badPlayerIsArchivedAndNextPlayerContinues() throws Exception {
        SourceData bad = new SourceData();
        bad.inventory = new byte[]{-1};
        this.storage = new MysqlManager(bad, new SourceData());
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        assertEquals(1, this.accepted.size());
        assertEquals(1, this.rejected.size());
        assertTrue(new String(this.rejected.getFirst()).contains(bad.uuid));
    }

    @Test
    void additionalEquipmentSlotsAreRetained() throws Exception {
        SourceData source = new SourceData();
        source.inventory = items(Map.of(42, "{id:'minecraft:saddle',count:1}"));
        this.storage = new MysqlManager(source);
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        var inventory = NmsPlayerFixture.allocate(InventoryDataType.class).decode(this.accepted.getFirst().data().get(InventoryDataType.INVENTORY));
        assertEquals(43, inventory.contents().length);
        assertTrue(inventory.contents()[42].is(Items.SADDLE));
    }

    @Test
    void sinkFailureStopsBatchAndClosesCursor() {
        MysqlManager mysql = new MysqlManager(new SourceData(), new SourceData());
        this.storage = mysql;
        assertThrows(IOException.class, () -> new InvSyncSource(this.plugin(), this.registry).read(new MigrationSource.Sink() {
            public void accept(MigrationSource.@NonNull PlayerData data) throws IOException { throw new IOException("disk full"); }
            public void reject(@NonNull UUID player, String name, @NonNull String stage, @NonNull Throwable error, byte[] raw) { fail("sink failure must propagate"); }
        }));
        assertTrue(mysql.playerDataDao.query.cursor.closed);
        assertEquals(1, mysql.reads);
    }

    @Test
    void unavailableSourceMethodEndsBatch() {
        this.storage = new Object();
        assertThrows(UnsupportedOperationException.class, () -> new InvSyncSource(this.plugin(), this.registry).read(this.sink));
        assertThrows(LinkageError.class, () -> InvSyncAccess.call(new Object(), "missing"));
    }

    @Test
    void conversionInterruptionEndsBatchAndClosesCursor() {
        SourceData source = new SourceData();
        this.interruptDecode = true;
        MysqlManager mysql = new MysqlManager(source);
        this.storage = mysql;
        assertThrows(InterruptedException.class, () -> new InvSyncSource(this.plugin(), this.registry).read(this.sink));
        assertTrue(mysql.playerDataDao.query.cursor.closed);
        assertTrue(this.rejected.isEmpty());
    }

    @Test
    void interruptedReadClosesCursor() {
        MysqlManager mysql = new MysqlManager(new SourceData());
        this.storage = mysql;
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> new InvSyncSource(this.plugin(), this.registry).read(this.sink));
        assertTrue(mysql.playerUUIDDataDao.cursor.closed);
        assertEquals(0, mysql.reads);
    }

    @Test
    void mongoUsesCursorAndSourceMapper() throws Exception {
        MongoDBManager mongo = new MongoDBManager();
        this.storage = mongo;
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        assertEquals(1, this.accepted.size());
        assertEquals(1, mongo.playerDataCollection.batchSize);
        assertTrue(mongo.playerDataCollection.cursor.closed);
        assertEquals(1, mongo.mapped);
    }

    public interface SourceApi {
        Object getStorageManager();
        Gson getGson() throws Exception;
    }

    private static byte[] items(Map<Integer, String> entries) throws IOException {
        List<Map<String, Object>> values = new ArrayList<>();
        entries.forEach((slot, item) -> values.add(Map.of("slot", slot, "item", item)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(new Gson().toJson(values).getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    @Test
    void unregisteredEnchantmentsAndOriginalVersionsArePreserved() throws Exception {
        SourceData source = new SourceData();
        String item = "{id:'minecraft:enchanted_book',count:1,DataVersion:4671,components:{'minecraft:enchantments':{'minecraft:rejuvenation':3},'minecraft:stored_enchantments':{'minecraft:silence':2}}}";
        source.inventory = items(Map.of(0, item));
        source.enderChest = items(Map.of(5, item));
        this.storage = new MysqlManager(source);
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        assertTrue(this.rejected.isEmpty());
        for (var key : List.of(InventoryDataType.INVENTORY, EnderChestDataType.ENDER_CHEST)) {
            CompoundTag container = (CompoundTag) this.accepted.getFirst().data().get(key);
            CompoundTag migrated = container.getList("items").getCompound(0);
            assertEquals(4671, migrated.getInt("DataVersion"));
            assertEquals(3, migrated.getCompound("components").getCompound("minecraft:enchantments").getInt("minecraft:rejuvenation"));
            assertEquals(2, migrated.getCompound("components").getCompound("minecraft:stored_enchantments").getInt("minecraft:silence"));
        }
    }

    @Test
    void mysqlImportsNameWithUnknownLastSeenAndStableAliasChoice() throws Exception {
        SourceData source = new SourceData();
        MysqlManager mysql = new MysqlManager(source);
        mysql.playerUUIDDataDao = new Names(List.of(new Name("Zeta", source.uuid), new Name("Alpha", source.uuid)));
        this.storage = mysql;
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        assertEquals("Alpha", this.accepted.getFirst().user().name());
        assertEquals(UUID.fromString(source.uuid), this.accepted.getFirst().user().player());
        assertEquals(0, this.accepted.getFirst().user().lastSeen());
    }

    @Test
    void mongoImportsItsSeparateNameCollection() throws Exception {
        SourceData source = new SourceData();
        MongoDBManager mongo = new MongoDBManager();
        mongo.playerDataCollection = new Collection(List.of(source));
        mongo.uuidCollection = new Collection(List.of(new Name("Player", source.uuid)));
        this.storage = mongo;
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        assertEquals("Player", this.accepted.getFirst().user().name());
        assertEquals(0, this.accepted.getFirst().user().lastSeen());
        assertTrue(mongo.uuidCollection.cursor.closed);
    }

    public static class SourceData {
        final String uuid = UUID.randomUUID().toString();
        byte[] inventory;
        byte[] enderChest;
        public String getUuid() { return this.uuid; }
        public double getHealth() { return 12; }
        public double getMaxHealth() { return 36; }
        public double getFood() { return 13; }
        public int getLevel() { return 25; }
        public float getExp() { return 0.75f; }
        public boolean inventoryIsInit() { return this.inventory != null; }
        public byte[] getInventory() { return this.inventory; }
        public boolean enderChestIsInit() { return this.enderChest != null; }
        public byte[] getEnderChest() { return this.enderChest; }
        public boolean buffsIsInit() { return false; }
        public boolean statisticIsInit() { return false; }
        public boolean advancementsIsInit() { return false; }
        public boolean persistentDataIsInit() { return false; }
    }

    public static class MysqlManager {
        final Dao playerDataDao;
        Names playerUUIDDataDao = new Names(List.of());
        int reads;
        Thread readThread;
        MysqlManager(SourceData... data) { this.playerDataDao = new Dao(List.of(data)); }
        public SourceData getPlayerData(String uuid) { this.readThread = Thread.currentThread(); this.reads++; return this.playerDataDao.query.data.stream().filter(data -> data.uuid.equals(uuid)).findFirst().orElse(null); }
    }

    public record Name(String name, String uuid) {
        public String getName() { return this.name; }
        public String getUuid() { return this.uuid; }
        public String getString(String key) { return key.equals("_id") ? this.name : this.uuid; }
    }

    public static class Names {
        final Cursor cursor;
        Names(List<Name> names) { this.cursor = new Cursor(names.iterator()); }
        public Cursor iterator() { return this.cursor; }
    }

    public static class Dao {
        final Query query;
        Dao(List<SourceData> data) { this.query = new Query(data); }
        public Query queryBuilder() { return this.query; }
    }

    public static class Query {
        final List<SourceData> data;
        final Cursor cursor;
        List<String> columns;
        Query(List<SourceData> data) { this.data = data; this.cursor = new Cursor(data.iterator()); }
        public Query selectColumns(String... columns) { this.columns = List.of(columns); return this; }
        public Cursor iterator() { return this.cursor; }
    }

    public static class Cursor implements Iterator<Object>, AutoCloseable {
        final Iterator<?> iterator;
        boolean closed;
        Cursor(Iterator<?> iterator) { this.iterator = iterator; }
        public boolean hasNext() { return this.iterator.hasNext(); }
        public Object next() { return this.iterator.next(); }
        public void close() { this.closed = true; }
    }

    public static class MongoDBManager {
        Collection playerDataCollection = new Collection(List.of(new SourceData()));
        Collection uuidCollection = new Collection(List.of());
        int mapped;
        private SourceData documentToPlayerData(SourceData document) { this.mapped++; return document; }
    }

    public static class Collection {
        final Cursor cursor;
        int batchSize;
        Collection(List<?> values) { this.cursor = new Cursor(values.iterator()); }
        public Collection find() { return this; }
        public Collection batchSize(int size) { this.batchSize = size; return this; }
        public Cursor iterator() { return this.cursor; }
    }
}
