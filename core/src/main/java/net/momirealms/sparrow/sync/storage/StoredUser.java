package net.momirealms.sparrow.sync.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/** 导入导出使用的玩家名字映射, lastSeen 保留数据库中的最后上线时间, 单位为 Unix 毫秒. */
@ApiStatus.Internal
public record StoredUser(@NotNull UUID player, @NotNull String name, long lastSeen) {
}
