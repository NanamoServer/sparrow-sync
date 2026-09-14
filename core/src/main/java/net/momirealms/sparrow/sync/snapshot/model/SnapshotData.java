package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockCodec;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public interface SnapshotData {

    /** 返回全部类型标识, <strong>不触发解码</strong>. */
    @NotNull
    Set<DataKey> keys();

    /**
     * 读取指定类型, 不存在时返回 null; 原始块首次读取时校验、解压并解析, 后续复用 Tag.
     * @return <strong>返回的 Tag 只读</strong>, 修改值请使用 with
     * @throws UncheckedIOException 数据校验、解压或 NBT 解析失败时
     */
    @Nullable
    Tag get(@NotNull DataKey key);

    /**
     * 读取全部类型并返回只读 Map, 任一类型读取失败都会抛出异常.
     * @return <strong>Map 及其中的 Tag 均只读</strong>
     * @throws UncheckedIOException 数据校验、解压或 NBT 解析失败时
     */
    @NotNull
    Map<DataKey, Tag> all();

    /**
     * 返回指定类型的原始块区间, 不校验块内数据, 供编码器直接复制.
     * @return 引用原数组的块, 类型不存在或只有 Tag 时返回 null
     */
    @Nullable
    default RawBlock raw(@NotNull DataKey key) {
        return null;
    }

    /**
     * 从块头读取压缩前的 NBT 字节数, 包含外层 CompoundTag 和类型名, 不解压或校验内容.
     * @return 原始字节数, 类型不存在或没有原始块时返回 -1
     * @throws UncheckedIOException 块头或区间无效时
     */
    default int rawLength(@NotNull DataKey key) {
        RawBlock block = this.raw(key);
        if (block == null) return -1;
        try {
            return BlockCodec.readHeader(block, key.asString()).rawLength();
        } catch (FormatException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 返回替换或追加指定类型后的新对象, 原对象不变, 其他类型继续复用原数据块.
     * 新增类型排在末尾, 此操作不读取其他类型.
     * @param value <strong>直接保留引用, 传入后不得修改</strong>
     */
    @NotNull
    default SnapshotData with(@NotNull DataKey key, @NotNull Tag value) {
        return this.with(Map.of(key, value));
    }

    /**
     * 返回批量替换后的新对象, 原对象不变; 新增类型按 Map 顺序追加, 空 Map 返回当前对象.
     * @param values Map 会复制, <strong>其中的 Tag 传入后不得修改</strong>
     */
    @NotNull
    default SnapshotData with(@NotNull Map<DataKey, Tag> values) {
        return values.isEmpty() ? this : new OverlaySnapshotData(this, new LinkedHashMap<>(values));
    }

    /**
     * 按原顺序筛选类型, 不读取 Tag 或校验数据块.
     * 非空视图仍引用来源, 长期保存前应复制选中的块.
     * @return 选中类型的视图, 无匹配时返回共享空数据
     */
    @NotNull
    default SnapshotData select(@NotNull Predicate<DataKey> selected) {
        Set<DataKey> keys = new LinkedHashSet<>();
        for (DataKey key : this.keys()) {
            if (selected.test(key)) keys.add(key);
        }
        return keys.isEmpty() ? EagerSnapshotData.EMPTY : new SubsetSnapshotData(this, keys);
    }
}
