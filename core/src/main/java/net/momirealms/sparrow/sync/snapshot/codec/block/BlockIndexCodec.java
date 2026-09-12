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
     * 读取各类型的块偏移, 保持传入索引的迭代顺序, 不访问块头或 payload.
     *
     * @param index 索引 compoundTag
     * @return 保持输入迭代顺序的条目表
     * @throws IOException 当偏移不是 IntTag 或为负数时
     */
    @NotNull
    public static LinkedHashMap<String, BlockIndex> read(@NotNull CompoundTag index) throws IOException {
        LinkedHashMap<String, BlockIndex> entries = new LinkedHashMap<>();
        // 每个类型名直接对应 IntTag, 旧开发格式中的 compound 条目不再接受.
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

    /**
     * 将条目写为未压缩索引的 NBT 树, 保持块的写入顺序.
     *
     * @param entries 按物理写入次序收集的条目
     * @return 使用 LinkedHashMap 保存顶层顺序的索引树
     */
    @NotNull
    public static CompoundTag write(@NotNull LinkedHashMap<String, BlockIndex> entries) {
        CompoundTag index = NBT.createCompound(new LinkedHashMap<>());
        for (Map.Entry<String, BlockIndex> item : entries.entrySet()) {
            index.putInt(item.getKey(), item.getValue().offset());
        }
        return index;
    }

}
