package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    @Nullable
    public static MapIdentity fromReplicaDimension(@NotNull String dimension) {
        if (!dimension.startsWith("sparrow-sync:map/")) return null;
        String[] parts = dimension.split("/", -1);
        if (parts.length != 5) return null;
        try {
            HexFormat hex = HexFormat.of();
            MapIdentity identity = new MapIdentity(new String(hex.parseHex(parts[1]), StandardCharsets.UTF_8),
                    new MapSource(new String(hex.parseHex(parts[2]), StandardCharsets.UTF_8), Integer.parseInt(parts[3])), Integer.parseInt(parts[4]));
            return identity.replicaDimension().equals(dimension) ? identity : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
