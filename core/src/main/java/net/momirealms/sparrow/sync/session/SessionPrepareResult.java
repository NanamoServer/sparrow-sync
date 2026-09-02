package net.momirealms.sparrow.sync.session;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/** 会话进入世界前的数据准备结果. */
@ApiStatus.Internal
public sealed interface SessionPrepareResult {

    /** 数据准备完成, 会话可以进入应用阶段. */
    record Ready() implements SessionPrepareResult {
    }

    /** 关键数据无法准备, 本次登录不能放行. */
    record Failed(@NotNull String detail) implements SessionPrepareResult {
    }

    /** 会话在准备完成前已经失效. */
    record Rejected() implements SessionPrepareResult {
    }
}
