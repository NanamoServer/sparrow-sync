package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 全部内容已保存为 Tag 的数据体, 取值无需解码, 迭代顺序跟随传入的 Map.
 * 对外提供只读视图, 调用方交付后不得再修改来源 Map 或其中的 Tag.
 */
public final class EagerSnapshotData implements SnapshotData {
    public static final EagerSnapshotData EMPTY = new EagerSnapshotData(Map.of()); // 空数据体共享实例

    private final Map<DataKey, Tag> values; // 类型与 Tag 的只读视图, all 每次返回同一对象

    /**
     * 保存已经解码的类型数据, 读取时直接返回其中的 Tag.
     *
     * @param values 类型与 Tag, <strong>交付后调用方不得再修改 Map 或其中的 Tag</strong>
     */
    public EagerSnapshotData(@NotNull Map<DataKey, Tag> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * 从采集或转换结果创建数据体.
     *
     * @param values 类型与 Tag, <strong>交付后调用方不得修改 Map 或其中的 Tag</strong>
     * @return 直接持有这些 Tag 的数据体, 空输入返回 EMPTY
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
