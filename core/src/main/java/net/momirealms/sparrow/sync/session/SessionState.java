package net.momirealms.sparrow.sync.session;

import org.jetbrains.annotations.NotNull;

/**
 * 会话生命周期状态.
 * 玩家的每次进服对应一个会话, 从数据准备开始, 到最终保存落库 settle 结束.
 */
public enum SessionState {
    PREPARING,  // 读库与预解码进行中, 这通常发生在配置阶段.
    APPLYING,   // 预解码产物正在应用到玩家实体, 这通常发生在 onJoin 阻塞执行.
    ACTIVE,     // 数据已就位, 玩家正常游玩. 唯一允许退出保存的状态.
    SAVING,     // 退出采集已完成, 已经发起数据库保存请求. 此时仍持有锁.
    CLOSED;     // 结束保存, 释放锁.

    /**
     * 本状态能否转移到 next.
     */
    public boolean canTransitionTo(@NotNull SessionState next) {
        return switch (this) {
            case PREPARING ->   next == /*正常应用数据*/ APPLYING || next == /*第一次进服无数据*/   ACTIVE || next == /*中途退出或发生异常*/ CLOSED;
            case APPLYING ->    next == /*正常应用数据*/ ACTIVE   || next == /*中途退出或发生异常*/ CLOSED;
            case ACTIVE ->      next == /*正常采集数据*/ SAVING;
            case SAVING ->      next == /*正常落库*/    CLOSED;
            case CLOSED -> false;
        };
    }

    /**
     * 会话是否停在中间态 (非 ACTIVE 非 CLOSED), 即进服或退出保存尚未走完.
     */
    public boolean transitional() {
        return this != ACTIVE && this != CLOSED;
    }
}
