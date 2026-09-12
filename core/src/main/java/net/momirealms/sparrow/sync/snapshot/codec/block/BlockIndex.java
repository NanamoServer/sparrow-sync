package net.momirealms.sparrow.sync.snapshot.codec.block;

import net.momirealms.sparrow.nbt.ByteTag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据类型到块位置的索引, 负责 NBT 字段与条目之间的转换.
 * 每个条目保存 o/l/n/c, 偏移和长度以字节为单位.
 */
public final class BlockIndex {
    private BlockIndex() {
    }

    /**
     * 读取各类型的块位置, 长度与压缩方式, 保持传入索引的迭代顺序.
     *
     * @param index 索引 compoundTag
     * @return 保持输入迭代顺序的条目表
     * @throws IOException 当条目缺少字段, 字段类型错误或长度为负时
     */
    @NotNull
    public static LinkedHashMap<String, Entry> read(@NotNull CompoundTag index) throws IOException {
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        // 逐项检查真实 NBT 类型, 缺字段不能被 getInt/getByte 的缺省零值掩盖.
        for (Map.Entry<String, Tag> tagEntry : index.entrySet()) {
            String key = tagEntry.getKey();
            if (!(tagEntry.getValue() instanceof CompoundTag value)
                    || !(value.get("o") instanceof IntTag) || !(value.get("l") instanceof IntTag)
                    || !(value.get("n") instanceof IntTag) || !(value.get("c") instanceof ByteTag))
            {
                throw new FormatException(InvalidReason.CORRUPTED, "invalid index entry for " + key);
            }
            Entry entry = new Entry(value.getInt("o"), value.getInt("l"), value.getInt("n"), value.getByte("c"));
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
    public static CompoundTag write(@NotNull LinkedHashMap<String, Entry> entries) {
        CompoundTag index = NBT.createCompound(new LinkedHashMap<>());
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            CompoundTag value = NBT.createCompound();
            value.putInt("o", entry.offset());
            value.putInt("l", entry.length());
            value.putInt("n", entry.rawLength());
            value.putByte("c", entry.compressorId());
            index.put(item.getKey(), value);
        }
        return index;
    }

    /**
     * 单个类型的块描述, 长度均不包含容器头和索引段.
     *
     * @param offset 块头相对 blockBase 的偏移, 首块为零
     * @param length 压缩后 payload 的字节数, 不含 9 字节块头
     * @param rawLength 解压后单键 compound 的字节数
     * @param compressorId 压缩注册表标识, 必须与块头一致
     */
    public record Entry(int offset, int length, int rawLength, byte compressorId) {
    }
}
