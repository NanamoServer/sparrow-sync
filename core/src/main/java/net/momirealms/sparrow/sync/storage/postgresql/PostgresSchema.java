package net.momirealms.sparrow.sync.storage.postgresql;

import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import org.jdbi.v3.core.Handle;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class PostgresSchema {
    public static final int CURRENT_VERSION = DependencyVersions.POSTGRESQL_SCHEMA_VERSION;

    private PostgresSchema() {
    }

    // 建表与版本发布在同一事务内完成, 文本使用确定性的逐字节比较.
    public static void initialize(@NotNull Handle handle, @NotNull String prefix) {
        handle.execute("CREATE TABLE \"" + prefix + "snapshots\" ("
                + "id UUID PRIMARY KEY, player UUID NOT NULL, ts BIGINT NOT NULL, "
                + "cause VARCHAR(32) COLLATE \"C\" NOT NULL, pinned BOOLEAN NOT NULL, server VARCHAR(255) COLLATE \"C\" NOT NULL, "
                + "format INTEGER NOT NULL, mc_data INTEGER NOT NULL, data BYTEA NOT NULL)");
        handle.execute("CREATE INDEX \"" + prefix + "snapshot_player_time\" ON \"" + prefix + "snapshots\" (player, ts, id)");
        handle.execute("CREATE INDEX \"" + prefix + "snapshot_player_pin_time\" ON \"" + prefix + "snapshots\" (player, pinned, ts, id)");
        handle.execute("CREATE TABLE \"" + prefix + "users\" ("
                + "player UUID PRIMARY KEY, name VARCHAR(64) COLLATE \"C\" NOT NULL, last_seen BIGINT NOT NULL)");
        handle.execute("CREATE INDEX \"" + prefix + "user_name_seen\" ON \"" + prefix + "users\" (name, last_seen, player)");
        handle.execute("CREATE TABLE \"" + prefix + "maps\" ("
                + "global_id INTEGER PRIMARY KEY, owner VARCHAR(255) COLLATE \"C\" NOT NULL, origin_id INTEGER NOT NULL, "
                + "data_version INTEGER NOT NULL, updated_at BIGINT NOT NULL, data BYTEA NOT NULL, UNIQUE (owner, origin_id))");
        handle.execute("CREATE INDEX \"" + prefix + "map_updated_at\" ON \"" + prefix + "maps\" (updated_at)");
        handle.execute("INSERT INTO \"" + prefix + "meta\" (id, value) VALUES ('maps', 0)");
    }
}
