package net.momirealms.sparrow.sync.snapshot.exception;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/** 快照格式错误, 携带可供不同解码器共用的错误分类. */
public class FormatException extends IOException {
    private final InvalidReason reason;

    public FormatException(@NotNull InvalidReason reason, @NotNull String message) {
        super(message);
        this.reason = reason;
    }

    @NotNull
    public InvalidReason reason() {
        return this.reason;
    }

    public enum InvalidReason {
        UNSUPPORTED_FORMAT,     // 快照格式版本或标志位超出当前可读范围
        UNSUPPORTED_COMPRESSION,// 无法识别块头声明的压缩算法
        CORRUPTED               // 数据损坏或结构不完整
    }
}
