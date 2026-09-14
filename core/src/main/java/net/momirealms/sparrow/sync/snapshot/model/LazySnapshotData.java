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
    private final byte[] frameBytes; // 只读来源数组, 可能包含快照头和元数据
    private final int frameOffset;  // 数据帧在来源数组中的起点
    private final int frameLength;  // 数据帧占用字节数, 供完整复制时限定范围
    private final Map<DataKey, RawBlock> blocks; // 按索引顺序记录块区间, 暂不读取块头
    private final Set<DataKey> keys; // 只读类型集合, 顺序与块区间一致
    private final Map<DataKey, Tag> cache = new ConcurrentHashMap<>(); // 仅缓存成功解出的 Tag

    /**
     * 保存已校验的帧区间和索引, 各类型首次访问时再读取.
     * @param frameBytes <strong>构造后不得修改来源数组</strong>
     * @param index 按物理顺序排列的已校验索引
     */
    public LazySnapshotData(byte @NotNull [] frameBytes, int frameOffset, int frameLength, int blockBase, @NotNull LinkedHashMap<String, BlockIndex> index) {
        this.frameBytes = frameBytes;
        this.frameOffset = frameOffset;
        this.frameLength = frameLength;
        this.blocks = new LinkedHashMap<>(index.size());
        DataKey previousKey = null;
        long previousOffset = 0;
        // 相邻索引确定块边界, 最后一块以数据帧末尾为界
        for (Map.Entry<String, BlockIndex> entry : index.entrySet()) {
            long blockOffset = (long) blockBase + entry.getValue().offset();
            if (previousKey != null) {
                this.blocks.put(previousKey, new RawBlock(frameBytes, previousOffset, blockOffset));
            }
            previousKey = DataKey.parse(entry.getKey());
            previousOffset = blockOffset;
        }
        if (previousKey != null) {
            this.blocks.put(previousKey, new RawBlock(frameBytes, previousOffset, (long) frameOffset + frameLength));
        }
        this.keys = Collections.unmodifiableSet(this.blocks.keySet());
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
        return this.blocks.get(key);
    }

    @Override
    @Nullable
    public Tag get(@NotNull DataKey key) {
        RawBlock block = this.blocks.get(key);
        if (block == null) return null;
        // 地图处理通过引用判断数据是否变化, 解码成功后始终复用同一个 Tag
        return this.cache.computeIfAbsent(key, ignored -> {
            try {
                return BlockCodec.decode(block, key.asString());
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

    // 已解码并缓存的类型数量
    int decodedBlockCount() {
        return this.cache.size();
    }
}
