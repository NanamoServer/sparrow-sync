package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 全部内容已经在内存里的数据体, 供采集侧、JSON 解码与迁移来源使用.
 * 取值不会失败, 迭代顺序跟随传入的 Map.
 */
public final class EagerSnapshotData implements SnapshotData {
    private final Map<DataKey, Tag> values;

    /**
     * @param values 各数据类型的值, <strong>交付后调用方不得再修改它或其中的 Tag</strong>
     */
    public EagerSnapshotData(@NotNull Map<DataKey, Tag> values) {
        this.values = Collections.unmodifiableMap(values);
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
