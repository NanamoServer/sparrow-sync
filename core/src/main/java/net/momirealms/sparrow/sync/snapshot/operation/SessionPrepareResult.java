package net.momirealms.sparrow.sync.snapshot.operation;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public sealed interface SessionPrepareResult {
    /** 关键数据无法准备, 本次登录不能放行. */
    record Failed(@NotNull String detail) implements SessionPrepareResult {
    }

    /** 数据准备完成, 会话可以进入应用阶段. */
    Ready READY = new Ready();
    record Ready() implements SessionPrepareResult {
    }

    /** 会话在准备完成前已经失效. */
    Rejected REJECTED = new Rejected();
    record Rejected() implements SessionPrepareResult {
    }
}
