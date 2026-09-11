package net.momirealms.sparrow.sync.storage;

import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.ApiStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@ApiStatus.Internal
public final class SnapshotRowMapper implements RowMapper<SnapshotRow> {
    @NotNull
    @Override
    public SnapshotRow map(ResultSet result, StatementContext context) throws SQLException {
        return new SnapshotRow(readMeta(result), result.getObject("format", Integer.class), result.getBytes("data"));
    }

    @NotNull
    public static SnapshotMeta readMeta(ResultSet result) throws SQLException {
        return new SnapshotMeta(
                readUuid(result, "id"),
                readUuid(result, "player"),
                result.getObject("ts", Long.class),
                SaveCause.byName(result.getString("cause")),
                result.getObject("pinned", Boolean.class),
                result.getString("server"),
                result.getObject("mc_data", Integer.class)
        );
    }

    // PostgreSQL 原生 UUID 返回 UUID 对象, MySQL BINARY(16) 返回高位在前的字节数组.
    private static UUID readUuid(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value instanceof UUID uuid ? uuid : UUIDUtils.fromBytes((byte[]) value);
    }
}
