package net.momirealms.sparrow.sync.map.data;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

// 来源地图与负数全局 ID 的对应关系.
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
        // 用十六进制编码地图源 ID, 避免出现资源路径不允许的字符.
        HexFormat hex = HexFormat.of();
        return "sparrow-sync:map/"
                + hex.formatHex(this.source.ownerId().getBytes(StandardCharsets.UTF_8)) + "/"
                + this.source.id() + "/"
                + this.globalId;
    }
}
