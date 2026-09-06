package net.momirealms.sparrow.sync.map.data;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

// 一份可跨服的地图数据的ID
@ApiStatus.Internal
public record MapIdentity(@NotNull MapSource source, int globalId) {
    public MapIdentity {
        if (globalId >= 0) {
            throw new IllegalArgumentException("map identity requires a negative global id");
        }
    }

    // 生成副本使用的隔离维度名称.
    @NotNull
    public String replicaDimension() {
        // 十六进制保留完整来源身份, 同时满足资源路径的字符限制.
        HexFormat hex = HexFormat.of();
        return "sparrow-sync:map/"
                + hex.formatHex(this.source.ownerId().getBytes(StandardCharsets.UTF_8)) + "/"
                + this.source.id() + "/"
                + this.globalId;
    }
}
