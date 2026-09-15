package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StoredUser;
import java.io.IOException;
import net.momirealms.sparrow.sync.map.MapStorage;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.bson.Document;
import org.bson.BsonMaximumSizeExceededException;
import org.bson.UuidRepresentation;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public final class MongoStorageProvider implements StorageProvider {
    // 预热心跳事件类.
    // 首次使用是在关服时, 但是可能会落在关服竞争窗口里: mongoClient.close() 只中断 monitor 线程不等它退出才会加载.
    // Paper 随后关闭插件类加载器, 导致 monitor 的最后一轮心跳必然失败并首次索要这个类, 于是会以 NoClassDefFoundError 结束.
    @SuppressWarnings("unused")
    private static final Class<?>[] HEARTBEAT_EVENT_CLASSES = {
            com.mongodb.event.ServerHeartbeatStartedEvent.class,
            com.mongodb.event.ServerHeartbeatSucceededEvent.class,
            com.mongodb.event.ServerHeartbeatFailedEvent.class
    };

    public static final String USER_FIELD_NAME = "name";          // lookupUser 按这个字段查名字
    public static final String USER_FIELD_LAST_SEEN = "lastSeen"; // 同名记录按这个时间取最近一条
    private static final long MAX_PAYLOAD_BYTES = 15L * 1024 * 1024; // 二进制载荷上限, 给 MongoDB 的 16 MB 文档限制留出余量
    private static final Bson NEWEST_FIRST = Sorts.descending(DocumentSnapshotCodec.FIELD_TIMESTAMP, DocumentSnapshotCodec.FIELD_ID); // 新快照在前, _id 确定同一采集时间下的顺序

    private final PluginConfig.MongoOptions options;
    private final DocumentSnapshotCodec codec;
    private final PlayerSerialExecutor serialExecutor; // 同一玩家的写入和轮转排队执行
    private final Executor asyncExecutor;              // 没有顺序要求的数据库操作执行器
    private final SyncLogger logger;

    private MongoClient mongoClient;
    private MongoDatabase mongoDatabase;
    private volatile MongoCollection<Document> users;
    private volatile MongoCollection<Document> snapshots;
    private MongoMapStorage maps;

    public MongoStorageProvider(@NotNull PluginConfig.MongoOptions options,
                                @NotNull DocumentSnapshotCodec codec,
                                @NotNull PlayerSerialExecutor serialExecutor,
                                @NotNull Executor asyncExecutor,
                                @NotNull SyncLogger logger) {
        this.options = options;
        this.codec = codec;
        this.serialExecutor = serialExecutor;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    @Override
    public void initialize() {
        try {
            // UUID 使用标准 BSON 表示, 与 DocumentSnapshotCodec 的读写约定保持一致
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
            Configurator.setLevel("org.mongodb.driver.client", Level.WARN);
            Configurator.setLevel("org.mongodb.driver.cluster", Level.WARN);
            this.mongoClient = MongoClients.create(builder.build());
            this.mongoDatabase = this.mongoClient.getDatabase(this.options.database());
            this.mongoDatabase.runCommand(new Document("ping", 1));
            // 读取文档集合 & 建立索引
            MongoCollection<Document> userCollection = this.mongoDatabase.getCollection(this.options.collectionPrefix() + "users");
            MongoCollection<Document> snapshotCollection = this.mongoDatabase.getCollection(this.options.collectionPrefix() + "snapshots");
            IndexReconciler.reconcile(this.logger, this.mongoDatabase, this.options.collectionPrefix());
            MongoMapStorage mapStorage = new MongoMapStorage(this.mongoDatabase, this.options.collectionPrefix(), this.asyncExecutor);
            mapStorage.initialize();
            this.users = userCollection;
            this.snapshots = snapshotCollection;
            this.maps = mapStorage;
        } catch (Throwable throwable) {
            this.shutdown();
            throw new IllegalStateException("Failed to connect mongodb at " + this.options.url(), throwable);
        }
    }

    private MongoCollection<Document> snapshotCollection() {
        MongoCollection<Document> collection = this.snapshots;
        if (collection == null) {
            throw new IllegalStateException("mongo storage is not initialized");
        }
        return collection;
    }

    @Override
    @NotNull
    public MapStorage maps() {
        if (this.maps == null) {
            throw new IllegalStateException("mongo storage is not initialized");
        }
        return this.maps;
    }

    private MongoCollection<Document> userCollection() {
        MongoCollection<Document> collection = this.users;
        if (collection == null) {
            throw new IllegalStateException("mongo storage is not initialized");
        }
        return collection;
    }

    @Override
    public void shutdown() {
        this.users = null;
        this.snapshots = null;
        this.maps = null;
        if (this.mongoClient != null) {
            this.mongoClient.close();
            this.mongoClient = null;
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
    public CompletableFuture<Optional<SnapshotMeta>> snapshotMeta(@NotNull UUID snapshotId) {
        return CompletableFuture.supplyAsync(() -> Optional.ofNullable(this.snapshotCollection().find(byId(snapshotId))
                .projection(Projections.exclude(DocumentSnapshotCodec.FIELD_DATA)).first()).map(DocumentSnapshotCodec::decodeMeta), this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<List<SnapshotMeta>> listSnapshots(@NotNull SnapshotQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            // 列表只读元数据, 数据体通常很大, 不从 MongoDB 拉回进程
            FindIterable<Document> found = this.snapshotCollection().find(filterOf(query))
                    .projection(Projections.exclude(DocumentSnapshotCodec.FIELD_DATA))
                    .sort(NEWEST_FIRST)
                    .skip(query.offset());
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

    @Override
    @NotNull
    public CompletableFuture<Long> countSnapshots(@NotNull SnapshotQuery query) {
        return CompletableFuture.supplyAsync(() -> this.snapshotCollection().countDocuments(filterOf(query)), this.asyncExecutor);
    }

    // UNBOUNDED_* 是 Java 侧哨兵值, 没有边界时不向 MongoDB 添加对应条件
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
    public CompletableFuture<SaveOutcome> saveSnapshotOutcome(@NotNull Snapshot snapshot) {
        return CompletableFuture.supplyAsync(() -> this.insert(snapshot), this.serialExecutor.executor(snapshot.meta().player()));
    }

    @Override
    @NotNull
    public CompletableFuture<Integer> rotate(@NotNull UUID player, int maxUnpinned) {
        // 轮转和写入都进玩家自己的队列, 两种操作按提交顺序执行
        return CompletableFuture.supplyAsync(() -> {
            Bson unpinned = Filters.and(byPlayer(player), Filters.eq(DocumentSnapshotCodec.FIELD_PINNED, false));
            // 0 表示不保留未固定快照
            if (maxUnpinned <= 0) {
                return (int) this.snapshotCollection().deleteMany(unpinned).getDeletedCount();
            }
            long count = this.snapshotCollection().countDocuments(unpinned);
            if (count <= maxUnpinned) return 0;
            // 找到最后一份需要保留的快照, 比它更早的记录都可以删除
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
        return CompletableFuture.supplyAsync(() -> this.snapshotCollection().updateOne(byId(snapshotId),
                new Document("$set", new Document(DocumentSnapshotCodec.FIELD_PINNED, pinned))).getModifiedCount() > 0, this.asyncExecutor);
    }

    @NotNull
    @Override
    public CompletableFuture<Boolean> deleteSnapshot(@NotNull UUID snapshotId) {
        return CompletableFuture.supplyAsync(() -> this.snapshotCollection().deleteOne(byId(snapshotId)).getDeletedCount() > 0, this.asyncExecutor);
    }

    @NotNull
    @Override
    public CompletableFuture<Void> ensureUser(@NotNull UUID player, @NotNull String name) {
        return CompletableFuture.runAsync(() -> this.userCollection().replaceOne(Filters.eq("_id", player),
                new Document("_id", player).append(USER_FIELD_NAME, name).append(USER_FIELD_LAST_SEEN, new Date()),
                new ReplaceOptions().upsert(true)), this.asyncExecutor);
    }

    @NotNull
    @Override
    public CompletableFuture<Optional<UUID>> lookupUser(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            Document document = this.userCollection().find(Filters.eq(USER_FIELD_NAME, name)).sort(Sorts.descending(USER_FIELD_LAST_SEEN)).limit(1).first();
            return document == null ? Optional.empty() : Optional.ofNullable(document.get("_id", UUID.class));
        }, this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<List<Snapshot>> scanSnapshots(long before, @Nullable UUID after, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            Bson filter = Filters.lt(DocumentSnapshotCodec.FIELD_TIMESTAMP, new Date(before));
            if (after != null) filter = Filters.and(filter, Filters.gt("_id", after));
            List<Snapshot> result = new ArrayList<>();
            try (MongoCursor<Document> cursor = this.snapshotCollection().find(filter).sort(Sorts.ascending("_id")).limit(limit).batchSize(limit).iterator()) {
                while (cursor.hasNext()) {
                    result.add(this.decodeDocument(cursor.next()).orElseThrow());
                }
            }
            return result;
        }, this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<List<StoredUser>> scanUsers(@Nullable UUID after, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            Bson filter = after == null ? new Document() : Filters.gt("_id", after);
            List<StoredUser> result = new ArrayList<>();
            try (MongoCursor<Document> cursor = this.userCollection().find(filter).sort(Sorts.ascending("_id")).limit(limit).batchSize(limit).iterator()) {
                while (cursor.hasNext()) {
                    Document user = cursor.next();
                    result.add(new StoredUser(user.get("_id", UUID.class), user.getString(USER_FIELD_NAME), user.getDate(USER_FIELD_LAST_SEEN).getTime()));
                }
            }
            return result;
        }, this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<Void> importUser(@NotNull StoredUser user) {
        return CompletableFuture.runAsync(() -> this.userCollection().replaceOne(Filters.eq("_id", user.player()),
                new Document("_id", user.player()).append(USER_FIELD_NAME, user.name()).append(USER_FIELD_LAST_SEEN, new Date(user.lastSeen())), new ReplaceOptions().upsert(true)), this.asyncExecutor);
    }

    @Override
    @NotNull
    public CompletableFuture<SaveOutcome> importSnapshot(@NotNull Snapshot snapshot) {
        return CompletableFuture.supplyAsync(() -> {
            Document document;
            try {
                document = this.codec.encode(snapshot);
            } catch (IOException failure) {
                return new SaveOutcome(SaveResult.REJECTED_MALFORMED, failure);
            }
            if (document.get(DocumentSnapshotCodec.FIELD_DATA, Binary.class).getData().length > MAX_PAYLOAD_BYTES) {
                return new SaveOutcome(SaveResult.REJECTED_OVERSIZED, new IOException("Snapshot exceeds the storage payload limit"));
            }
            try {
                this.snapshotCollection().replaceOne(byId(snapshot.meta().id()), document, new ReplaceOptions().upsert(true));
                return new SaveOutcome(SaveResult.SAVED, null);
            } catch (MongoWriteException failure) {
                int code = failure.getError().getCode();
                SaveResult result = code == 121 || failure.getError().getCategory() == ErrorCategory.DUPLICATE_KEY
                        ? SaveResult.REJECTED_MALFORMED : code == 10334 || code == 17419 ? SaveResult.REJECTED_OVERSIZED : MongoFailureClassifier.classify(failure);
                return new SaveOutcome(result, failure);
            } catch (MongoException | BsonMaximumSizeExceededException failure) {
                return new SaveOutcome(MongoFailureClassifier.classify(failure), failure);
            }
        }, this.asyncExecutor);
    }

    // 单份和批量写入共用这里的编码与大小检查
    // 写入成功后再看一次最新快照, 让调用方知道它是否落进了历史中段
    private SaveOutcome insert(Snapshot snapshot) {
        SnapshotMeta meta = snapshot.meta();
        Document document;
        // 编码阶段
        try {
            document = this.codec.encode(snapshot);
        } catch (Throwable throwable) {
            SaveResult result = MongoFailureClassifier.classify(throwable);
            if (result.retriable()) return new SaveOutcome(result, throwable);
            this.logger.error(LogCategory.STORAGE, meta.player(), null, throwable, LogConstants.STORAGE_ENCODE_FAILED, meta.player().toString());
            return new SaveOutcome(result, null);
        }
        // 写前守卫, 16 MB 文档上限检查
        long payload = document.get(DocumentSnapshotCodec.FIELD_DATA, Binary.class).getData().length;
        if (payload > MAX_PAYLOAD_BYTES) {
            this.logger.error(LogCategory.STORAGE, meta.player(), null, LogConstants.STORAGE_OVERSIZED, meta.player().toString(), String.valueOf(payload));
            return new SaveOutcome(SaveResult.REJECTED_OVERSIZED, null);
        }
        // 开写数据
        try {
            this.snapshotCollection().insertOne(document);
        } catch (MongoWriteException exception) {
            if (exception.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                return this.failed(meta, exception);
            }
            // insertOne 只报撞了唯一索引, 不报撞的是哪一条. 查一次 _id 才能确认这份快照真的在库里, 否则任何其他来源的写入失败都会被当成 DUPLICATE, 导致数据静默丢失
            if (this.snapshotCollection().find(byId(meta.id())).projection(Projections.include(DocumentSnapshotCodec.FIELD_ID)).first() == null) {
                this.logger.error(LogCategory.STORAGE, meta.player(), null, exception, LogConstants.STORAGE_CONSTRAINT_CONFLICT, meta.player().toString());
                return new SaveOutcome(SaveResult.REJECTED_MALFORMED, null);
            }
            return new SaveOutcome(SaveResult.DUPLICATE, null);
        } catch (Throwable throwable) {
            return this.failed(meta, throwable);
        }
        // 数据已经落库, 回查结果只用来报告顺序
        Document newest = this.snapshotCollection().find(byPlayer(meta.player()))
                .sort(NEWEST_FIRST)
                .limit(1)
                .projection(Projections.include(DocumentSnapshotCodec.FIELD_ID, DocumentSnapshotCodec.FIELD_TIMESTAMP, DocumentSnapshotCodec.FIELD_SERVER))
                .first();
        if (newest == null || meta.id().equals(newest.get(DocumentSnapshotCodec.FIELD_ID, UUID.class))) {
            return new SaveOutcome(SaveResult.SAVED, null);
        }
        // 启动恢复时插入旧快照很正常, 在线保存出现乱序通常说明会话锁失效
        this.logger.file(LogCategory.STORAGE, meta.player(), null, LogConstants.STORAGE_OUT_OF_ORDER,
                meta.id().toString(), meta.player().toString(), String.valueOf(meta.timestamp()),
                readString(newest.get(DocumentSnapshotCodec.FIELD_SERVER)), String.valueOf(readTimestamp(newest)));
        return new SaveOutcome(SaveResult.SAVED_OUT_OF_ORDER, null);
    }

    // 确定性失败在存储边界详细记录, 可重试失败交给保存链按尝试次数收敛
    private SaveOutcome failed(SnapshotMeta meta, Throwable throwable) {
        SaveResult result = MongoFailureClassifier.classify(throwable);
        if (result.retriable()) {
            return new SaveOutcome(result, throwable);
        }
        this.logger.error(LogCategory.STORAGE, meta.player(), null, throwable, LogConstants.STORAGE_WRITE_REJECTED, meta.player().toString());
        return new SaveOutcome(result, null);
    }

    // 查不到文档就返回空, 已存在但损坏的快照交给调用方处理
    private Optional<Snapshot> decodeDocument(@Nullable Document document) {
        if (document == null) return Optional.empty();
        DecodedSnapshot decoded = this.codec.decode(document);
        if (decoded instanceof DecodedSnapshot.Valid(Snapshot snapshot)) {
            return Optional.of(snapshot);
        }
        DecodedSnapshot.Invalid invalid = (DecodedSnapshot.Invalid) decoded;
        throw new CompletionException(new FormatException(invalid.reason(), "stored snapshot is invalid (" + invalid.reason() + "): " + invalid.detail()));
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
