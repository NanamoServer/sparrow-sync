package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import org.jetbrains.annotations.NotNull;

public sealed interface DecodedSnapshot {

    record Valid(@NotNull Snapshot snapshot) implements DecodedSnapshot {
    }

    record Invalid(@NotNull FormatException.InvalidReason reason, @NotNull String detail) implements DecodedSnapshot {
    }
}
