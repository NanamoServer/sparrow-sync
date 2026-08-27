package net.momirealms.sparrow.sync.snapshot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 快照的元数据. version 是同一玩家内单调递增的版本号, 快照的新旧裁决只看它; timestamp 为毫秒, 仅作展示.
 *
 * @param player        玩家 UUID
 * @param version       玩家内单调递增的快照版本号
 * @param timestamp     创建时刻的毫秒时间戳
 * @param cause         保存原因
 * @param pinned        是否固定, 固定快照豁免轮转清理
 * @param server        创建快照的服务器名
 * @param mcDataVersion 物品 NBT 对应的 Minecraft data version, 跨版本升级的依据
 */
public record SnapshotMeta(@NotNull UUID player, long version, long timestamp, @NotNull SaveCause cause, boolean pinned, @NotNull String server, int mcDataVersion) {

    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    @NotNull
    public SnapshotMeta withPinned(boolean pinned) {
        if (this.pinned == pinned) return this;
        return new SnapshotMeta(this.player, this.version, this.timestamp, this.cause, pinned, this.server, this.mcDataVersion);
    }

    public static final class Builder {
        private @Nullable UUID player;
        private long version;
        private long timestamp;
        private @Nullable SaveCause cause;
        private boolean pinned;
        private String server = "";
        private int mcDataVersion;

        private Builder() {
        }

        @NotNull
        public Builder player(@NotNull UUID player) {
            this.player = player;
            return this;
        }

        @NotNull
        public Builder version(long version) {
            this.version = version;
            return this;
        }

        @NotNull
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        @NotNull
        public Builder cause(@NotNull SaveCause cause) {
            this.cause = cause;
            return this;
        }

        @NotNull
        public Builder pinned(boolean pinned) {
            this.pinned = pinned;
            return this;
        }

        @NotNull
        public Builder server(@NotNull String server) {
            this.server = server;
            return this;
        }

        @NotNull
        public Builder mcDataVersion(int mcDataVersion) {
            this.mcDataVersion = mcDataVersion;
            return this;
        }

        @NotNull
        public SnapshotMeta build() {
            if (this.player == null) throw new IllegalStateException("snapshot meta requires a player");
            if (this.cause == null) throw new IllegalStateException("snapshot meta requires a save cause");
            return new SnapshotMeta(this.player, this.version, this.timestamp, this.cause, this.pinned, this.server, this.mcDataVersion);
        }
    }
}
