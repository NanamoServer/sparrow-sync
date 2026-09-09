package net.momirealms.sparrow.sync.session.operation;

import net.momirealms.sparrow.sync.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@ApiStatus.Internal
public sealed interface SnapshotDetailResult {
    record Ready(@NotNull Snapshot snapshot, @NotNull Map<DataKey, Preview> previews) implements SnapshotDetailResult {
    }

    record NotFound() implements SnapshotDetailResult {
    }

    record Invalid(@NotNull FormatException.InvalidReason reason, @NotNull String detail) implements SnapshotDetailResult {
    }

    record Failed(@NotNull Throwable failure) implements SnapshotDetailResult {
    }

    record Archive(@NotNull SnapshotFiles.ExceptionEntry entry, @NotNull SnapshotDetailResult result) {
    }

    sealed interface Preview {
        record Ready(@NotNull Object value) implements Preview {
        }

        record Unsupported(boolean registered) implements Preview {
        }

        record Failed(@NotNull String detail) implements Preview {
        }
    }
}
