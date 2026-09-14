package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class SubsetSnapshotData implements SnapshotData {
    private final SnapshotData source; // 选中类型的值和原始块均从来源读取
    private final Set<DataKey> keys; // 选中类型按来源顺序排列, 构造后只读

    SubsetSnapshotData(@NotNull SnapshotData source, @NotNull Set<DataKey> keys) {
        this.source = source;
        this.keys = Collections.unmodifiableSet(keys);
    }

    @Override
    @NotNull
    public Set<DataKey> keys() {
        return this.keys;
    }

    @Override
    @Nullable
    public Tag get(@NotNull DataKey key) {
        return this.keys.contains(key) ? this.source.get(key) : null;
    }

    @Override
    @Nullable
    public RawBlock raw(@NotNull DataKey key) {
        return this.keys.contains(key) ? this.source.raw(key) : null;
    }

    @Override
    @NotNull
    public Map<DataKey, Tag> all() {
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        for (DataKey key : this.keys) {
            values.put(key, this.source.get(key));
        }
        return Collections.unmodifiableMap(values);
    }
}
