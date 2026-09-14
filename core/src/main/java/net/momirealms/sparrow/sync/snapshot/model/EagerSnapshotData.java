package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 直接保存已解码的 Tag, 读取顺序与传入 Map 一致.
 * <strong>交付后不得修改来源 Map 或其中的 Tag</strong>.
 */
public final class EagerSnapshotData implements SnapshotData {
    public static final EagerSnapshotData EMPTY = new EagerSnapshotData(Map.of());

    private final Map<DataKey, Tag> values; // 只读视图, all 返回同一对象

    /**
     * 直接保存已解码的数据.
     * @param values <strong>传入后不得修改 Map 或其中的 Tag</strong>
     */
    public EagerSnapshotData(@NotNull Map<DataKey, Tag> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * 直接保存传入数据, 空 Map 返回 EMPTY.
     * @param values <strong>传入后不得修改 Map 或其中的 Tag</strong>
     */
    @NotNull
    public static EagerSnapshotData fromTags(@NotNull Map<DataKey, Tag> values) {
        return values.isEmpty() ? EMPTY : new EagerSnapshotData(values);
    }

    @Override
    @NotNull
    public Set<DataKey> keys() {
        return this.values.keySet();
    }

    @Override
    @Nullable
    public Tag get(@NotNull DataKey key) {
        return this.values.get(key);
    }

    @Override
    @NotNull
    public Map<DataKey, Tag> all() {
        return this.values;
    }
}
