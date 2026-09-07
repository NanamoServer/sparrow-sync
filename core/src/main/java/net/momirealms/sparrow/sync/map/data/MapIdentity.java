package net.momirealms.sparrow.sync.map.data;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

// 地图同步标识, 包含地图源 ID、来源地图 ID 和全局地图 ID.
@ApiStatus.Internal
public record MapIdentity(@NotNull MapSource source, int globalId) {
    public MapIdentity {
        if (globalId >= 0) {
            throw new IllegalArgumentException("map identity requires a negative global id");
        }
    }

    // 生成副本专用维度标识.
    @NotNull
    public String replicaDimension() {
        // 十六进制保留地图同步标识, 同时满足资源路径的字符限制.
        HexFormat hex = HexFormat.of();
        return "sparrow-sync:map/"
                + hex.formatHex(this.source.ownerId().getBytes(StandardCharsets.UTF_8)) + "/"
                + this.source.id() + "/"
                + this.globalId;
    }
}
