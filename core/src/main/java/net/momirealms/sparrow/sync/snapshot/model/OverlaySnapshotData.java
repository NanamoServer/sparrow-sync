package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class OverlaySnapshotData implements SnapshotData {
    private final SnapshotData source; // 未替换的类型从这里读取, 继续使用原对象的 Tag 缓存和原始数据块
    private final Map<DataKey, Tag> overrides; // 这次及此前 with 调用写入的新值, 构造完成后不再修改此 Map
    private final Set<DataKey> keys; // 所有类型的读取顺序: 原有类型位置不变, 新增类型排在末尾

    OverlaySnapshotData(@NotNull SnapshotData source, @NotNull Map<DataKey, Tag> overrides) {
        this.source = source;
        this.overrides = overrides;
        if (source.keys().containsAll(overrides.keySet())) {
            this.keys = source.keys();
        } else {
            Set<DataKey> keys = new LinkedHashSet<>(source.keys());
            keys.addAll(overrides.keySet());
            this.keys = Collections.unmodifiableSet(keys);
        }
    }

    @Override
    @NotNull
    public Set<DataKey> keys() {
        return this.keys;
    }

    @Override
    @Nullable
    public Tag get(@NotNull DataKey key) {
        Tag value = this.overrides.get(key);
        return value == null ? this.source.get(key) : value;
    }

    @Override
    @Nullable
    public RawBlock raw(@NotNull DataKey key) {
        return this.overrides.containsKey(key) ? null : this.source.raw(key);
    }

    @Override
    @NotNull
    public Map<DataKey, Tag> all() {
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        for (DataKey key : this.keys) {
            values.put(key, this.get(key));
        }
        return Collections.unmodifiableMap(values);
    }

    @Override
    @NotNull
    public SnapshotData with(@NotNull Map<DataKey, Tag> values) {
        if (values.isEmpty()) return this;
        // 将连续替换合并到同一张表, 保留最初来源的原始块读取能力.
        Map<DataKey, Tag> overrides = new LinkedHashMap<>(this.overrides);
        overrides.putAll(values);
        return new OverlaySnapshotData(this.source, overrides);
    }
}
