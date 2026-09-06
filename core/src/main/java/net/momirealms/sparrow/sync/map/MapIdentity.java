package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@ApiStatus.Internal
public record MapIdentity(@NotNull String clusterId, @NotNull MapSource source, int globalId) {
    public MapIdentity {
        if (clusterId.isBlank() || globalId >= 0) {
            throw new IllegalArgumentException("map identity requires a cluster and a negative global id");
        }
    }

    @NotNull
    public String replicaDimension() {
        // 十六进制保留完整来源身份, 同时满足资源路径的字符限制.
        HexFormat hex = HexFormat.of();
        return "sparrow-sync:map/" + hex.formatHex(this.clusterId.getBytes(StandardCharsets.UTF_8)) + "/"
                + hex.formatHex(this.source.ownerId().getBytes(StandardCharsets.UTF_8)) + "/" + this.source.id() + "/" + this.globalId;
    }
}
