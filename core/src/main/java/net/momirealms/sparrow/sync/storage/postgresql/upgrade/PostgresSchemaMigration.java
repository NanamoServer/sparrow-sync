package net.momirealms.sparrow.sync.storage.postgresql.upgrade;

import org.jdbi.v3.core.Handle;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public interface PostgresSchemaMigration {
    int targetVersion();

    // 在迁移事务内执行, 普通 DDL 和版本号一同提交; 失败由事务回滚.
    void migrate(@NotNull Handle handle, @NotNull String prefix);
}
