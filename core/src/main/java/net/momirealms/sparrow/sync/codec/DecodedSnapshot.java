package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.sync.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import org.jetbrains.annotations.NotNull;

/**
 * 快照解码结果.
 * 损坏或不受支持的数据则为 {@link Invalid} 并携带原因.
 */
public sealed interface DecodedSnapshot {

    record Valid(@NotNull Snapshot snapshot) implements DecodedSnapshot {
    }

    record Invalid(@NotNull FormatException.InvalidReason reason, @NotNull String detail) implements DecodedSnapshot {
    }
}
