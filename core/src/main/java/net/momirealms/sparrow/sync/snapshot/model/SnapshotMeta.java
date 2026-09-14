package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 快照元数据. timestamp 在接收保存请求时分配, <strong>同一玩家的时间戳必须严格递增</strong>.
 * @param pinned 固定的快照不参与自动清理
 * @param timestamp Unix 毫秒时间, 用于判断快照新旧
 * @param mcDataVersion 保存时的 Minecraft 数据版本, 用于展示和诊断
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

        /** 新快照由 build 分配 ID, 读取已有快照时保留原 ID. */
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
            UUID id = this.id != null ? this.id : UUIDUtils.timeOrdered();
            return new SnapshotMeta(id, this.player, this.timestamp, this.cause, this.pinned, this.server, this.mcDataVersion);
        }
    }
}
