package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * 一份玩家数据快照, 由快照元数据与各类型的数据组成.
 * 类型数据可以是已解码的 Tag, 也可以保留为按需解码的原始块字节.
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
     * 全部类型的 Tag, <strong>会还原数据体中的每一个类型</strong>.
     */
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
