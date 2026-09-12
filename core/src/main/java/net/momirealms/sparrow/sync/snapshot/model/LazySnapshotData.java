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
    private final byte[] frame; // 交付后只读的原始帧, 供各类型首次取块访问
    private final boolean upgradedMeta; // 元信息已经升级时, 原帧索引须在保存时重建
    private final int blockBase; // 块区在 frame 中的绝对起点
    private final LinkedHashMap<DataKey, BlockIndex> index; // 已校验且按物理次序排列的数据块索引表
    private final Set<DataKey> keys; // 索引键集合的只读视图, 与索引共享顺序和内容
    private final Map<DataKey, Tag> cache = new ConcurrentHashMap<>(); // 仅缓存成功解出的 Tag

    public LazySnapshotData(byte @NotNull [] frame, int blockBase, @NotNull LinkedHashMap<String, BlockIndex> index, boolean upgradedMeta) {
        this.frame = frame;
        this.upgradedMeta = upgradedMeta;
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

    public boolean upgradedMeta() {
        return this.upgradedMeta;
    }

    // 返回构造时传入的完整帧字节, 供编码器直接复制已有的索引和数据块.
    @NotNull
    public byte[] encodedFrame() {
        return this.frame;
    }

    @Override
    @Nullable
    public RawBlock raw(@NotNull DataKey key) {
        BlockIndex entry = this.index.get(key);
        if (entry == null) return null;
        return new RawBlock(this.frame, (long) this.blockBase + entry.offset(), entry);
    }

    @Override
    @Nullable
    public Tag get(@NotNull DataKey key) {
        BlockIndex entry = this.index.get(key);
        if (entry == null) return null;
        // MapPipeline 通过引用相等判断是否改动, 成功解块后必须始终复用同一个 Tag.
        return this.cache.computeIfAbsent(key, ignored -> {
            try {
                return BlockCodec.decode(this.frame, this.blockBase, key.asString(), entry);
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
