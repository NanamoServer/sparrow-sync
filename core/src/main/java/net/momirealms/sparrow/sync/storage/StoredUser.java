package net.momirealms.sparrow.sync.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/** 导入导出使用的玩家名字映射, lastSeen 为 Unix 毫秒, 来源没有最后上线时间时使用 0 表示未知. */
@ApiStatus.Internal
public record StoredUser(@NotNull UUID player, @NotNull String name, long lastSeen) {
}
