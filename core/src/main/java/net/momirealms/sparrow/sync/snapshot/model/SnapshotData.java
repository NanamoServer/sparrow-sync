package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public interface SnapshotData {

    /**
     * 读取可跨载体传递的块元信息, <strong>不触发解块</strong>.
     *
     * @param key 要查询的类型
     * @return 原块的元信息, 类型不存在时返回默认元信息
     */
    @NotNull
    default BlockMeta meta(@NotNull DataKey key) {
        RawBlock block = this.raw(key);
        return block == null ? BlockMeta.DEFAULT : block.index().meta();
    }

    /**
     * 读取一个完整的逻辑块, 同时携带元信息和类型数据.
     *
     * @param key 要读取的类型
     * @return 完整块, 类型不存在时返回 null
     * @throws UncheckedIOException 当原始块无法还原为 Tag 时
     */
    @Nullable
    default SnapshotBlock block(@NotNull DataKey key) {
        Tag value = this.get(key);
        return value == null ? null : new SnapshotBlock(this.meta(key), value);
    }

    /**
     * 展开全部逻辑块供需要完整内容的转换使用, 保留每个块的元信息.
     *
     * @return 按来源顺序排列的只读块集合
     * @throws UncheckedIOException 当任一原始块无法还原为 Tag 时
     */
    @NotNull
    default Map<DataKey, SnapshotBlock> blocks() {
        Map<DataKey, SnapshotBlock> blocks = new LinkedHashMap<>();
        for (DataKey key : this.keys()) {
            blocks.put(key, this.block(key));
        }
        return Collections.unmodifiableMap(blocks);
    }

    /**
     * 数据体中全部类型的标识, <strong>不触发任何解析</strong>.
     */
    @NotNull
    Set<DataKey> keys();

    /**
     * 读取指定类型的 NBT 值, 类型不存在时返回 null.
     * 若该类型仍保存为原始字节, 首次读取会校验数据块, 解压并解析 NBT, 后续读取复用已解析的 Tag.
     *
     * @param key 要读取的数据类型标识
     * @return 该类型的 Tag 或 null; <strong>调用方不得修改返回的 Tag</strong>, 替换值应调用 with
     * @throws UncheckedIOException 当该类型的数据块校验, 解压或 NBT 解析失败时
     */
    @Nullable
    Tag get(@NotNull DataKey key);

    /**
     * 读取全部类型的 NBT 值并返回只读 Map.
     * 尚未读取的数据块会在此时校验, 解压并解析; 任一类型读取失败都会使此方法抛出异常.
     *
     * @return 包含全部类型及其 Tag 的只读 Map, <strong>其中的 Tag 也不得修改</strong>
     * @throws UncheckedIOException 当任一类型的数据块校验, 解压或 NBT 解析失败时
     */
    @NotNull
    Map<DataKey, Tag> all();

    /**
     * 取得指定类型的数据块在原字节数组中的位置及索引信息, 供编码器直接复制.
     * 此方法不检查块内的 CRC, 不解压或解析 NBT; 能取得原始块并不表示其中的数据可正常读取.
     *
     * @param key 要查找的数据类型标识
     * @return 引用原帧字节的 RawBlock; 类型不存在, 或该类型仅保存为 Tag 而没有原始字节时返回 null
     */
    @Nullable
    default RawBlock raw(@NotNull DataKey key) {
        return null;
    }

    /**
     * 读取索引中记录的该类型 NBT 在压缩前的字节数, 无需读取数据块.
     * 长度包含包裹该类型值的 CompoundTag 及类型名, 对应序列化后的 {类型名: 值}, 可用于详情页显示大小.
     *
     * @param key 要查询大小的数据类型标识
     * @return 未压缩的 NBT 字节数; 类型不存在或没有原始块索引时返回 -1
     */
    default int rawLength(@NotNull DataKey key) {
        RawBlock block = this.raw(key);
        return block == null ? -1 : block.index().rawLength();
    }

    /**
     * 创建一个新的 SnapshotData, 将指定类型的值设为 value, 原对象保持不变.
     * 类型已存在时替换其值, 不存在时追加到类型列表末尾; 其他类型仍从原对象读取.
     * 调用本方法不会读取或解析其他类型的 NBT, 保存时仍可直接复制它们的原始数据块.
     *
     * @param key 要替换或追加的数据类型标识
     * @param value 新的 NBT 值, 直接保存其引用, <strong>传入后调用方不得修改此 Tag</strong>
     * @return 读取 key 时返回 value 的新对象, 原对象及先前 with 返回的对象均不受影响
     */
    @NotNull
    default SnapshotData with(@NotNull DataKey key, @NotNull Tag value) {
        return this.with(Map.of(key, value));
    }

    /**
     * 将一组新值覆盖到当前数据体上, 已有类型保留原元信息, 新类型使用默认元信息.
     * 原有类型位置不变, 新增类型按传入 Map 的顺序追加; 空 Map 返回当前对象.
     *
     * @param values 要替换或追加的值, Map 会被复制, <strong>其中的 Tag 交付后不得修改</strong>
     * @return 合并后的只读数据体, 当前对象保持不变
     */
    @NotNull
    default SnapshotData with(@NotNull Map<DataKey, Tag> values) {
        if (values.isEmpty()) return this;
        Map<DataKey, SnapshotBlock> blocks = new LinkedHashMap<>();
        for (Map.Entry<DataKey, Tag> entry : values.entrySet()) {
            blocks.put(entry.getKey(), new SnapshotBlock(this.meta(entry.getKey()), entry.getValue()));
        }
        return this.withBlocks(blocks);
    }

    /**
     * 用完整逻辑块覆盖类型数据及其元信息, 适用于本次采集和载体转换后的结果.
     * 未覆盖类型继续提供原始块, 已有键位置不变, 新键按输入顺序追加.
     *
     * @param blocks 新的完整块, Map 会被复制, <strong>块内的 Tag 交付后不得修改</strong>
     * @return 覆盖后的只读数据体, 空输入返回当前对象
     */
    @NotNull
    default SnapshotData withBlocks(@NotNull Map<DataKey, SnapshotBlock> blocks) {
        return blocks.isEmpty() ? this : new OverlaySnapshotData(this, new LinkedHashMap<>(blocks));
    }

    /**
     * 按来源顺序选择部分类型, 筛选期间不读取 Tag 或校验数据块.
     * 返回的非空视图仍引用来源; 长期持有前应将选中的块复制到独立帧.
     *
     * @param selected 按类型标识判断是否保留
     * @return 只暴露选中类型的视图; 没有匹配项时返回共享空数据体
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
