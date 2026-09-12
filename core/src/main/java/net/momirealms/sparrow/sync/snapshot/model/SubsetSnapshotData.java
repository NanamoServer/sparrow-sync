package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 只暴露来源中的部分数据类型, 取值和原始块读取继续由来源负责.
 * 视图保留来源引用, 适合临时筛选和编码; 长期保留前应将选中的块复制到独立帧.
 */
final class SubsetSnapshotData implements SnapshotData {
    private final SnapshotData source; // 提供选中类型的 Tag 缓存和原始块
    private final Set<DataKey> keys; // 按来源顺序收集的类型, 构造后只读

    SubsetSnapshotData(@NotNull SnapshotData source, @NotNull Set<DataKey> keys) {
        this.source = source;
        this.keys = Collections.unmodifiableSet(keys);
    }

    @Override
    @NotNull
    public BlockMeta meta(@NotNull DataKey key) {
        return this.keys.contains(key) ? this.source.meta(key) : BlockMeta.DEFAULT;
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
