package net.momirealms.sparrow.sync.snapshot.operation;

import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@ApiStatus.Internal
public sealed interface SnapshotDetailResult {
    record Ready(@NotNull Snapshot snapshot, @NotNull Map<DataKey, Preview> previews) implements SnapshotDetailResult {
    }

    record Overview() implements SnapshotDetailResult {
    }

    NotFound NOT_FOUND = new NotFound();
    record NotFound() implements SnapshotDetailResult {
    }

    record Invalid(@NotNull FormatException.InvalidReason reason, @NotNull String detail) implements SnapshotDetailResult {
    }

    record Failed(@NotNull Throwable failure) implements SnapshotDetailResult {
    }

    record Archive(@NotNull SnapshotFiles.ExceptionEntry entry, @NotNull SnapshotDetailResult result) {
    }

    sealed interface Preview {

        // 支持预览但尚未读取; rawLength 为压缩前的 NBT 字节数, 无法确定时为 -1
        record Unloaded(int rawLength) implements Preview {
        }

        record Ready(@NotNull Object value) implements Preview {
        }

        record Unsupported(boolean registered, int rawLength, boolean discardUnknown) implements Preview {
        }

        record Failed(@NotNull String detail) implements Preview {
        }
    }
}
