package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 集成测试, 依赖本机 27017 端口的 MongoDB, 不可达时整类跳过
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoStorageProviderTest {
    private static final String TEST_DATABASE = "sparrow_sync_it";
    private static final DataKey STATS = DataKey.of("test", "stats");
    private static final DataKey BLOB = DataKey.of("test", "blob");
    private static final long BASE_TIME = 1_756_300_000_000L;

    private final QuietLogger logger = new QuietLogger();
    private PlayerSerialExecutor serialExecutor;
    private MongoStorageProvider provider;
    private UUID player;

    @BeforeAll
    void connect() {
        PluginConfig.MongoOptions options = new PluginConfig.MongoOptions("mongodb://localhost:27017", TEST_DATABASE, "", "", "admin", "it_");
        DocumentSnapshotCodec codec = new DocumentSnapshotCodec(new DataRegistry(), new BinarySnapshotCodec(CompressorRegistry.DEFLATE));
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
            this.provider.close();
        }
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            client.getDatabase(TEST_DATABASE).drop();
        } catch (Exception ignored) {
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

                CompletionException failure = assertThrows(CompletionException.class, () -> this.provider.saveSnapshot(snapshot(2, false)).join());

                assertInstanceOf(MongoWriteException.class, failure.getCause());
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
            DocumentSnapshotCodec codec = new DocumentSnapshotCodec(new DataRegistry(), new BinarySnapshotCodec(CompressorRegistry.DEFLATE));
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
                assertEquals(1, schema.getInteger("version"));
            } finally {
                upgraded.close();
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
            DocumentSnapshotCodec codec = new DocumentSnapshotCodec(new DataRegistry(), new BinarySnapshotCodec(CompressorRegistry.DEFLATE));
            MongoStorageProvider outdated = new MongoStorageProvider(options, codec, this.serialExecutor, Runnable::run, this.logger);
            try {
                assertThrows(IllegalStateException.class, outdated::initialize);
            } finally {
                outdated.close();
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
        // 未注册的二进制字段原样透传, 16MB 载荷触发写前守卫
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        data.put(BLOB, NBT.createByteArray(new byte[16 * 1024 * 1024]));
        Snapshot oversized = new Snapshot(meta(1, false), data);

        CompletionException exception = assertThrows(CompletionException.class, () -> this.provider.saveSnapshot(oversized).join());

        assertTrue(String.valueOf(exception.getCause().getMessage()).contains("over the document limit"));
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
