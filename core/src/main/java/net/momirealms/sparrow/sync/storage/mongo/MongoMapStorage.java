package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.MapData;
import net.momirealms.sparrow.sync.map.MapIdentity;
import net.momirealms.sparrow.sync.map.MapSource;
import net.momirealms.sparrow.sync.map.MapStorage;
import net.momirealms.sparrow.sync.map.StoredMap;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;

@ApiStatus.Internal
public final class MongoMapStorage implements MapStorage {
    private static final long MAX_SEQUENCE = -(long) Integer.MIN_VALUE; // 可分配负数 int 的数量
    private final MongoCollection<Document> maps; // 完整地图记录, 含身份、数据版本与二进制 NBT
    private final MongoCollection<Document> counters; // 当前集合前缀下共用一个自增序列, 已分配或空洞 ID 均不复用
    private final String ownerId;
    private final Executor executor;

    public MongoMapStorage(@NotNull MongoDatabase database, @NotNull String prefix, @NotNull String ownerId, @NotNull Executor executor) {
        // 全局 ID 和首份画面按多数确认, 查询只读取已提交的地图记录.
        this.maps = database.getCollection(prefix + "maps")
                .withReadPreference(ReadPreference.primary())
                .withReadConcern(ReadConcern.MAJORITY).withWriteConcern(WriteConcern.MAJORITY);
        this.counters = database.getCollection(prefix + "map_counters")
                .withReadPreference(ReadPreference.primary())
                .withReadConcern(ReadConcern.MAJORITY).withWriteConcern(WriteConcern.MAJORITY);
        this.ownerId = ownerId;
        this.executor = executor;
    }

    // 启动时完成索引与序列准备, 全局 ID 使用 Mongo 原生 _id 唯一索引.
    public void initialize() {
        this.maps.createIndex(Indexes.ascending("owner", "origin_id"), new IndexOptions().unique(true).name("map_source"));
        // 多个服务器可同时初始化同一组集合, 已存在计数器沿用原序列
        try {
            this.counters.updateOne(eq("_id", "maps"), setOnInsert("sequence", 0L), new UpdateOptions().upsert(true));
        } catch (MongoWriteException exception) {
            if (exception.getError().getCategory() != ErrorCategory.DUPLICATE_KEY
                    || this.counters.find(eq("_id", "maps")).first() == null) {
                throw exception;
            }
        }
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<StoredMap>> find(int globalId) {
        return CompletableFuture.supplyAsync(() -> this.read(this.maps.find(eq("_id", globalId)).first()), this.executor);
    }

    @Override
    @NotNull
    public CompletableFuture<StoredMap> register(@NotNull MapSource source, @NotNull MapData initial) {
        return CompletableFuture.supplyAsync(() -> {
            this.checkOwner(source);
            Optional<StoredMap> existing = this.read(this.maps.find(this.sourceFilter(source)).first());
            if (existing.isPresent()) return existing.get();
            // 先完成内容编码再分配 ID, 映射写入时已经具备可读取的完整画面
            Binary payload = encode(initial);
            // 条件自增到负 int 空间的末端即停止, 分配失败产生的空洞保留.
            Document counter = this.counters.findOneAndUpdate(
                    and(eq("_id", "maps"), gte("sequence", 0L), lt("sequence", MAX_SEQUENCE)),
                    inc("sequence", 1L), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (counter == null) {
                throw new IllegalStateException("map id sequence is missing, invalid or exhausted");
            }
            int globalId = Math.toIntExact(-((Number) counter.get("sequence")).longValue());
            MapIdentity identity = new MapIdentity(source, globalId);
            Document document = new Document("_id", globalId)
                    .append("owner", source.ownerId()).append("origin_id", source.id())
                    .append("data_version", initial.dataVersion()).append("data", payload);
            try {
                this.maps.insertOne(document);
            } catch (MongoWriteException exception) {
                if (exception.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw exception;
                // 并发登记同一来源时返回胜出的完整记录, 其他唯一索引冲突保留原始失败.
                return this.read(this.maps.find(this.sourceFilter(source)).first()).orElseThrow(() -> exception);
            }
            return new StoredMap(identity, initial);
        }, this.executor);
    }

    @Override
    @NotNull
    public CompletableFuture<Void> update(@NotNull MapIdentity identity, @NotNull MapData data) {
        return CompletableFuture.runAsync(() -> {
            this.checkOwner(identity.source());
            // 同时匹配来源和全局 ID, 更新仅作用于已登记的这份身份
            long matched = this.maps.updateOne(
                    and(this.sourceFilter(identity.source()), eq("_id", identity.globalId())),
                    combine(set("data_version", data.dataVersion()), set("data", encode(data)))
            ).getMatchedCount();
            if (matched != 1) {
                throw new IllegalStateException("map identity does not match a registered map");
            }
        }, this.executor);
    }

    // 核对本服是否具有这张原图的上传权限.
    private void checkOwner(MapSource source) {
        if (!this.ownerId.equals(source.ownerId())) {
            throw new IllegalArgumentException("only the origin owner may upload map content");
        }
    }

    // 限定当前集合中的一份原图身份.
    private Bson sourceFilter(MapSource source) {
        return and(eq("owner", source.ownerId()), eq("origin_id", source.id()));
    }

    // 将数据库文档还原为独立地图记录, 并核对存储格式.
    private Optional<StoredMap> read(@Nullable Document document) {
        if (document == null) return Optional.empty();
        if (!(document.get("owner") instanceof String owner) || !(document.get("origin_id") instanceof Integer originId)
                || !(document.get("_id") instanceof Integer globalId) || !(document.get("data_version") instanceof Integer dataVersion)
                || !(document.get("data") instanceof Binary binary)) {
            throw new IllegalStateException("malformed stored map");
        }
        try {
            CompoundTag tag = NBT.fromBytes(binary.getData());
            if (tag == null) {
                throw new IOException("map payload is empty");
            }
            return Optional.of(new StoredMap(new MapIdentity(new MapSource(owner, originId), globalId), new MapData(dataVersion, tag)));
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    // 编码地图 NBT, 并给 BSON 文档的身份字段预留空间.
    private static Binary encode(MapData data) {
        try {
            return new Binary(data.encode());
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }
}
