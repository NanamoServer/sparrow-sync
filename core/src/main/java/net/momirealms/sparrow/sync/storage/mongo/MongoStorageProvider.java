package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import net.momirealms.sparrow.sync.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
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
            this.mongoClient = MongoClients.create(builder.build());
            this.mongoDatabase = this.mongoClient.getDatabase(this.options.database());
            this.mongoDatabase.runCommand(new Document("ping", 1));
            // 读取文档集合 & 建立索引
            MongoCollection<Document> metaCollection = this.mongoDatabase.getCollection(this.options.collectionPrefix() + "meta");
            MongoCollection<Document> userCollection = this.mongoDatabase.getCollection(this.options.collectionPrefix() + "users");
            MongoCollection<Document> snapshotCollection = this.mongoDatabase.getCollection(this.options.collectionPrefix() + "snapshots");
            IndexReconciler.reconcile(this.logger, metaCollection, userCollection, snapshotCollection);
            this.users = userCollection;
            this.snapshots = snapshotCollection;
        } catch (Throwable throwable) {
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

    private MongoCollection<Document> userCollection() {
        MongoCollection<Document> collection = this.users;
        if (collection == null) {
            throw new IllegalStateException("mongo storage is not initialized");
        }
        return collection;
    }

    @Override
    public void shutdown() {
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
            // 列表只读元数据, 数据体通常很大, 不从 MongoDB 拉回进程
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
    public CompletableFuture<SaveResult> saveSnapshot(@NotNull Snapshot snapshot) {
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

    // 单份和批量写入共用这里的编码与大小检查
    // 写入成功后再看一次最新快照, 让调用方知道它是否落进了历史中段
    private SaveResult insert(Snapshot snapshot) {
        SnapshotMeta meta = snapshot.meta();
        Document document;
        // 编码阶段
        try {
            document = this.codec.encode(snapshot);
        } catch (Throwable throwable) {
            this.logger.error(TranslationManager.console(LogConstants.STORAGE_ENCODE_FAILED, meta.player().toString()), throwable);
            return MongoFailureClassifier.classify(throwable);
        }
        // 写前守卫, 16 MB 文档上限检查
        long payload = payloadBytes(document);
        if (payload > MAX_PAYLOAD_BYTES) {
            this.logger.error(TranslationManager.console(LogConstants.STORAGE_OVERSIZED, meta.player().toString(), String.valueOf(payload)));
            return SaveResult.REJECTED_OVERSIZED;
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
                this.logger.error(TranslationManager.console(LogConstants.STORAGE_CONSTRAINT_CONFLICT, meta.player().toString()), exception);
                return SaveResult.REJECTED_MALFORMED;
            }
            return SaveResult.DUPLICATE;
        } catch (Throwable throwable) {
            return this.failed(meta, throwable);
        }
        // 数据已经落库, 回查结果只用来报告顺序 todo 思考这一块, 真的有必要回查吗(回查有一次成本)? 区分 STORAGE_OUT_OF_ORDER 有用吗?
        Document newest = this.snapshotCollection().find(byPlayer(meta.player()))
                .sort(NEWEST_FIRST)
                .limit(1)
                .projection(Projections.include(DocumentSnapshotCodec.FIELD_ID, DocumentSnapshotCodec.FIELD_TIMESTAMP, DocumentSnapshotCodec.FIELD_SERVER))
                .first();
        if (newest == null || meta.id().equals(newest.get(DocumentSnapshotCodec.FIELD_ID, UUID.class))) {
            return SaveResult.SAVED;
        }
        // 启动恢复时插入旧快照很正常, 在线保存出现乱序通常说明会话锁失效
        this.logger.warn(TranslationManager.console(LogConstants.STORAGE_OUT_OF_ORDER,
                meta.id().toString(), meta.player().toString(), String.valueOf(meta.timestamp()),
                readString(newest.get(DocumentSnapshotCodec.FIELD_SERVER)), String.valueOf(readTimestamp(newest))));
        return SaveResult.SAVED_OUT_OF_ORDER;
    }

    // 写入失败按可否重试归类, 存储层知道细节所以日志在这里打全
    private SaveResult failed(SnapshotMeta meta, Throwable throwable) {
        SaveResult result = MongoFailureClassifier.classify(throwable);
        String key = result.retriable() ? LogConstants.STORAGE_WRITE_RETRIABLE : LogConstants.STORAGE_WRITE_REJECTED;
        this.logger.error(TranslationManager.console(key, meta.player().toString()), throwable); // todo 打印的数据全一些, 带上uuid的前几位.
        return result;
    }

    // 查不到文档就返回空, 已存在但损坏的快照交给调用方处理
    private Optional<Snapshot> decodeDocument(@Nullable Document document) {
        if (document == null) return Optional.empty();
        DecodedSnapshot decoded = this.codec.decode(document);
        if (decoded instanceof DecodedSnapshot.Valid(Snapshot snapshot)) {
            return Optional.of(snapshot);
        }
        DecodedSnapshot.Invalid invalid = (DecodedSnapshot.Invalid) decoded;
        throw new CompletionException(new IOException("stored snapshot is invalid (" + invalid.reason() + "): " + invalid.detail()));
    }

    // data 里可能嵌套 Document 和 List, 所有层级的二进制内容都计入大小
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
