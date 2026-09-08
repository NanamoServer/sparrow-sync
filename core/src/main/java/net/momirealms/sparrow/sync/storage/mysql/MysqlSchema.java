package net.momirealms.sparrow.sync.storage.mysql;

import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import org.jdbi.v3.core.Handle;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 保存当前完整表结构, 空库初始化时直接创建这一版本.
@ApiStatus.Internal
public final class MysqlSchema {
    public static final int CURRENT_VERSION = DependencyVersions.MYSQL_SCHEMA_VERSION; // 表布局独立版本, 正式发布后的结构变更需补齐旧库升级步骤
    private static final String TABLE_OPTIONS = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"; // 事务表使用完整 Unicode 字符集, 字符串比较区分大小写

    private MysqlSchema() {
    }

    // 由迁移入口持锁调用, meta 与初始化目标记录已就绪; 同一版本中断后可重入.
    public static void initialize(@NotNull Handle handle, @NotNull String prefix) {
        // 列表查询使用独立元信息列, 同一玩家按时间和 id 稳定排序.
        handle.execute("CREATE TABLE IF NOT EXISTS `" + prefix + "snapshots` ("
                + "`id` BINARY(16) NOT NULL PRIMARY KEY, `player` BINARY(16) NOT NULL, `ts` BIGINT NOT NULL, "
                + "`cause` VARCHAR(32) NOT NULL, `pinned` BOOLEAN NOT NULL, `server` VARCHAR(255) NOT NULL, "
                + "`format` INT NOT NULL, `mc_data` INT NOT NULL, `data` LONGBLOB NOT NULL, "
                + "KEY `snapshot_player_time` (`player`, `ts`, `id`), KEY `snapshot_player_pin_time` (`player`, `pinned`, `ts`, `id`))" + TABLE_OPTIONS);
        // 名称检索同时保留最后出现时间, 可从同名记录中选择最近使用者.
        handle.execute("CREATE TABLE IF NOT EXISTS `" + prefix + "users` ("
                + "`player` BINARY(16) NOT NULL PRIMARY KEY, `name` VARCHAR(64) NOT NULL, `last_seen` BIGINT NOT NULL, "
                + "KEY `user_name_seen` (`name`, `last_seen`))" + TABLE_OPTIONS);
        // 来源服务器和原始编号唯一定位地图, 更新时间索引供按时间筛选清理使用.
        handle.execute("CREATE TABLE IF NOT EXISTS `" + prefix + "maps` ("
                + "`global_id` INT NOT NULL PRIMARY KEY, `owner` VARCHAR(255) NOT NULL, `origin_id` INT NOT NULL, "
                + "`data_version` INT NOT NULL, `updated_at` BIGINT NOT NULL, `data` LONGBLOB NOT NULL, "
                + "UNIQUE KEY `map_source` (`owner`, `origin_id`), KEY `map_updated_at` (`updated_at`))" + TABLE_OPTIONS);
        // 重入时保留已分配的编号进度, 仅在计数器缺失时从 0 开始.
        handle.execute("INSERT INTO `" + prefix + "meta` (`id`, `value`) VALUES ('maps', 0) ON DUPLICATE KEY UPDATE `id` = 'maps'");
    }
}
