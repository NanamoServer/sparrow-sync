package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.nbt.ByteArrayTag;
import net.momirealms.sparrow.nbt.ByteTag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.DoubleTag;
import net.momirealms.sparrow.nbt.FloatTag;
import net.momirealms.sparrow.nbt.IntArrayTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.LongArrayTag;
import net.momirealms.sparrow.nbt.LongTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.ShortTag;
import net.momirealms.sparrow.nbt.StringTag;
import net.momirealms.sparrow.nbt.Tag;
import org.bson.Document;
import org.bson.types.Binary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 结构化字段的 NBT 与 BSON 互转. 窄数值类型向 BSON 方向升宽 (byte/short -> int32, float -> double),
 * 读回后类型变宽但数值不变, 读方经 NumericTag 宽容取值不受影响; 人工写入的 Boolean 与 Date 读为 byte(0/1) 与 int64 毫秒, 再次保存后在文档中归一为数值类型.
 * IntArrayTag 与 LongArrayTag 以单键标记文档 {@code {"$i32a": [...]}} / {@code {"$i64a": [...]}}
 * 无损往返, 这两个键名为本转换器保留.
 */
public final class BsonTagConverter {
    private static final String INT_ARRAY_MARKER = "$i32a";
    private static final String LONG_ARRAY_MARKER = "$i64a";

    private BsonTagConverter() {
    }

    /**
     * 把 NBT 值转换为 BSON 可存储的值.
     *
     * @throws IllegalArgumentException 当 Tag 类型没有对应的 BSON 形态时
     */
    @NotNull
    public static Object toBson(@NotNull Tag tag) {
        return switch (tag) {
            case CompoundTag compound -> {
                Document document = new Document();
                for (Map.Entry<String, Tag> entry : compound.entrySet()) {
                    document.append(entry.getKey(), toBson(entry.getValue()));
                }
                yield document;
            }
            case ListTag list -> {
                int size = list.size();
                List<Object> values = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    values.add(toBson(list.get(i)));
                }
                yield values;
            }
            case StringTag string -> string.getAsString();
            case IntTag value -> value.getAsInt();
            case LongTag value -> value.getAsLong();
            case DoubleTag value -> value.getAsDouble();
            case FloatTag value -> (double) value.getAsFloat();
            case ByteTag value -> (int) value.getAsByte();
            case ShortTag value -> (int) value.getAsShort();
            case ByteArrayTag bytes -> new Binary(bytes.value());
            case IntArrayTag ints -> {
                int[] value = ints.value();
                List<Integer> boxed = new ArrayList<>(value.length);
                for (int i = 0; i < value.length; i++) {
                    boxed.add(value[i]);
                }
                yield new Document(INT_ARRAY_MARKER, boxed);
            }
            case LongArrayTag longs -> {
                long[] value = longs.value();
                List<Long> boxed = new ArrayList<>(value.length);
                for (int i = 0; i < value.length; i++) {
                    boxed.add(value[i]);
                }
                yield new Document(LONG_ARRAY_MARKER, boxed);
            }
            default -> throw new IllegalArgumentException("tag type " + tag.getId() + " has no bson form");
        };
    }

    /**
     * 把 BSON 值转换回 NBT 值.
     *
     * @throws IllegalArgumentException 当 BSON 值类型无法转换为 NBT 时
     */
    @NotNull
    public static Tag toTag(@Nullable Object value) {
        return switch (value) {
            case Document document -> {
                Tag arrayTag = fromMarkerDocument(document);
                if (arrayTag != null) yield arrayTag;
                CompoundTag compound = NBT.createCompound();
                for (Map.Entry<String, Object> entry : document.entrySet()) {
                    compound.put(entry.getKey(), toTag(entry.getValue()));
                }
                yield compound;
            }
            case List<?> list -> {
                ListTag tags = NBT.createList();
                int size = list.size();
                for (int i = 0; i < size; i++) {
                    tags.add(toTag(list.get(i)));
                }
                yield tags;
            }
            case String string -> NBT.createString(string);
            case Integer number -> NBT.createInt(number);
            case Long number -> NBT.createLong(number);
            case Double number -> NBT.createDouble(number);
            case Boolean bool -> NBT.createBoolean(bool);
            case Binary binary -> NBT.createByteArray(binary.getData());
            case byte[] bytes -> NBT.createByteArray(bytes);
            case Date date -> NBT.createLong(date.getTime());
            case null -> throw new IllegalArgumentException("null bson value has no nbt form");
            default -> throw new IllegalArgumentException("bson value type " + value.getClass().getName() + " has no nbt form");
        };
    }

    // 识别数组标记文档, 不是标记时返回 null 走普通 compound 转换
    @Nullable
    private static Tag fromMarkerDocument(Document document) {
        if (document.size() != 1) return null;
        Object ints = document.get(INT_ARRAY_MARKER);
        if (ints instanceof List<?> list) {
            int[] value = new int[list.size()];
            for (int i = 0; i < value.length; i++) {
                value[i] = ((Number) list.get(i)).intValue();
            }
            return NBT.createIntArray(value);
        }
        Object longs = document.get(LONG_ARRAY_MARKER);
        if (longs instanceof List<?> list) {
            long[] value = new long[list.size()];
            for (int i = 0; i < value.length; i++) {
                value[i] = ((Number) list.get(i)).longValue();
            }
            return NBT.createLongArray(value);
        }
        return null;
    }
}
