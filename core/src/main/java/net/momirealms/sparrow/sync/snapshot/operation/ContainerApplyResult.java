package net.momirealms.sparrow.sync.snapshot.operation;

import org.jetbrains.annotations.ApiStatus;

// 声明顺序对应跨服协议序号, 已有常量的位置必须保持稳定
@ApiStatus.Internal
public enum ContainerApplyResult {
    /** 本次请求中的容器数据均已应用. */
    APPLIED,
    /** 玩家或服务不可用, 本次未即时应用. */
    OFFLINE,
    /** 准备或应用未完整成功, 玩家可能已收到部分容器数据. */
    FAILED,
    /** 跨服通信失败或超时, 无法确认目标服的执行结果. */
    UNAVAILABLE
}