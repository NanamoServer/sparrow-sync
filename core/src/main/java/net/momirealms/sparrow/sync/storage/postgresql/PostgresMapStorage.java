package net.momirealms.sparrow.sync.storage.postgresql;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.MapStorage;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.List;
import net.momirealms.sparrow.sync.map.data.MapArchiveRecord;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@ApiStatus.Internal
public final class PostgresMapStorage implements MapStorage {
    private static final long MAX_SEQUENCE = -(long) Integer.MIN_VALUE;
    private static final String COLUMNS = "\"global_id\", \"owner\", \"origin_id\", \"data_version\", \"updated_at\", \"data\"";

    private final Jdbi jdbi;
    private final String maps;
    private final String meta;
    private final Executor executor;

    public PostgresMapStorage(@NotNull Jdbi jdbi, @NotNull String prefix, @NotNull Executor executor) {
        this.jdbi = jdbi;
        this.maps = "\"" + prefix + "maps\"";
        this.meta = "\"" + prefix + "meta\"";
        this.executor = executor;
    }

    @Override
    @NotNull
    public CompletableFuture<List<MapArchiveRecord>> scan(int beforeId, int limit) {
        return CompletableFuture.supplyAsync(() -> this.jdbi.withHandle(handle -> handle.createQuery("SELECT " + COLUMNS + " FROM " + this.maps + " WHERE \"global_id\" < :before ORDER BY \"global_id\" DESC LIMIT :limit")
                .bind("before", beforeId).bind("limit", limit).map((result, context) -> new MapArchiveRecord(new MapIdentity(new MapSource(result.getString("owner"), result.getInt("origin_id")), result.getInt("global_id")), result.getInt("data_version"), result.getLong("updated_at"), result.getBytes("data"))).list()), this.executor);
    }

    @Override
    @NotNull
    public CompletableFuture<Long> sequence() {
        return CompletableFuture.supplyAsync(() -> this.jdbi.withHandle(handle -> handle.createQuery("SELECT \"value\" FROM " + this.meta + " WHERE \"id\" = 'maps'").mapTo(Long.class).one()), this.executor);
    }

    @Override
    @NotNull
    public CompletableFuture<Void> importSequence(long sequence) {
        return CompletableFuture.runAsync(() -> this.jdbi.useHandle(handle -> handle.createUpdate("UPDATE " + this.meta + " SET \"value\" = GREATEST(\"value\", :sequence) WHERE \"id\" = 'maps'").bind("sequence", sequence).execute()), this.executor);
    }

    @Override
    @NotNull
    public CompletableFuture<Void> importMap(@NotNull MapArchiveRecord map) {
        return CompletableFuture.runAsync(() -> this.jdbi.useTransaction(handle -> {
            handle.createUpdate("INSERT INTO " + this.maps + " (\"global_id\", \"owner\", \"origin_id\", \"data_version\", \"updated_at\", \"data\") VALUES (:id, :owner, :origin, :version, :updated, :data) ON CONFLICT (\"global_id\") DO UPDATE SET \"owner\" = EXCLUDED.\"owner\", \"origin_id\" = EXCLUDED.\"origin_id\", \"data_version\" = EXCLUDED.\"data_version\", \"updated_at\" = EXCLUDED.\"updated_at\", \"data\" = EXCLUDED.\"data\"")
                    .bind("id", map.identity().globalId()).bind("owner", map.identity().source().ownerId()).bind("origin", map.identity().source().id())
                    .bind("version", map.dataVersion()).bind("updated", map.updatedAt()).bind("data", map.data()).execute();
            handle.createUpdate("UPDATE " + this.meta + " SET \"value\" = GREATEST(\"value\", :sequence) WHERE \"id\" = 'maps'").bind("sequence", -(long) map.identity().globalId()).execute();
        }), this.executor);
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<StoredMap>> find(int globalId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<Row> row = this.jdbi.withHandle(handle -> handle.createQuery("SELECT " + COLUMNS + " FROM " + this.maps + " WHERE \"global_id\" = :id")
                    .bind("id", globalId).map((result, context) -> readRow(result)).findOne());
            return row.map(PostgresMapStorage::decode);
        }, this.executor);
    }

    // 首份内容编码完成后分配全局 ID, 已提交的序列进度在后续插入失败时保留.
    @Override
    @NotNull
    public CompletableFuture<StoredMap> register(@NotNull MapSource source, @NotNull MapData initial) {
        return CompletableFuture.supplyAsync(() -> {
            validateSource(source);
            Optional<Row> existing = this.findSource(source);
            if (existing.isPresent()) return decode(existing.get());
            byte[] payload = encode(initial);
            int globalId = this.allocateId();
            MapIdentity identity = new MapIdentity(source, globalId);
            try {
                this.jdbi.useHandle(handle -> handle.createUpdate("INSERT INTO " + this.maps + " (\"global_id\", \"owner\", \"origin_id\", \"data_version\", \"updated_at\", \"data\") VALUES (:id, :owner, :origin, :version, :updated, :data)")
                        .bind("id", globalId).bind("owner", source.ownerId()).bind("origin", source.id())
                        .bind("version", initial.dataVersion()).bind("updated", System.currentTimeMillis()).bind("data", payload).execute());
            } catch (JdbiException exception) {
                SQLException sql = PostgresFailureClassifier.sqlCause(exception);
                if (sql == null || !"23505".equals(sql.getSQLState())) {
                    throw exception;
                }
                // 来源相同的竞争返回胜出记录, 其他唯一键冲突保留失败.
                return decode(this.findSource(source).orElseThrow(() -> exception));
            }
            return new StoredMap(identity, initial);
        }, this.executor);
    }

    // 单条 UPDATE RETURNING 分配并返回本次编号, 提交后的缺口可保留.
    private int allocateId() {
        return this.jdbi.withHandle(handle -> {
            long next = handle.createQuery("UPDATE " + this.meta + " SET \"value\" = \"value\" + 1 WHERE \"id\" = 'maps' AND \"value\" >= 0 AND \"value\" < :max RETURNING \"value\"")
                    .bind("max", MAX_SEQUENCE).mapTo(Long.class).findOne()
                    .orElseThrow(() -> new IllegalStateException("map id sequence is missing, invalid or exhausted"));
            return Math.toIntExact(-next);
        });
    }

    @Override
    @NotNull
    public CompletableFuture<Void> update(@NotNull MapIdentity identity, @NotNull MapData data) {
        return CompletableFuture.runAsync(() -> {
            validateSource(identity.source());
            byte[] payload = encode(data);
            // PostgreSQL 返回命中行数, 同值更新也能验证完整身份.
            int updated = this.jdbi.withHandle(handle -> handle.createUpdate("UPDATE " + this.maps + " SET \"data_version\" = :version, \"updated_at\" = :updated, \"data\" = :data WHERE \"global_id\" = :id AND \"owner\" = :owner AND \"origin_id\" = :origin AND \"updated_at\" IS NOT NULL")
                    .bind("version", data.dataVersion()).bind("updated", System.currentTimeMillis()).bind("data", payload)
                    .bind("id", identity.globalId()).bind("owner", identity.source().ownerId()).bind("origin", identity.source().id()).execute());
            if (updated != 1) {
                throw new IllegalStateException("map identity does not match a registered map with a supported format");
            }
        }, this.executor);
    }

    private Optional<Row> findSource(MapSource source) {
        return this.jdbi.withHandle(handle -> handle.createQuery("SELECT " + COLUMNS + " FROM " + this.maps + " WHERE \"owner\" = :owner AND \"origin_id\" = :origin")
                .bind("owner", source.ownerId()).bind("origin", source.id()).map((result, context) -> readRow(result)).findOne());
    }

    private static void validateSource(MapSource source) {
        String owner = source.ownerId();
        if (owner.codePointCount(0, owner.length()) > 255 || owner.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("PostgreSQL map owners must contain at most 255 Unicode code points and must not contain NUL");
        }
    }

    // 时间属于持久化元数据, 在边界校验后只向地图领域传递身份和原生内容.
    private static Row readRow(ResultSet result) throws SQLException {
        byte[] data = result.getBytes("data");
        if (result.getObject("updated_at", Long.class) == null || data == null) {
            throw new IllegalStateException("malformed stored map");
        }
        MapSource source = new MapSource(result.getString("owner"), result.getInt("origin_id"));
        validateSource(source);
        return new Row(new MapIdentity(source, result.getInt("global_id")), result.getInt("data_version"), data);
    }

    // NBT 编解码在连接归还后执行, 数据体沿用 MapData 的原生格式.
    private static StoredMap decode(Row row) {
        try {
            CompoundTag tag = NBT.fromBytes(row.data());
            if (tag == null) {
                throw new IOException("map payload is empty");
            }
            return new StoredMap(row.identity(), new MapData(row.dataVersion(), tag));
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private static byte[] encode(MapData data) {
        try {
            return data.encode();
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private record Row(MapIdentity identity, int dataVersion, byte[] data) {
    }
}
