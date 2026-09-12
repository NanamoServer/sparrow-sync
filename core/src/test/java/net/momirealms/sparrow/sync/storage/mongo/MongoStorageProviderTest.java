package net.momirealms.sparrow.sync.storage.mongo;

import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import com.mongodb.client.MongoClient;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.CreateCollectionOptions;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapSource;
import org.bson.Document;
import org.bson.BsonDocument;
import org.bson.UuidRepresentation;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 集成测试, 依赖本机 27017 端口的 MongoDB, 不可达时整类跳过
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoStorageProviderTest {
    private static final String TEST_DATABASE = "sparrow_sync_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final DataKey STATS = DataKey.of("test", "stats");
    private static final DataKey BLOB = DataKey.of("test", "blob");
    private static final long BASE_TIME = 1_756_300_000_000L;

    private final SyncLogger logger = new SyncLogger(new QuietLogger());
    private PlayerSerialExecutor serialExecutor;
    private MongoStorageProvider provider;
    private UUID player;

    @BeforeAll
    void connect() {
        PluginConfig.MongoOptions options = new PluginConfig.MongoOptions("mongodb://localhost:27017", TEST_DATABASE, "", "", "admin", "it_");
        DocumentSnapshotCodec codec = new DocumentSnapshotCodec(new SnapshotDataCodec(CompressorRegistry.DEFLATE));
        this.serialExecutor = new PlayerSerialExecutor(this.logger, 4);
        // 读走内联执行, 写按玩家投递到 worker, 与运行期同构
        this.provider = new MongoStorageProvider(options, codec, this.serialExecutor, Runnable::run, this.logger);
        try {
            this.provider.initialize();
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "local MongoDB is not reachable: " + exception.getMessage());
        }
    }

    @BeforeEach
    void setUp() {
        this.player = UUID.randomUUID();
    }

    @AfterAll
    void cleanup() {
        if (this.serialExecutor != null) {
            this.serialExecutor.shutdown(5, TimeUnit.SECONDS);
        }
        if (this.provider != null) {
            this.provider.shutdown();
        }
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            client.getDatabase(TEST_DATABASE).drop();
        } catch (Exception ignored) {
        }
    }

    @Test
    void mapStorageFactoryUsesTheProvidersDatabaseAndCollectionPrefix() {
        String owner = this.player.toString();
        var maps = this.provider.maps();
        CompoundTag tag = NBT.createCompound();
        tag.putString("dimension", "minecraft:overworld");
        tag.putByteArray("colors", new byte[MapData.PIXEL_COUNT]);
        var stored = maps.register(new MapSource(owner, 0), new MapData(4440, tag)).join();
        var other = this.provider.maps();
        assertEquals(stored, other.find(stored.identity().globalId()).join().orElseThrow());
        other.update(stored.identity(), stored.data()).join();
        assertEquals(stored, maps.find(stored.identity().globalId()).join().orElseThrow());

        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            var database = client.getDatabase(TEST_DATABASE);
            var meta = database.getCollection("it_meta");
            var schema = meta.find(new Document("_id", "schema")).first();
            var counter = meta.find(new Document("_id", "maps")).first();
            assertEquals(2, schema.getInteger("version"));
            assertEquals(-(long) stored.identity().globalId(), counter.getLong("sequence"));
            assertEquals(Set.of("_id_"), Set.copyOf(meta.listIndexes().map(index -> index.getString("name")).into(new ArrayList<>())));
            assertFalse(database.listCollectionNames().into(new ArrayList<>()).contains("it_map_counters"));

            IndexReconciler.reconcile(this.logger, database, "it_");
            assertEquals(schema, meta.find(new Document("_id", "schema")).first());
            assertEquals(counter, meta.find(new Document("_id", "maps")).first());
            var next = this.provider.maps().register(new MapSource(owner, 1), stored.data()).join();
            assertEquals(stored.identity().globalId() - 1, next.identity().globalId());
        }
    }

    @Test
    void savedSnapshotReadsBackByLatestAndId() {
        Snapshot first = snapshot(1, false);
        Snapshot second = snapshot(2, false);

        assertEquals(SaveResult.SAVED, this.provider.saveSnapshot(first).join());
        assertEquals(SaveResult.SAVED, this.provider.saveSnapshot(second).join());

        assertEquals(second, this.provider.latestSnapshot(this.player).join().orElseThrow());
        assertEquals(first, this.provider.snapshot(first.meta().id()).join().orElseThrow());
        assertEquals(Optional.empty(), this.provider.snapshot(UUID.randomUUID()).join());
    }

    @Test
    void sameSnapshotWrittenTwiceIsIdempotent() {
        Snapshot snapshot = snapshot(1, false);

        assertEquals(SaveResult.SAVED, this.provider.saveSnapshot(snapshot).join());
        assertEquals(SaveResult.DUPLICATE, this.provider.saveSnapshot(snapshot).join());

        assertEquals(1, this.provider.listSnapshots(this.player).join().size());
    }

    @Test
    void duplicateKeyFromForeignUniqueIndexFailsInsteadOfReportingDuplicate() {
        // 模拟 beta 遗留的 (player, version) 唯一索引: 新文档不带 version, 同一玩家第二份起统统撞 null 键.
        // 这类冲突是写入失败, 报成 DUPLICATE 等于把静默丢数据伪装成幂等成功
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            MongoCollection<Document> snapshots = client.getDatabase(TEST_DATABASE).getCollection("it_snapshots");
            // 先清掉其他测试的存量文档, 它们的 version 同为 null, 会让唯一索引建不起来
            snapshots.deleteMany(new Document());
            Bson legacyKeys = Indexes.compoundIndex(Indexes.ascending("player"), Indexes.descending("version"));
            snapshots.createIndex(legacyKeys, new IndexOptions().unique(true));
            try {
                assertEquals(SaveResult.SAVED, this.provider.saveSnapshot(snapshot(1, false)).join());

                // 报成 DUPLICATE 等于把静默丢数据伪装成幂等成功, 这类冲突重试也不会好
                SaveResult result = this.provider.saveSnapshot(snapshot(2, false)).join();

                assertEquals(SaveResult.REJECTED_MALFORMED, result);
                assertFalse(result.stored());
                assertEquals(1, this.provider.listSnapshots(this.player).join().size());
            } finally {
                snapshots.dropIndex(legacyKeys);
            }
        }
    }

    @Test
    void indexesAreReconciledToDeclarationOnInitialize() {
        // 从旧版升级上来的库带着 (player, version) 唯一索引与已退场字段的查询索引,
        // 启动对账必须清掉一切未声明的索引并建出声明的, 否则遗留唯一索引让每名玩家只能存一份
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            MongoCollection<Document> snapshots = client.getDatabase(TEST_DATABASE).getCollection("it_snapshots");
            snapshots.deleteMany(new Document());
            snapshots.dropIndexes();
            snapshots.createIndex(Indexes.compoundIndex(Indexes.ascending("player"), Indexes.descending("version")), new IndexOptions().unique(true));
            snapshots.createIndex(Indexes.ascending("cause"));

            PluginConfig.MongoOptions options = new PluginConfig.MongoOptions("mongodb://localhost:27017", TEST_DATABASE, "", "", "admin", "it_");
            DocumentSnapshotCodec codec = new DocumentSnapshotCodec(new SnapshotDataCodec(CompressorRegistry.DEFLATE));
            MongoStorageProvider upgraded = new MongoStorageProvider(options, codec, this.serialExecutor, Runnable::run, this.logger);
            try {
                upgraded.initialize();

                assertEquals(SaveResult.SAVED, upgraded.saveSnapshot(snapshot(1, false)).join());
                assertEquals(SaveResult.SAVED, upgraded.saveSnapshot(snapshot(2, false)).join());
                List<String> names = new ArrayList<>();
                for (Document index : snapshots.listIndexes()) {
                    names.add(index.getString("name"));
                }
                names.sort(String::compareTo);
                assertEquals(List.of("_id_", "player_1_ts_-1__id_-1"), names);
                // 对账完成后本版的 schema 代数被写回, 此后更旧的插件版本连不上这个库
                Document schema = client.getDatabase(TEST_DATABASE).getCollection("it_meta").find(new Document("_id", "schema")).first();
                assertEquals(2, schema.getInteger("version"));
            } finally {
                upgraded.shutdown();
            }
        }
    }

    @Test
    void mapIndexesUpgradeTogetherAndKeepExistingRecordsAndSequence() {
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            var database = client.getDatabase(TEST_DATABASE);
            String prefix = "upgrade_maps_";
            var meta = database.getCollection(prefix + "meta");
            var maps = database.getCollection(prefix + "maps");
            meta.insertOne(new Document("_id", "schema").append("version", 1));
            Document sequence = new Document("_id", "maps").append("sequence", 42L);
            meta.insertOne(sequence);
            Document oldMap = new Document("_id", -42).append("owner", "source").append("origin_id", 1);
            maps.insertOne(oldMap);
            maps.createIndex(Indexes.descending("owner"), new IndexOptions().name("map_source"));
            maps.createIndex(Indexes.ascending("updated_at"), new IndexOptions().name("existing_time"));
            maps.createIndex(Indexes.ascending("retired"));
            var options = new PluginConfig.MongoOptions("mongodb://localhost:27017", TEST_DATABASE, "", "", "admin", prefix);
            var upgraded = new MongoStorageProvider(options, new DocumentSnapshotCodec(new SnapshotDataCodec(CompressorRegistry.DEFLATE)), this.serialExecutor, Runnable::run, this.logger);
            try {
                for (int i = 0; i < 2; i++) {
                    upgraded.initialize();
                    assertEquals(2, meta.find(new Document("_id", "schema")).first().getInteger("version"));
                    assertEquals(sequence, meta.find(new Document("_id", "maps")).first());
                    assertEquals(oldMap, maps.find().first());
                    assertEquals(Set.of("_id_", "map_source", "existing_time"), Set.copyOf(maps.listIndexes().map(index -> index.getString("name")).into(new ArrayList<>())));
                    Document source = maps.listIndexes().into(new ArrayList<>()).stream().filter(index -> index.getString("name").equals("map_source")).findFirst().orElseThrow();
                    assertEquals(new Document("owner", 1).append("origin_id", 1), source.get("key"));
                    assertTrue(source.getBoolean("unique"));
                    assertEquals(Set.of("_id_"), Set.copyOf(meta.listIndexes().map(index -> index.getString("name")).into(new ArrayList<>())));
                    upgraded.shutdown();
                }
            } finally {
                upgraded.shutdown();
            }
        }
    }

    @Test
    void mapIndexOptionsAreReconciledEvenAtTheCurrentVersion() {
        List<IndexOptions> variants = List.of(
                new IndexOptions().sparse(true),
                new IndexOptions().partialFilterExpression(new Document("owner", new Document("$exists", true))),
                new IndexOptions().hidden(true),
                new IndexOptions().collation(Collation.builder().locale("en").collationStrength(CollationStrength.SECONDARY).build())
        );
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            var database = client.getDatabase(TEST_DATABASE);
            for (int i = 0; i < variants.size(); i++) {
                String prefix = "map_options_" + i + "_";
                IndexReconciler.reconcile(this.logger, database, prefix);
                var maps = database.getCollection(prefix + "maps");
                maps.dropIndex("map_source");
                maps.createIndex(Indexes.ascending("owner", "origin_id"), variants.get(i).unique(true).name("map_source"));
                maps.dropIndex("map_updated_at");
                maps.createIndex(Indexes.ascending("updated_at"), new IndexOptions().expireAfter(60L, TimeUnit.SECONDS).name("map_updated_at"));
                IndexReconciler.reconcile(this.logger, database, prefix);
                List<Document> indexes = maps.listIndexes().into(new ArrayList<>());
                assertEquals(3, indexes.size());
                for (Document index : indexes) {
                    assertFalse(Boolean.TRUE.equals(index.getBoolean("sparse")));
                    assertFalse(Boolean.TRUE.equals(index.getBoolean("hidden")));
                    assertFalse(index.containsKey("partialFilterExpression"));
                    assertFalse(index.containsKey("expireAfterSeconds"));
                    assertFalse(index.containsKey("collation"));
                }
                assertEquals(2, database.getCollection(prefix + "meta").find(new Document("_id", "schema")).first().getInteger("version"));
            }
        }
    }

    @Test
    void mapIndexesUseTheCollectionsDefaultCollation() {
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            var database = client.getDatabase(TEST_DATABASE);
            String prefix = "map_collation_";
            database.createCollection(prefix + "maps", new CreateCollectionOptions().collation(Collation.builder().locale("en").collationStrength(CollationStrength.SECONDARY).build()));
            var maps = database.getCollection(prefix + "maps");
            maps.createIndex(Indexes.ascending("owner", "origin_id"), new IndexOptions().unique(true).name("map_source").collation(Collation.builder().locale("simple").build()));
            IndexReconciler.reconcile(this.logger, database, prefix);
            List<Document> indexes = maps.listIndexes().into(new ArrayList<>());
            Document expected = database.listCollections().filter(new Document("name", prefix + "maps")).first().get("options", Document.class).get("collation", Document.class);
            for (Document index : indexes) {
                assertEquals(expected, index.get("collation"));
            }
            IndexReconciler.reconcile(this.logger, database, prefix);
            assertEquals(indexes, maps.listIndexes().into(new ArrayList<>()));
        }
    }

    @Test
    void failedMapIndexBuildKeepsThePreviousVersionAndCanResume() {
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            var database = client.getDatabase(TEST_DATABASE);
            for (int version = 0; version <= 1; version++) {
                String prefix = "failed_maps_" + version + "_";
                var meta = database.getCollection(prefix + "meta");
                var maps = database.getCollection(prefix + "maps");
                Document schema = version == 0 ? null : new Document("_id", "schema").append("version", version);
                if (schema != null) {
                    meta.insertOne(schema);
                }
                Document sequence = new Document("_id", "maps").append("sequence", 99L);
                meta.insertOne(sequence);
                maps.insertMany(List.of(new Document("_id", -1).append("owner", "same").append("origin_id", 1), new Document("_id", -2).append("owner", "same").append("origin_id", 1)));
                var options = new PluginConfig.MongoOptions("mongodb://localhost:27017", TEST_DATABASE, "", "", "admin", prefix);
                var upgraded = new MongoStorageProvider(options, new DocumentSnapshotCodec(new SnapshotDataCodec(CompressorRegistry.DEFLATE)), this.serialExecutor, Runnable::run, this.logger);
                try {
                    assertThrows(IllegalStateException.class, upgraded::initialize);
                    assertThrows(IllegalStateException.class, upgraded::maps);
                    assertEquals(schema, meta.find(new Document("_id", "schema")).first());
                    assertEquals(sequence, meta.find(new Document("_id", "maps")).first());
                    assertEquals(2, maps.countDocuments());
                    // 测试显式移除制造冲突的记录, 下一次启动重新完成索引对账.
                    maps.deleteOne(new Document("_id", -2));
                    upgraded.initialize();
                    assertEquals(2, meta.find(new Document("_id", "schema")).first().getInteger("version"));
                    assertEquals(sequence, meta.find(new Document("_id", "maps")).first());
                    assertEquals(1, maps.countDocuments());
                } finally {
                    upgraded.shutdown();
                }
            }
        }
    }

    @Test
    void newerSchemaGenerationRefusesToStart() {
        // 共库的另一台服务器已用更新版本的插件升级了库结构, 本插件 (更旧) 必须拒绝启动,
        // 否则它的对账会把新版索引拆回旧样, 两个版本互相改写没有尽头
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            MongoCollection<Document> meta = client.getDatabase(TEST_DATABASE).getCollection("it_meta");
            meta.replaceOne(new Document("_id", "schema"),
                    new Document("_id", "schema").append("version", 999),
                    new com.mongodb.client.model.ReplaceOptions().upsert(true));

            PluginConfig.MongoOptions options = new PluginConfig.MongoOptions("mongodb://localhost:27017", TEST_DATABASE, "", "", "admin", "it_");
            DocumentSnapshotCodec codec = new DocumentSnapshotCodec(new SnapshotDataCodec(CompressorRegistry.DEFLATE));
            MongoStorageProvider outdated = new MongoStorageProvider(options, codec, this.serialExecutor, Runnable::run, this.logger);
            try {
                var maps = client.getDatabase(TEST_DATABASE).getCollection("it_maps");
                maps.createIndex(Indexes.ascending("future_field"), new IndexOptions().name("future_map_index"));
                List<Document> before = maps.listIndexes().into(new ArrayList<>());
                assertThrows(IllegalStateException.class, outdated::initialize);
                assertEquals(before, maps.listIndexes().into(new ArrayList<>()));
                maps.dropIndex("future_map_index");
            } finally {
                outdated.shutdown();
                meta.deleteOne(new Document("_id", "schema"));
            }
        }
    }

    @Test
    void lateSnapshotLandsInHistoryWithoutDisplacingTheNewest() {
        // 本机关服落库失败 -> 别的子服放人并写出更晚的快照 -> 本机重启后把留存的快照插回
        Snapshot fromOtherServer = snapshot(5, false);
        this.provider.saveSnapshot(fromOtherServer).join();
        Snapshot recovered = snapshot(3, false);

        assertEquals(SaveResult.SAVED_OUT_OF_ORDER, this.provider.saveSnapshot(recovered).join());

        assertEquals(fromOtherServer, this.provider.latestSnapshot(this.player).join().orElseThrow());
        assertEquals(List.of(fromOtherServer.meta().id(), recovered.meta().id()),
                this.provider.listSnapshots(this.player).join().stream().map(SnapshotMeta::id).toList());
    }

    @Test
    void backToBackSavesForOnePlayerStayInSubmissionOrder() {
        // 不 join 直接连发 20 份: 分桶收在 provider 内部, 同一玩家的写入必须仍按提交序落库.
        // 若写入被投递到共享池, 其中若干份会后到先写, 结果就是 SAVED_OUT_OF_ORDER —— 那正是脑亡信号被自家污染的样子
        List<CompletableFuture<SaveResult>> pending = new ArrayList<>();
        for (int offset = 1; offset <= 20; offset++) {
            pending.add(this.provider.saveSnapshot(snapshot(offset, false)));
        }
        CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).join();

        assertTrue(pending.stream().map(CompletableFuture::join).allMatch(result -> result == SaveResult.SAVED));
        assertEquals(BASE_TIME + 20, this.provider.latestSnapshot(this.player).join().orElseThrow().meta().timestamp());
    }

    @Test
    void paginationProjectsOnlyTheRequestedMetadataOnTheWire() throws Exception {
        List<SnapshotMeta> metas = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            SnapshotMeta meta = new SnapshotMeta(new UUID(0, i + 1), this.player, BASE_TIME, SaveCause.COMMAND, i % 2 == 0, "lobby", 4440);
            this.provider.saveSnapshot(new Snapshot(meta, Map.of())).join();
            metas.addFirst(meta);
        }
        List<BsonDocument> finds = new ArrayList<>();
        List<BsonDocument> responses = new ArrayList<>();
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://localhost:27017"))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .addCommandListener(new CommandListener() {
                    @Override
                    public void commandStarted(CommandStartedEvent event) {
                        if (event.getCommandName().equals("find")) {
                            finds.add(BsonDocument.parse(event.getCommand().toJson()));
                        }
                    }

                    @Override
                    public void commandSucceeded(CommandSucceededEvent event) {
                        if (event.getCommandName().equals("find")) {
                            responses.add(BsonDocument.parse(event.getResponse().toJson()));
                        }
                    }
                }).build();
        Field field = MongoStorageProvider.class.getDeclaredField("snapshots");
        field.setAccessible(true);
        Object original = field.get(this.provider);
        try (MongoClient client = MongoClients.create(settings)) {
            MongoCollection<Document> collection = client.getDatabase(TEST_DATABASE).getCollection("it_snapshots");
            field.set(this.provider, collection);
            collection.updateMany(new Document("player", this.player), new Document("$set", new Document("data", "invalid payload")));
            SnapshotQuery query = SnapshotQuery.of(this.player).withOffset(27).withLimit(27);
            assertEquals(metas.subList(27, 32), this.provider.listSnapshots(query).join());
            assertEquals(32L, this.provider.countSnapshots(query).join());
            assertEquals(1, finds.size());
            BsonDocument find = finds.getFirst();
            assertEquals(27, find.getNumber("skip").intValue());
            assertEquals(27, find.getNumber("limit").intValue());
            assertEquals(0, find.getDocument("projection").getNumber("data").intValue());
            assertEquals(BsonDocument.parse("{ts: -1, _id: -1}"), find.getDocument("sort"));
            var batch = responses.getFirst().getDocument("cursor").getArray("firstBatch");
            assertEquals(5, batch.size());
            for (int i = 0; i < batch.size(); i++) {
                assertFalse(batch.get(i).asDocument().containsKey("data"));
            }
            SnapshotQuery filtered = query.between(BASE_TIME, BASE_TIME).withPinned(SnapshotQuery.PinFilter.PINNED).withOffset(1).withLimit(1);
            assertEquals(List.of(metas.get(3)), this.provider.listSnapshots(filtered).join());
            assertEquals(16L, this.provider.countSnapshots(filtered).join());
            assertEquals(16L, this.provider.countSnapshots(filtered.withPinned(SnapshotQuery.PinFilter.UNPINNED)).join());
            assertEquals(List.of(metas.getLast()), this.provider.listSnapshots(query.withLimit(0).withOffset(31)).join());
            assertEquals(List.of(), this.provider.listSnapshots(query.withOffset(32)).join());
            assertEquals(0L, this.provider.countSnapshots(query.between(BASE_TIME + 1, BASE_TIME + 2)).join());
        } finally {
            field.set(this.provider, original);
        }
    }

    @Test
    void singleSnapshotDecodePreservesCorruptionAndFutureFormatReasons() {
        Snapshot snapshot = snapshot(1, false);
        this.provider.saveSnapshot(snapshot).join();
        try (MongoClient client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://localhost:27017"))
                .uuidRepresentation(UuidRepresentation.STANDARD).build())) {
            MongoCollection<Document> collection = client.getDatabase(TEST_DATABASE).getCollection("it_snapshots");
            Document filter = new Document("_id", snapshot.meta().id());
            collection.updateOne(filter, new Document("$set", new Document("data", "broken")));
            CompletionException corrupted = assertThrows(CompletionException.class, () -> this.provider.snapshot(snapshot.meta().id()).join());
            assertEquals(FormatException.InvalidReason.CORRUPTED, assertInstanceOf(FormatException.class, corrupted.getCause()).reason());
            collection.updateOne(filter, new Document("$set", new Document("format", 100)));
            CompletionException future = assertThrows(CompletionException.class, () -> this.provider.snapshot(snapshot.meta().id()).join());
            assertEquals(FormatException.InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(FormatException.class, future.getCause()).reason());
        }
    }

    @Test
    void listReturnsMetadataNewestFirst() {
        for (int offset = 1; offset <= 3; offset++) {
            this.provider.saveSnapshot(snapshot(offset, false)).join();
        }

        List<SnapshotMeta> metas = this.provider.listSnapshots(this.player).join();

        assertEquals(3, metas.size());
        assertEquals(BASE_TIME + 3, metas.get(0).timestamp());
        assertEquals(BASE_TIME + 1, metas.get(2).timestamp());
        assertEquals(SaveCause.DISCONNECT, metas.get(0).cause());
    }

    @Test
    void listRecentSnapshotsKeepsOnlyTheNewest() {
        for (int offset = 1; offset <= 5; offset++) {
            this.provider.saveSnapshot(snapshot(offset, false)).join();
        }

        List<SnapshotMeta> recent = this.provider.listRecentSnapshots(this.player, 2).join();

        assertEquals(List.of(BASE_TIME + 5, BASE_TIME + 4), recent.stream().map(SnapshotMeta::timestamp).toList());
    }

    @Test
    void listPinnedSnapshotsSkipsUnpinned() {
        this.provider.saveSnapshot(snapshot(1, true)).join();
        this.provider.saveSnapshot(snapshot(2, false)).join();
        this.provider.saveSnapshot(snapshot(3, true)).join();

        List<SnapshotMeta> pinned = this.provider.listPinnedSnapshots(this.player).join();

        assertEquals(List.of(BASE_TIME + 3, BASE_TIME + 1), pinned.stream().map(SnapshotMeta::timestamp).toList());
    }

    @Test
    void listSnapshotsBetweenIncludesBothBounds() {
        for (int offset = 1; offset <= 5; offset++) {
            this.provider.saveSnapshot(snapshot(offset, false)).join();
        }

        List<SnapshotMeta> window = this.provider.listSnapshotsBetween(this.player, BASE_TIME + 2, BASE_TIME + 4).join();

        assertEquals(List.of(BASE_TIME + 4, BASE_TIME + 3, BASE_TIME + 2), window.stream().map(SnapshotMeta::timestamp).toList());
    }

    @Test
    void combinedQueryAppliesEveryCondition() {
        this.provider.saveSnapshot(snapshot(1, true)).join();
        this.provider.saveSnapshot(snapshot(2, false)).join();
        this.provider.saveSnapshot(snapshot(3, true)).join();
        this.provider.saveSnapshot(snapshot(9, true)).join();

        List<SnapshotMeta> found = this.provider.listSnapshots(SnapshotQuery.of(this.player)
                .between(BASE_TIME + 1, BASE_TIME + 5)
                .withPinned(SnapshotQuery.PinFilter.PINNED)
                .withLimit(1)).join();

        // 区间挡掉 +9, pinned 挡掉 +2, limit 只留最新的 +3
        assertEquals(List.of(BASE_TIME + 3), found.stream().map(SnapshotMeta::timestamp).toList());
    }

    @Test
    void rotateRemovesOldestUnpinnedAndSparesPinned() {
        this.provider.saveSnapshot(snapshot(1, true)).join();
        for (int offset = 2; offset <= 5; offset++) {
            this.provider.saveSnapshot(snapshot(offset, false)).join();
        }

        int removed = this.provider.rotate(this.player, 2).join();

        // 未固定的四份只留采集最晚的两份, 固定的那份不参与轮转
        assertEquals(2, removed);
        assertEquals(List.of(BASE_TIME + 5, BASE_TIME + 4, BASE_TIME + 1),
                this.provider.listSnapshots(this.player).join().stream().map(SnapshotMeta::timestamp).toList());
    }

    @Test
    void pinAndDeleteSnapshot() {
        Snapshot snapshot = snapshot(1, false);
        this.provider.saveSnapshot(snapshot).join();

        assertTrue(this.provider.setPinned(snapshot.meta().id(), true).join());
        assertTrue(this.provider.listSnapshots(this.player).join().get(0).pinned());
        assertTrue(this.provider.deleteSnapshot(snapshot.meta().id()).join());
        assertEquals(Optional.empty(), this.provider.latestSnapshot(this.player).join());
    }

    @Test
    void ensureUserAndLookupByName() {
        this.provider.ensureUser(this.player, "Catnies").join();

        assertEquals(Optional.of(this.player), this.provider.lookupUser("Catnies").join());
        assertEquals(Optional.empty(), this.provider.lookupUser("Nobody").join());
    }

    @Test
    void oversizedSnapshotIsRejectedBeforeWrite() {
        // 不可压缩的载荷超过 15 MiB, 触发 Mongo 写前大小检查
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        byte[] payload = new byte[15 * 1024 * 1024 + 1024];
        new Random(7L).nextBytes(payload);
        data.put(BLOB, NBT.createByteArray(payload));
        Snapshot oversized = new Snapshot(meta(1, false), data);

        // 重试也不会变小, 归类为需要人工介入而不是留在重试队列里
        SaveResult result = this.provider.saveSnapshot(oversized).join();

        assertEquals(SaveResult.REJECTED_OVERSIZED, result);
        assertFalse(result.stored());
        assertFalse(result.retriable());
        assertEquals(0, this.provider.listSnapshots(this.player).join().size());
    }

    private Snapshot snapshot(int timeOffset, boolean pinned) {
        return new Snapshot(meta(timeOffset, pinned), payload());
    }

    private SnapshotMeta meta(int timeOffset, boolean pinned) {
        return metaOf(this.player, timeOffset, pinned);
    }

    private static SnapshotMeta metaOf(UUID player, int timeOffset, boolean pinned) {
        return SnapshotMeta.builder()
                .player(player)
                .timestamp(BASE_TIME + timeOffset)
                .cause(SaveCause.DISCONNECT)
                .pinned(pinned)
                .server("test-server")
                .mcDataVersion(4440)
                .build();
    }

    private static Map<DataKey, Tag> payload() {
        CompoundTag stats = NBT.createCompound();
        stats.putInt("kills", 3);
        stats.putString("mode", "SURVIVAL");
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        data.put(STATS, stats);
        data.put(BLOB, NBT.createByteArray(new byte[]{1, 2, 3}));
        return data;
    }

    private static final class QuietLogger implements PluginLogger {

        @Override
        public void info(String s) {
        }

        @Override
        public void warn(String s) {
        }

        @Override
        public void warn(String s, Throwable t) {
        }

        @Override
        public void error(String s) {
        }

        @Override
        public void error(String s, Throwable t) {
        }
    }
}
