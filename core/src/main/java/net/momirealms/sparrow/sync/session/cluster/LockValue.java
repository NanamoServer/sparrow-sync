package net.momirealms.sparrow.sync.session.cluster;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record LockValue(@NotNull String serverId, @NotNull String token) {

    @NotNull
    public String format() {
        return this.serverId + ':' + this.token;
    }

    @Nullable
    public static LockValue parse(@NotNull String raw) {
        int separator = raw.lastIndexOf(':');
        if (separator <= 0 || separator == raw.length() - 1) return null;
        return new LockValue(raw.substring(0, separator), raw.substring(separator + 1));
    }
}
