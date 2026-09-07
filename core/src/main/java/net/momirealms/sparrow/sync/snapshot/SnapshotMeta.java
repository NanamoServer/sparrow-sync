package net.momirealms.sparrow.sync.snapshot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 快照的元数据.
 * timestamp 在保存请求被接受时分配, 不是落库时刻. <strong>同一玩家的 timestamp 必须严格递增</strong>, 同毫秒的两份快照无法定序;
 *
 * @param id            快照身份, 落库主键
 * @param player        玩家 UUID
 * @param timestamp    保存请求被接受时的毫秒时间戳, 快照新旧的唯一裁决依据
 * @param cause         保存原因
 * @param pinned        是否固定, 固定快照豁免轮转清理
 * @param server        创建快照的服务器名
 * @param mcDataVersion 物品 NBT 对应的 Minecraft data version, 跨版本升级的依据
 */
public record SnapshotMeta(@NotNull UUID id,
                           @NotNull UUID player,
                           long timestamp,
                           @NotNull SaveCause cause,
                           boolean pinned,
                           @NotNull String server,
                           int mcDataVersion) {

    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    @NotNull
    public SnapshotMeta withPinned(boolean pinned) {
        if (this.pinned == pinned) return this;
        return new SnapshotMeta(this.id, this.player, this.timestamp, this.cause, pinned, this.server, this.mcDataVersion);
    }

    public static final class Builder {
        private @Nullable UUID id;
        private @Nullable UUID player;
        private long timestamp;
        private @Nullable SaveCause cause;
        private boolean pinned;
        private String server = "";
        private int mcDataVersion;

        private Builder() {
        }

        /** 采集新快照时无需设置, build 会分配一个新身份; 解码既有快照时必须原样带回. */
        @NotNull
        public Builder id(@NotNull UUID id) {
            this.id = id;
            return this;
        }

        @NotNull
        public Builder player(@NotNull UUID player) {
            this.player = player;
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
            UUID id = this.id != null ? this.id : UUID.randomUUID();
            return new SnapshotMeta(id, this.player, this.timestamp, this.cause, this.pinned, this.server, this.mcDataVersion);
        }
    }
}
