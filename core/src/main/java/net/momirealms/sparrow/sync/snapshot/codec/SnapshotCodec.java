package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 快照编解码接口, 支持二进制、数据库文档等格式.
 * 编码错误抛出异常, 解码错误返回带原因的 {@link DecodedSnapshot.Invalid}.
 * @param <T> 编码后的数据类型
 */
public interface SnapshotCodec<T> {
    int MINIMUM_SUPPORTED_VERSION = 1; // 可读取的最早格式版本
    int CURRENT_VERSION = DependencyVersions.SNAPSHOT_FORMAT_VERSION;    // 帧头以单字节保存版本, 取值须为 1..255

    @NotNull
    T encode(@NotNull Snapshot snapshot) throws IOException;

    @NotNull
    DecodedSnapshot decode(@NotNull T encoded);
}
