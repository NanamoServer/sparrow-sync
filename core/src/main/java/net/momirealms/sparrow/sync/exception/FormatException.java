package net.momirealms.sparrow.sync.exception;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 快照解码失败, 携带精确的失败原因, 让各载体形态对同一故障给出一致的结论.
 */
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

    /**
     * 解码失败的原因分类.
     */
    public enum InvalidReason {
        BAD_MAGIC,              // 字节头的魔数不匹配, 数据来源不是本插件的快照
        UNSUPPORTED_FORMAT,     // 快照格式版本高于本版本能解析的范围
        UNSUPPORTED_COMPRESSION,// 字节头声明的压缩算法本版本不认识
        CORRUPTED               // 数据损坏或结构不完整
    }
}
