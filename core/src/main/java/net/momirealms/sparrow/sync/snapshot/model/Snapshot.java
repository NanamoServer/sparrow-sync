package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * 一份玩家数据快照, 由快照元数据与各类型的完整数据块组成.
 * 数据块同时携带类型数据与元信息, 未注册类型在正式加载时按元信息决定是否保留.
 */
public final class Snapshot {
    private final SnapshotMeta meta;
    private final SnapshotData content;

    public Snapshot(@NotNull SnapshotMeta meta, @NotNull Map<DataKey, Tag> data) {
        this(meta, EagerSnapshotData.fromTags(data));
    }

    public Snapshot(@NotNull SnapshotMeta meta, @NotNull SnapshotData content) {
        this.meta = meta;
        this.content = content;
    }

    @NotNull
    public SnapshotMeta meta() {
        return this.meta;
    }

    @NotNull
    public SnapshotData content() {
        return this.content;
    }

    /**
     * 数据体中全部类型的标识, <strong>不触发任何解析</strong>.
     */
    @NotNull
    public Set<DataKey> keys() {
        return this.content.keys();
    }

    /**
     * 取一个类型的值, 数据体中没有这个类型时返回 null.
     */
    @Nullable
    public Tag data(@NotNull DataKey key) {
        return this.content.get(key);
    }

    /**
     * 全部类型的 Tag 投影, <strong>会还原数据体中的每一个类型</strong>.
     */
    @NotNull
    public Map<DataKey, Tag> allData() {
        return this.content.all();
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (this == object) return true;
        if (!(object instanceof Snapshot other)) return false;
        return this.meta.equals(other.meta) && this.content.blocks().equals(other.content.blocks());
    }

    @Override
    public int hashCode() {
        return 31 * this.meta.hashCode() + this.content.blocks().hashCode();
    }

    @Override
    public String toString() {
        return "Snapshot[meta=" + this.meta + ", data=" + this.content.blocks() + "]";
    }
}
