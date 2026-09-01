package net.momirealms.sparrow.sync.snapshot;

import org.jetbrains.annotations.NotNull;

/**
 * 快照的保存原因.
 */
public enum SaveCause {
    DISCONNECT,         // 离线
    WORLD_CHANGE,       // 切换世界
    GAME_MODE_CHANGE,   // 切换游戏模式
    PRE_DEATH,          // 死亡清理前
    DEATH,              // 死亡清理后
    SHUTDOWN,           // 服务器关闭
    INTERVAL,           // 定时
    COMMAND,            // 命令
    RESTORE,            // 回滚
    EDIT,               // 编辑
    API,                // API 保存
    UNKNOWN;            // 未知

    private static final SaveCause[] VALUES = values();

    /**
     * 按名称解析保存原因, 无法识别的名称返回 {@link #UNKNOWN}.
     */
    @NotNull
    public static SaveCause byName(@NotNull String name) {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i].name().equals(name)) return VALUES[i];
        }
        return UNKNOWN;
    }
}
