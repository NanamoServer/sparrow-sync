package net.momirealms.sparrow.sync.snapshot.codec.block;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.model.BlockIndex;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BlockIndexCodec {
    private BlockIndexCodec() {
    }

    /**
     * 读取索引中的块偏移, 保留输入顺序, 不访问数据块.
     * @throws IOException 偏移不是 IntTag 或为负数时
     */
    @NotNull
    public static LinkedHashMap<String, BlockIndex> read(@NotNull CompoundTag index) throws IOException {
        LinkedHashMap<String, BlockIndex> entries = new LinkedHashMap<>();
        // 类型名直接对应 IntTag 偏移
        for (Map.Entry<String, Tag> tagEntry : index.entrySet()) {
            String key = tagEntry.getKey();
            if (!(tagEntry.getValue() instanceof IntTag value)) {
                throw new FormatException(InvalidReason.CORRUPTED, "invalid index entry for " + key);
            }
            BlockIndex entry = new BlockIndex(value.getAsInt());
            if (entry.offset() < 0) {
                throw new FormatException(InvalidReason.CORRUPTED, "negative index offset for " + key);
            }
            entries.put(key, entry);
        }
        return entries;
    }

    /** 按块写入顺序生成未压缩的索引 NBT. */
    @NotNull
    public static CompoundTag write(@NotNull LinkedHashMap<String, BlockIndex> entries) {
        CompoundTag index = NBT.createCompound(new LinkedHashMap<>());
        for (Map.Entry<String, BlockIndex> item : entries.entrySet()) {
            index.putInt(item.getKey(), item.getValue().offset());
        }
        return index;
    }

}
