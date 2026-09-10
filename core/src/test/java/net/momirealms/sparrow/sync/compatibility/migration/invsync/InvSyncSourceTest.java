package net.momirealms.sparrow.sync.compatibility.migration.invsync;

import com.google.gson.Gson;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
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
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class InvSyncSourceTest {
    private final List<MigrationSource.PlayerData> accepted = new ArrayList<>();
    private final List<byte[]> rejected = new ArrayList<>();
    private final Map<Field, Object> previous = new LinkedHashMap<>();
    private DataRegistry registry;
    private Object storage;
    private Thread decoderThread;
    private Thread sinkThread;
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
                case "getPlugin" -> null;
                case "isPluginEnabled" -> false;
                case "getPlugins" -> new Plugin[0];
                default -> throw new AssertionError(operation);
            });
            case "getAdvancement" -> null;
            case "getLogger" -> Logger.getAnonymousLogger();
            case "getVersion" -> "Paper (MC: 1.21.8)";
            case "getBukkitVersion" -> "1.21.8-R0.1-SNAPSHOT";
            default -> throw new AssertionError(method);
        }));
        this.registry = new DataRegistry();
        this.registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        this.registry.register(NmsPlayerFixture.allocate(EnderChestDataType.class));
        this.registry.register(new HealthDataType());
        this.registry.register(new AttributesDataType());
        this.registry.register(new HungerDataType());
        this.registry.register(new ExperienceDataType());
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
            case "getGson" -> new Gson();
            case "getItemSerializer" -> new ItemSerializer();
            default -> throw new AssertionError(method);
        });
    }

    @Test
    void mysqlReadsOneBodyAtATimeAndPrefersV2() throws Exception {
        MysqlManager mysql = new MysqlManager(new SourceData(), new SourceData());
        this.storage = mysql;
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        assertEquals(2, this.accepted.size());
        assertEquals(List.of("uuid"), mysql.playerDataDao.query.columns);
        assertTrue(mysql.playerDataDao.query.cursor.closed);
        assertEquals(0, mysql.legacyReads);
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
        assertEquals(new HungerDataType.Hunger(13, 0, 0, 0), new HungerDataType().decode(data.get(HungerDataType.HUNGER), CraftMagicNumbers.INSTANCE.getDataVersion()));
        assertEquals(new ExperienceDataType.Experience(0, 25, 0.75f), new ExperienceDataType().decode(data.get(ExperienceDataType.EXPERIENCE), CraftMagicNumbers.INSTANCE.getDataVersion()));
        var attributes = new AttributesDataType().decode(data.get(AttributesDataType.ATTRIBUTES), CraftMagicNumbers.INSTANCE.getDataVersion());
        assertEquals(1, attributes.values().length);
        assertEquals(36, attributes.values()[0].base());
    }

    @Test
    void readDecodeAndSinkStayOnTheMigrationWorker() throws Exception {
        SourceData source = new SourceData();
        source.inventory = new byte[]{1};
        MysqlManager mysql = new MysqlManager(source);
        this.storage = mysql;
        Thread worker;
        try (var executor = Executors.newSingleThreadExecutor()) {
            worker = executor.submit(() -> {
                new InvSyncSource(this.plugin(), this.registry).read(this.sink);
                return Thread.currentThread();
            }).get();
        }
        var inventory = NmsPlayerFixture.allocate(InventoryDataType.class).decode(this.accepted.getFirst().data().get(InventoryDataType.INVENTORY), CraftMagicNumbers.INSTANCE.getDataVersion());
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
        source.inventory = new byte[]{2};
        this.storage = new MysqlManager(source);
        new InvSyncSource(this.plugin(), this.registry).read(this.sink);
        var inventory = NmsPlayerFixture.allocate(InventoryDataType.class).decode(this.accepted.getFirst().data().get(InventoryDataType.INVENTORY), CraftMagicNumbers.INSTANCE.getDataVersion());
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
        source.inventory = new byte[]{-2};
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
        assertTrue(mysql.playerDataDao.query.cursor.closed);
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
        Gson getGson();
        Object getItemSerializer();
    }

    public class ItemSerializer {
        public Map<Integer, ItemStack> deserializerInventory(byte[] bytes) throws Exception {
            decoderThread = Thread.currentThread();
            if (bytes[0] == -1) throw new IOException("bad item");
            if (bytes[0] == -2) throw new InterruptedException();
            if (bytes[0] == 2) {
                return Map.of(42, CraftItemStack.asCraftMirror(new net.minecraft.world.item.ItemStack(Items.SADDLE)));
            }
            return Map.of(36, CraftItemStack.asCraftMirror(new net.minecraft.world.item.ItemStack(Items.DIAMOND_BOOTS)),
                    39, CraftItemStack.asCraftMirror(new net.minecraft.world.item.ItemStack(Items.DIAMOND_HELMET)),
                    40, CraftItemStack.asCraftMirror(new net.minecraft.world.item.ItemStack(Items.SHIELD)));
        }
    }

    public static class SourceData {
        final String uuid = UUID.randomUUID().toString();
        byte[] inventory;
        public String getUuid() { return this.uuid; }
        public double getHealth() { return 12; }
        public double getMaxHealth() { return 36; }
        public double getFood() { return 13; }
        public int getLevel() { return 25; }
        public float getExp() { return 0.75f; }
        public boolean inventoryIsInit() { return this.inventory != null; }
        public byte[] getInventory() { return this.inventory; }
        public boolean enderChestIsInit() { return false; }
        public boolean buffsIsInit() { return false; }
        public boolean statisticIsInit() { return false; }
        public boolean advancementsIsInit() { return false; }
        public boolean persistentDataIsInit() { return false; }
    }

    public static class MysqlManager {
        final Dao playerDataDao;
        int reads;
        int legacyReads;
        Thread readThread;
        MysqlManager(SourceData... data) { this.playerDataDao = new Dao(List.of(data)); }
        public SourceData getPlayerData(String uuid) { this.readThread = Thread.currentThread(); this.reads++; return this.playerDataDao.query.data.stream().filter(data -> data.uuid.equals(uuid)).findFirst().orElse(null); }
        public List<String> getAllV1PlayerUUIDs() { return this.playerDataDao.query.data.stream().map(data -> data.uuid).toList(); }
        public Object getPlayerDataFromV1(String uuid) { this.legacyReads++; throw new AssertionError("V2 must win"); }
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
        final Collection playerDataCollection = new Collection();
        int mapped;
        private SourceData documentToPlayerData(SourceData document) { this.mapped++; return document; }
    }

    public static class Collection {
        final Cursor cursor = new Cursor(List.of(new SourceData()).iterator());
        int batchSize;
        public Collection find() { return this; }
        public Collection batchSize(int size) { this.batchSize = size; return this; }
        public Cursor iterator() { return this.cursor; }
    }
}
