package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.block.BlockCodec;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class LazySnapshotData implements SnapshotData {
    private final byte[] frameBytes; // 交付后只读的来源数组, 可包含外层快照头和 Meta
    private final int frameOffset;  // 数据帧在来源数组中的起点
    private final int frameLength;  // 数据帧占用字节数, 供完整复制时限定范围
    private final int blockBase; // 块区在 frameBytes 中的绝对起点
    private final LinkedHashMap<DataKey, BlockIndex> index; // 已校验且按物理次序排列的数据块索引表
    private final Set<DataKey> keys; // 索引键集合的只读视图, 与索引共享顺序和内容
    private final Map<DataKey, Tag> cache = new ConcurrentHashMap<>(); // 仅缓存成功解出的 Tag

    /**
     * 保存已校验的数据帧区间和索引, 各类型在首次访问时读取来源数组.
     *
     * @param frameBytes 来源数组, <strong>构造后调用方不得修改其内容</strong>
     * @param frameOffset 数据帧的起点
     * @param frameLength 数据帧的字节数
     * @param blockBase 第一块在来源数组中的绝对起点
     * @param index 按物理顺序排列的已校验索引
     */
    public LazySnapshotData(byte @NotNull [] frameBytes, int frameOffset, int frameLength, int blockBase, @NotNull LinkedHashMap<String, BlockIndex> index) {
        this.frameBytes = frameBytes;
        this.frameOffset = frameOffset;
        this.frameLength = frameLength;
        this.blockBase = blockBase;
        this.index = new LinkedHashMap<>(index.size());
        for (Map.Entry<String, BlockIndex> entry : index.entrySet()) {
            this.index.put(DataKey.parse(entry.getKey()), entry.getValue());
        }
        this.keys = Collections.unmodifiableSet(this.index.keySet());
    }

    @Override
    @NotNull
    public Set<DataKey> keys() {
        return this.keys;
    }

    public byte @NotNull [] frameBytes() {
        return this.frameBytes;
    }

    public int frameOffset() {
        return this.frameOffset;
    }

    public int frameLength() {
        return this.frameLength;
    }

    @Override
    @Nullable
    public RawBlock raw(@NotNull DataKey key) {
        BlockIndex entry = this.index.get(key);
        if (entry == null) return null;
        return new RawBlock(this.frameBytes, (long) this.blockBase + entry.offset(), entry);
    }

    @Override
    @Nullable
    public Tag get(@NotNull DataKey key) {
        BlockIndex entry = this.index.get(key);
        if (entry == null) return null;
        // MapPipeline 通过引用相等判断是否改动, 成功解块后必须始终复用同一个 Tag.
        return this.cache.computeIfAbsent(key, ignored -> {
            try {
                return BlockCodec.decode(this.frameBytes, this.blockBase, key.asString(), entry);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
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

    // 成功还原并缓存的类型数量
    int decodedBlockCount() {
        return this.cache.size();
    }
}
