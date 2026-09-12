package net.momirealms.sparrow.sync.snapshot.codec.block;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.model.BlockIndex;
import net.momirealms.sparrow.sync.snapshot.model.BlockMeta;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BlockIndexCodec {
    private BlockIndexCodec() {
    }

    /**
     * 读取各类型的块位置, 长度与元信息, 保持传入索引的迭代顺序.
     *
     * @param index 索引 compoundTag
     * @return 保持输入迭代顺序的条目表
     * @throws IOException 当条目缺少字段, 字段类型错误或长度为负时
     */
    @NotNull
    public static LinkedHashMap<String, BlockIndex> read(@NotNull CompoundTag index) throws IOException {
        LinkedHashMap<String, BlockIndex> entries = new LinkedHashMap<>();
        // 逐项检查真实 NBT 类型, 缺字段不能被 getInt 的缺省零值掩盖.
        for (Map.Entry<String, Tag> tagEntry : index.entrySet()) {
            String key = tagEntry.getKey();
            if (!(tagEntry.getValue() instanceof CompoundTag value)
                    || !(value.get("o") instanceof IntTag)
                    || !(value.get("l") instanceof IntTag)
                    || !(value.get("n") instanceof IntTag))
            {
                throw new FormatException(InvalidReason.CORRUPTED, "invalid index entry for " + key);
            }
            BlockMeta meta;
            try {
                Tag storedMeta = value.get("meta");
                if (storedMeta != null && !(storedMeta instanceof CompoundTag)) {
                    throw new IOException("block meta must be a compound");
                }
                meta = BlockMetaCodec.read(storedMeta == null ? NBT.createCompound() : (CompoundTag) storedMeta);
            } catch (FormatException exception) {
                throw new FormatException(exception.reason(), "invalid index metadata for " + key + ": " + exception.getMessage());
            } catch (IOException exception) {
                throw new FormatException(InvalidReason.CORRUPTED, "invalid index metadata for " + key + ": " + exception.getMessage());
            }
            BlockIndex entry = new BlockIndex(value.getInt("o"), value.getInt("l"), value.getInt("n"), meta);
            if (entry.offset() < 0 || entry.length() < 0 || entry.rawLength() < 0) {
                throw new FormatException(InvalidReason.CORRUPTED, "negative index length or offset for " + key);
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
            BlockIndex entry = item.getValue();
            CompoundTag value = NBT.createCompound();
            value.put("meta", BlockMetaCodec.write(entry.meta()));
            value.putInt("o", entry.offset());
            value.putInt("l", entry.length());
            value.putInt("n", entry.rawLength());
            index.put(item.getKey(), value);
        }
        return index;
    }

}
