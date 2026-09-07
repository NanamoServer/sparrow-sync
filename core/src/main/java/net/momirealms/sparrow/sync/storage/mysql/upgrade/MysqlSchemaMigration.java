package net.momirealms.sparrow.sync.storage.mysql.upgrade;

import org.jdbi.v3.core.Handle;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

// 将已有表结构升级到下一代.
@ApiStatus.Internal
public interface MysqlSchemaMigration {

    // 目标版本从 2 开始, 已发布的步骤保持对应历史布局.
    int targetVersion();

    // DDL 中断后会再次执行本步, 实现需识别中间状态并恢复; 成功返回后由管线公布目标版本.
    void migrate(@NotNull Handle handle, @NotNull String prefix);
}
