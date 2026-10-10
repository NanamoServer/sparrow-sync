package net.momirealms.sparrow.sync.cluster;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record LockValue(@NotNull String serverId, @NotNull String instanceId, @NotNull String token) {

    @NotNull
    public String format() {
        return this.serverId + ':' + this.instanceId + ':' + this.token;
    }

    @Nullable
    public static LockValue parse(@NotNull String raw) {
        int tokenSeparator = raw.lastIndexOf(':');
        int instanceSeparator = raw.lastIndexOf(':', tokenSeparator - 1);
        if (instanceSeparator <= 0 || tokenSeparator <= instanceSeparator + 1 || tokenSeparator == raw.length() - 1) return null;
        return new LockValue(raw.substring(0, instanceSeparator), raw.substring(instanceSeparator + 1, tokenSeparator), raw.substring(tokenSeparator + 1));
    }
}
