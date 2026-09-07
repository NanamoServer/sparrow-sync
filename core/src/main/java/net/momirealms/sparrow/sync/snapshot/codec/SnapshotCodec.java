package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 快照与某种载体形态之间的编解码器. 每种载体一个实现: 二进制形态用于跨服消息与本地落盘,
 * 文档形态用于 MongoDB 落库, JSON 形态用于调试导出, 行形态用于 MySQL.
 * 编码失败向上抛出, 解码失败落为 {@link DecodedSnapshot.Invalid} 并携带原因.
 *
 * @param <T> 载体类型
 */
public interface SnapshotCodec<T> {
    int CURRENT_VERSION = 2;    // 快照格式版本, 以 1 字节写入帧头, 取值必须保持在 1..255

    @NotNull
    T encode(@NotNull Snapshot snapshot) throws IOException;

    @NotNull
    DecodedSnapshot decode(@NotNull T encoded);
}
