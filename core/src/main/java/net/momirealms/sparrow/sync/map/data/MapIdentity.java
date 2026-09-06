package net.momirealms.sparrow.sync.map.data;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    // 识别原生副本维度中保存的身份.
    @Nullable
    public static MapIdentity fromReplicaDimension(@NotNull String dimension) {
        if (!dimension.startsWith("sparrow-sync:map/")) return null;
        String[] parts = dimension.split("/", -1);
        if (parts.length != 4) return null;
        try {
            HexFormat hex = HexFormat.of();
            MapIdentity identity = new MapIdentity(
                    new MapSource(new String(hex.parseHex(parts[1]), StandardCharsets.UTF_8), Integer.parseInt(parts[2])),
                    Integer.parseInt(parts[3])
            );
            // 重新编码核对规范形式, 使大小写、数字写法等差异不能伪装成同一副本维度 todo 有点过度防御
            return identity.replicaDimension().equals(dimension) ? identity : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
