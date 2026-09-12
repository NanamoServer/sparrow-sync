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
 * 全部内容已经在内存里的数据体, 每个类型同时持有块元信息和 Tag.
 * 取值不会失败, 迭代顺序跟随传入的 Map.
 */
public final class EagerSnapshotData implements SnapshotData {
    public static final EagerSnapshotData EMPTY = new EagerSnapshotData(Map.of()); // 空数据体共享实例

    private Map<DataKey, Tag> tags; // all 首次请求时生成只读投影视图
    private final Map<DataKey, SnapshotBlock> values; // 已展开的块, 元信息与 Tag 一起持有

    /**
     * @param values 各数据类型的完整块, <strong>交付后调用方不得再修改它或其中的 Tag</strong>
     */
    public EagerSnapshotData(@NotNull Map<DataKey, SnapshotBlock> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * 将没有附加声明的外部 Tag 转为默认保留未知数据的块, 用于迁移等首次构造数据的入口.
     *
     * @param values 仅含类型数据的来源, <strong>其中的 Tag 交付后不得修改</strong>
     * @return 元信息均为默认值的独立数据体
     */
    @NotNull
    public static EagerSnapshotData fromTags(@NotNull Map<DataKey, Tag> values) {
        if (values.isEmpty()) return EMPTY;
        Map<DataKey, SnapshotBlock> blocks = new LinkedHashMap<>();
        for (Map.Entry<DataKey, Tag> entry : values.entrySet()) {
            blocks.put(entry.getKey(), new SnapshotBlock(BlockMeta.DEFAULT, entry.getValue()));
        }
        return new EagerSnapshotData(blocks);
    }

    @Override
    @NotNull
    public BlockMeta meta(@NotNull DataKey key) {
        SnapshotBlock block = this.values.get(key);
        return block == null ? BlockMeta.DEFAULT : block.meta();
    }

    @Override
    @Nullable
    public SnapshotBlock block(@NotNull DataKey key) {
        return this.values.get(key);
    }

    @Override
    @NotNull
    public Map<DataKey, SnapshotBlock> blocks() {
        return this.values;
    }

    @Override
    @NotNull
    public Set<DataKey> keys() {
        return this.values.keySet();
    }

    @Override
    @Nullable
    public Tag get(@NotNull DataKey key) {
        SnapshotBlock block = this.values.get(key);
        return block == null ? null : block.data();
    }

    @Override
    @NotNull
    public synchronized Map<DataKey, Tag> all() {
        if (this.tags != null) return this.tags;
        Map<DataKey, Tag> tags = new LinkedHashMap<>();
        for (Map.Entry<DataKey, SnapshotBlock> entry : this.values.entrySet()) {
            tags.put(entry.getKey(), entry.getValue().data());
        }
        this.tags = Collections.unmodifiableMap(tags);
        return this.tags;
    }
}
