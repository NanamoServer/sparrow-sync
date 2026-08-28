package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.MongoWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.event.ServerHeartbeatFailedEvent;
import net.momirealms.sparrow.sync.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB 存储实现. 集合: {prefix}users 与 {prefix}snapshots,
 * 快照经 {@link DocumentSnapshotCodec} 编解码, 以 _id 幂等, 以 timestamp 定序.
 * <p> 写入按玩家 UUID 投递到 {@link PlayerSerialExecutor} 的 worker, 同一玩家严格串行;
 * 读取与无顺序要求的写入走公共异步池. 投递收在本类内部, 调用方拿到的是尚未完成的 future.
 */
public final class MongoStorageProvider implements StorageProvider {
    private static final String USER_FIELD_NAME = "name";
    private static final String USER_FIELD_LAST_SEEN = "lastSeen";
    private static final long MAX_PAYLOAD_BYTES = 15L * 1024 * 1024;   // 写前守卫, 对 16MB 文档上限留余量
    // 采集时刻降序, 同毫秒时以 id 兜底保证结果稳定 (同毫秒本身是采集侧的契约违规)
    private static final Bson NEWEST_FIRST = Sorts.descending(DocumentSnapshotCodec.FIELD_TIMESTAMP, DocumentSnapshotCodec.FIELD_ID);

    private final PluginConfig.MongoOptions options;
    private final DocumentSnapshotCodec codec;
    private final PlayerSerialExecutor serialExecutor;  // 写: 按玩家 UUID 分桶, 同一玩家严格串行
    private final Executor asyncExecutor;               // 读: 无顺序要求
    private final PluginLogger logger;

    private MongoClient mongoClient;
    private MongoDatabase mongoDatabase;
    private volatile MongoCollection<Document> users;
    private volatile MongoCollection<Document> snapshots;

    public MongoStorageProvider(@NotNull PluginConfig.MongoOptions options, @NotNull DocumentSnapshotCodec codec,
                                @NotNull PlayerSerialExecutor serialExecutor, @NotNull Executor asyncExecutor, @NotNull PluginLogger logger) {
        this.options = options;
        this.codec = codec;
        this.serialExecutor = serialExecutor;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    @Override
    public void initialize() {
        try {
            // 建立链接
            MongoClientSettings.Builder builder = MongoClientSettings.builder()
                    .uuidRepresentation(UuidRepresentation.STANDARD)
                    .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(10, TimeUnit.SECONDS))
                    .applyConnectionString(new ConnectionString(this.options.url()));
            if (!this.options.username().isEmpty()) {
                builder.credential(MongoCredential.createCredential(
                        this.options.username(),
                        this.options.authSource(),
                        this.options.password().toCharArray())
                );
            }
            this.mongoClient = MongoClients.create(builder.build());
            this.mongoDatabase = this.mongoClient.getDatabase(this.options.database());
            this.mongoDatabase.runCommand(new Document("ping", 1));
            // 读取文档集合 & 建立索引
            MongoCollection<Document> userCollection = this.mongoDatabase.getCollection(this.options.collectionPrefix() + "users");
            MongoCollection<Document> snapshotCollection = this.mongoDatabase.getCollection(this.options.collectionPrefix() + "snapshots");
            userCollection.createIndex(Indexes.ascending(USER_FIELD_NAME));
            snapshotCollection.createIndex(Indexes.compoundIndex(Indexes.ascending(DocumentSnapshotCodec.FIELD_PLAYER), Indexes.descending(DocumentSnapshotCodec.FIELD_TIMESTAMP, DocumentSnapshotCodec.FIELD_ID)));
            this.users = userCollection;
            this.snapshots = snapshotCollection;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to connect mongodb at " + this.options.url(), throwable);
        }
    }

    // 未完成初始化 (连接失败或索引冲突) 时明确失败, 调用方按存储不可用裁决
    private MongoCollection<Document> snapshotCollection() {
        MongoCollection<Document> collection = this.snapshots;
        if (collection == null) {
            throw new IllegalStateException("mongo storage is not initialized");
        }
        return collection;
    }

    private MongoCollection<Document> userCollection() {
        MongoCollection<Document> collection = this.users;
        if (collection == null) {
            throw new IllegalStateException("mongo storage is not initialized");
        }
        return collection;
    }

    @Override
    public void close() {
        if (this.mongoClient != null) {
            this.mongoClient.close();
        }
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<Snapshot>> latestSnapshot(@NotNull UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            Document document = this.snapshotCollection().find(byPlayer(player)).sort(NEWEST_FIRST).limit(1).first();
            return this.decodeDocument(document);
        }, this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<Snapshot>> snapshot(@NotNull UUID snapshotId) {
        return CompletableFuture.supplyAsync(() -> this.decodeDocument(this.snapshotCollection().find(byId(snapshotId)).first()), this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<List<SnapshotMeta>> listSnapshots(@NotNull SnapshotQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            // 投影排除数据体, 列表查询不搬运物品字节
            FindIterable<Document> found = this.snapshotCollection().find(filterOf(query))
                    .projection(Projections.exclude(DocumentSnapshotCodec.FIELD_DATA))
                    .sort(NEWEST_FIRST);
            if (query.limit() != SnapshotQuery.NO_LIMIT) {
                found = found.limit(query.limit());
            }
            List<SnapshotMeta> metas = new ArrayList<>();
            for (Document document : found) {
                metas.add(DocumentSnapshotCodec.decodeMeta(document));
            }
            return metas;
        }, this.asyncExecutor);
    }

    // 未设置的条件不下发谓词, 免得给索引塞一个极值边界
    private static Bson filterOf(SnapshotQuery query) {
        List<Bson> filters = new ArrayList<>(4);
        filters.add(byPlayer(query.player()));
        if (query.from() != SnapshotQuery.UNBOUNDED_FROM) {
            filters.add(Filters.gte(DocumentSnapshotCodec.FIELD_TIMESTAMP, new Date(query.from())));
        }
        if (query.to() != SnapshotQuery.UNBOUNDED_TO) {
            filters.add(Filters.lte(DocumentSnapshotCodec.FIELD_TIMESTAMP, new Date(query.to())));
        }
        switch (query.pinned()) {
            case PINNED -> filters.add(Filters.eq(DocumentSnapshotCodec.FIELD_PINNED, true));
            case UNPINNED -> filters.add(Filters.eq(DocumentSnapshotCodec.FIELD_PINNED, false));
            case ANY -> {
            }
        }
        return filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
    }

    @Override
    @NotNull
    public CompletableFuture<SaveResult> saveSnapshot(@NotNull Snapshot snapshot) {
        return CompletableFuture.supplyAsync(() -> this.insert(snapshot), this.serialExecutor.executor(snapshot.meta().player()));
    }

    @Override
    @NotNull
    public CompletableFuture<Integer> saveSnapshots(@NotNull Collection<Snapshot> snapshots) {
        return CompletableFuture.supplyAsync(() -> {
            // 一批文档一次往返, 冲突或超限的个别快照回落到单份守卫路径
            // documents 与 encoded 同序, 批量写错误按下标精确映射回快照, 同玩家多份也不会张冠李戴
            List<Document> documents = new ArrayList<>(snapshots.size());
            List<Snapshot> encoded = new ArrayList<>(snapshots.size());
            List<Snapshot> fallback = new ArrayList<>();
            for (Snapshot snapshot : snapshots) {
                try {
                    documents.add(this.encodeGuarded(snapshot));
                    encoded.add(snapshot);
                } catch (Exception exception) {
                    this.logger.warn("Skipping snapshot of " + snapshot.meta().player() + " in bulk save", exception);
                }
            }
            int saved = 0;
            if (!documents.isEmpty()) {
                try {
                    this.snapshotCollection().insertMany(documents, new InsertManyOptions().ordered(false));
                    saved = documents.size();
                } catch (MongoBulkWriteException exception) {
                    saved = exception.getWriteResult().getInsertedCount();
                    for (BulkWriteError error : exception.getWriteErrors()) {
                        fallback.add(encoded.get(error.getIndex()));
                    }
                }
            }
            // 单份回落失败只影响自己, 已落库的份数照常返回
            for (int i = 0; i < fallback.size(); i++) {
                Snapshot snapshot = fallback.get(i);
                try {
                    this.insert(snapshot);
                    saved++;
                } catch (RuntimeException exception) {
                    this.logger.warn("Failed to save snapshot of " + snapshot.meta().player() + " in bulk fallback", exception);
                }
            }
            return saved;
        }, this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<Integer> rotate(@NotNull UUID player, int maxUnpinned) {
        // 轮转要删的正是该玩家刚写进去的历史, 与他的写入有先后关系, 因此同样落在他的 worker 上
        return CompletableFuture.supplyAsync(() -> {
            Bson unpinned = Filters.and(byPlayer(player), Filters.eq(DocumentSnapshotCodec.FIELD_PINNED, false));
            // 上限为 0 表示未固定的历史一份不留
            if (maxUnpinned <= 0) {
                return (int) this.snapshotCollection().deleteMany(unpinned).getDeletedCount();
            }
            long count = this.snapshotCollection().countDocuments(unpinned);
            if (count <= maxUnpinned) return 0;
            // 第 maxUnpinned 新的未固定快照是保留下界, 采集时刻早于它的全部删除
            Document boundary = this.snapshotCollection().find(unpinned)
                    .sort(NEWEST_FIRST)
                    .skip(maxUnpinned - 1).limit(1)
                    .projection(Projections.include(DocumentSnapshotCodec.FIELD_TIMESTAMP))
                    .first();
            if (boundary == null) return 0;
            Date cutoff = readTimestamp(boundary);
            if (cutoff == null) return 0;
            return (int) this.snapshotCollection().deleteMany(Filters.and(unpinned, Filters.lt(DocumentSnapshotCodec.FIELD_TIMESTAMP, cutoff))).getDeletedCount();
        }, this.serialExecutor.executor(player));
    }

    @Override
    @NotNull
    public CompletableFuture<Boolean> setPinned(@NotNull UUID snapshotId, boolean pinned) {
        // 只有 snapshotId 无从分桶; 改的是已存在快照的标志位, 与落库次序无关, 走公共池即可
        return CompletableFuture.supplyAsync(() -> this.snapshotCollection().updateOne(byId(snapshotId),
                new Document("$set", new Document(DocumentSnapshotCodec.FIELD_PINNED, pinned))).getModifiedCount() > 0, this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<Boolean> deleteSnapshot(@NotNull UUID snapshotId) {
        return CompletableFuture.supplyAsync(() -> this.snapshotCollection().deleteOne(byId(snapshotId)).getDeletedCount() > 0, this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<Void> ensureUser(@NotNull UUID player, @NotNull String name) {
        return CompletableFuture.runAsync(() -> this.userCollection().replaceOne(Filters.eq("_id", player),
                new Document("_id", player).append(USER_FIELD_NAME, name).append(USER_FIELD_LAST_SEEN, new Date()),
                new ReplaceOptions().upsert(true)), this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<UUID>> lookupUser(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            Document document = this.userCollection().find(Filters.eq(USER_FIELD_NAME, name)).sort(Sorts.descending(USER_FIELD_LAST_SEEN)).limit(1).first();
            return document == null ? Optional.empty() : Optional.ofNullable(document.get("_id", UUID.class));
        }, this.asyncExecutor);
    }

    // 编码 + 大小守卫, 单份与批量共用
    private Document encodeGuarded(Snapshot snapshot) throws IOException {
        Document document = this.codec.encode(snapshot);
        long payload = payloadBytes(document);
        if (payload > MAX_PAYLOAD_BYTES) {
            throw new IOException("snapshot of " + snapshot.meta().player() + " carries " + payload + " payload bytes, over the document limit");
        }
        return document;
    }

    /**
     * 写入一份快照. 主键是快照 id, 因此重复写入同一份是幂等的; 写入不改变次序, 落库后回查一次
     * 确认自己是不是采集时刻最晚的一份, 以此把"插回历史中段"与"有第二个写方"报给调用方.
     * <p>
     * 先写后查是有意的: 无论次序判定结果如何, 数据先落地. 回查与写入之间可能有别人插入,
     * 因此次序结果是诊断信号而不是并发控制手段 —— 互斥由会话锁负责.
     */
    private SaveResult insert(Snapshot snapshot) {
        Document document;
        try {
            document = this.encodeGuarded(snapshot);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
        SnapshotMeta meta = snapshot.meta();
        try {
            this.snapshotCollection().insertOne(document);
        } catch (MongoWriteException exception) {
            if (exception.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw exception;
            return SaveResult.DUPLICATE;
        }
        Document newest = this.snapshotCollection().find(byPlayer(meta.player()))
                .sort(NEWEST_FIRST)
                .limit(1)
                .projection(Projections.include(DocumentSnapshotCodec.FIELD_ID, DocumentSnapshotCodec.FIELD_TIMESTAMP, DocumentSnapshotCodec.FIELD_SERVER))
                .first();
        if (newest == null || meta.id().equals(newest.get(DocumentSnapshotCodec.FIELD_ID, UUID.class))) {
            return SaveResult.SAVED;
        }
        // 调用方按场景裁决: 启动插回历史是预期的, 在线保存走到这里意味着会话锁失效
        this.logger.warn("Snapshot " + meta.id() + " of " + meta.player() + " captured at " + meta.timestamp()
                + " landed behind a newer one from " + readString(newest.get(DocumentSnapshotCodec.FIELD_SERVER))
                + " captured at " + readTimestamp(newest));
        return SaveResult.SAVED_OUT_OF_ORDER;
    }

    // 解码为空表示不存在, 损坏快照以异常上抛让调用方裁决而不是伪装成不存在
    private Optional<Snapshot> decodeDocument(@Nullable Document document) {
        if (document == null) return Optional.empty();
        DecodedSnapshot decoded = this.codec.decode(document);
        if (decoded instanceof DecodedSnapshot.Valid valid) {
            return Optional.of(valid.snapshot());
        }
        DecodedSnapshot.Invalid invalid = (DecodedSnapshot.Invalid) decoded;
        throw new CompletionException(new IOException("stored snapshot is invalid (" + invalid.reason() + "): " + invalid.detail()));
    }

    // 递归统计数据体中全部二进制载荷, 结构化字段任意深度的 Binary 也计入守卫
    private static long payloadBytes(Document document) {
        Document data = document.get(DocumentSnapshotCodec.FIELD_DATA, Document.class);
        return data == null ? 0 : binaryBytesOf(data);
    }

    private static long binaryBytesOf(Object value) {
        return switch (value) {
            case Binary binary -> binary.getData().length;
            case byte[] bytes -> bytes.length;
            case Document document -> {
                long bytes = 0;
                for (Map.Entry<String, Object> entry : document.entrySet()) {
                    bytes += binaryBytesOf(entry.getValue());
                }
                yield bytes;
            }
            case List<?> list -> {
                long bytes = 0;
                int size = list.size();
                for (int i = 0; i < size; i++) {
                    bytes += binaryBytesOf(list.get(i));
                }
                yield bytes;
            }
            case null, default -> 0;
        };
    }

    private static Bson byPlayer(UUID player) {
        return Filters.eq(DocumentSnapshotCodec.FIELD_PLAYER, player);
    }

    private static Bson byId(UUID snapshotId) {
        return Filters.eq(DocumentSnapshotCodec.FIELD_ID, snapshotId);
    }

    @Nullable
    private static Date readTimestamp(Document document) {
        return document.get(DocumentSnapshotCodec.FIELD_TIMESTAMP) instanceof Date date ? date : null;
    }

    private static String readString(@Nullable Object value) {
        return value instanceof String string ? string : "unknown";
    }
}
