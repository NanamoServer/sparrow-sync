package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/** 玩家数据快照, 包含元数据和各类型的数据; 类型内容可按需解码. */
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

    /** 返回全部类型标识, <strong>不触发解码</strong>. */
    @NotNull
    public Set<DataKey> keys() {
        return this.content.keys();
    }

    /** 读取指定类型, 不存在时返回 null. */
    @Nullable
    public Tag data(@NotNull DataKey key) {
        return this.content.get(key);
    }

    /** 读取全部类型的 Tag, <strong>会解码所有尚未读取的数据</strong>. */
    @NotNull
    public Map<DataKey, Tag> allData() {
        return this.content.all();
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (this == object) return true;
        if (!(object instanceof Snapshot other)) return false;
        return this.meta.equals(other.meta) && this.content.all().equals(other.content.all());
    }

    @Override
    public int hashCode() {
        return 31 * this.meta.hashCode() + this.content.all().hashCode();
    }

    @Override
    public String toString() {
        return "Snapshot[meta=" + this.meta + ", data=" + this.content.all() + "]";
    }
}
