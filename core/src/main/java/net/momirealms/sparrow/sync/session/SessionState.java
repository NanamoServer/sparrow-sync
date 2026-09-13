package net.momirealms.sparrow.sync.session;

import org.jetbrains.annotations.NotNull;

/** 每次登录对应一个会话, 依次完成数据准备、应用和退出保存. */
public enum SessionState {
    PREPARING,  // 读取并准备登录数据
    APPLYING,   // 在 Join 阶段应用剩余数据
    ACTIVE,     // 数据已就绪, 可以接受保存请求
    SAVING,     // 停止接受新保存请求, 等待最终保存, 仍持有锁
    CLOSED;     // 会话已结束, 解锁可能尚未完成

    public boolean canTransitionTo(@NotNull SessionState next) {
        return switch (this) {
            case PREPARING ->   next == /*正常应用数据*/ APPLYING || next == /*第一次进服无数据*/   ACTIVE || next == /*中途退出或发生异常*/ CLOSED;
            case APPLYING ->    next == /*正常应用数据*/ ACTIVE   || next == /*中途退出或发生异常*/ CLOSED;
            case ACTIVE ->      next == /*正常采集数据*/ SAVING;
            case SAVING ->      next == /*正常落库*/    CLOSED;
            case CLOSED -> false;
        };
    }
}
