package net.momirealms.sparrow.sync.storage.mysql;

import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;

final class MysqlSnapshotRowMapper implements RowMapper<SnapshotRow> {

    /**
     * 将 MySQL 的查询结果映射为 SnapshotRow, 供行编解码器继续解析二进制内容.
     * 读取结果集当前行的元信息、格式版本与二进制帧.
     */
    @NotNull
    @Override
    public SnapshotRow map(ResultSet result, StatementContext context) throws SQLException {
        return new SnapshotRow(readMeta(result), result.getObject("format", Integer.class), result.getBytes("data"));
    }

    static SnapshotMeta readMeta(ResultSet result) throws SQLException {
        return new SnapshotMeta(
                UUIDUtils.fromBytes(result.getBytes("id")),
                UUIDUtils.fromBytes(result.getBytes("player")),
                result.getObject("ts", Long.class),
                SaveCause.byName(result.getString("cause")),
                result.getObject("pinned", Boolean.class),
                result.getString("server"),
                result.getObject("mc_data", Integer.class)
        );
    }
}
