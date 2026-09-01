package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.ByteArrayTag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntArrayTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.LongArrayTag;
import net.momirealms.sparrow.nbt.NumericTag;
import net.momirealms.sparrow.nbt.StringTag;
import net.momirealms.sparrow.nbt.Tag;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * 为快照数据体生成与 Map 迭代顺序无关的 128-bit 内容指纹.
 */
public final class SnapshotContentHasher {
    private static final long FIRST_SEED = 0x243F6A8885A308D3L;
    private static final long SECOND_SEED = 0x13198A2E03707344L;

    private static final long ROOT_DOMAIN = 0xA4093822299F31D0L;
    private static final long COMPOUND_DOMAIN = 0x082EFA98EC4E6C89L;
    private static final long DATA_KEY_DOMAIN = 0x452821E638D01377L;
    private static final long NAME_DOMAIN = 0xBE5466CF34E90C6CL;
    private static final long VALUE_DOMAIN = 0xC0AC29B7C97C50DDL;
    private static final long ENTRY_DOMAIN = 0x3F84D5B5B5470917L;
    private static final long ELEMENT_DOMAIN = 0x9216D5D98979FB1BL;
    private static final long SEQUENCE_DOMAIN = 0xD1310BA698DFB5ACL;
    private static final long SCALAR_DOMAIN = 0x2FFD72DBD01ADFB7L;

    private static final long TYPE_MULTIPLIER = 0x9E3779B185EBCA87L;
    private static final long SIZE_MULTIPLIER = 0xC2B2AE3D27D4EB4FL;
    private static final long ORDERED_MULTIPLIER = 0x165667B19E3779F9L;

    private SnapshotContentHasher() {
    }

    /**
     * 计算数据体的内容指纹, 顶层 Map 与嵌套 CompoundTag 的迭代顺序不影响结果.
     */
    @NotNull
    public static ContentHash hash(@NotNull Map<DataKey, Tag> data) {
        return new ContentHash(hashData(data, FIRST_SEED), hashData(data, SECOND_SEED));
    }

    private static long hashData(Map<DataKey, Tag> data, long seed) {
        long sum = 0;
        long xor = 0;
        for (Map.Entry<DataKey, Tag> entry : data.entrySet()) {
            long keyHash = hashDataKey(entry.getKey(), seed ^ NAME_DOMAIN);
            long valueHash = hashTag(entry.getValue(), seed ^ VALUE_DOMAIN);
            long entryHash = mix(seed ^ ENTRY_DOMAIN ^ keyHash ^ Long.rotateLeft(valueHash, 23));
            sum += entryHash;
            xor ^= Long.rotateLeft(entryHash, (int) entryHash);
        }
        return finishUnordered(seed ^ ROOT_DOMAIN, data.size(), sum, xor);
    }

    private static long hashDataKey(DataKey key, long seed) {
        long namespaceHash = hashString(key.namespace(), seed ^ DATA_KEY_DOMAIN);
        long valueHash = hashString(key.value(), seed ^ VALUE_DOMAIN);
        return mix(seed ^ DATA_KEY_DOMAIN ^ namespaceHash ^ Long.rotateLeft(valueHash, 29));
    }

    private static long hashTag(Tag tag, long seed) {
        long typedSeed = seed ^ (tag.getId() * TYPE_MULTIPLIER);
        return switch (tag.getId()) {
            case Tag.TAG_END_ID -> mix(typedSeed);
            case Tag.TAG_BYTE_ID, Tag.TAG_SHORT_ID, Tag.TAG_INT_ID, Tag.TAG_LONG_ID -> hashScalar(((NumericTag) tag).getAsLong(), typedSeed);
            case Tag.TAG_FLOAT_ID -> hashScalar(Float.floatToIntBits(((NumericTag) tag).getAsFloat()), typedSeed);
            case Tag.TAG_DOUBLE_ID -> hashScalar(Double.doubleToLongBits(((NumericTag) tag).getAsDouble()), typedSeed);
            case Tag.TAG_BYTE_ARRAY_ID -> hashByteArray((ByteArrayTag) tag, typedSeed);
            case Tag.TAG_STRING_ID -> hashString(((StringTag) tag).value(), typedSeed);
            case Tag.TAG_LIST_ID -> hashList((ListTag) tag, typedSeed);
            case Tag.TAG_COMPOUND_ID -> hashCompound((CompoundTag) tag, typedSeed);
            case Tag.TAG_INT_ARRAY_ID -> hashIntArray((IntArrayTag) tag, typedSeed);
            case Tag.TAG_LONG_ARRAY_ID -> hashLongArray((LongArrayTag) tag, typedSeed);
            default -> throw new IllegalArgumentException("Unsupported tag type: " + tag.getId());
        };
    }

    private static long hashScalar(long value, long seed) {
        return mix(seed ^ SCALAR_DOMAIN ^ mix(value));
    }

    private static long hashString(String value, long seed) {
        long hash = beginSequence(seed, value.length());
        int length = value.length();
        for (int i = 0; i < length; i++) {
            hash = appendOrdered(hash, value.charAt(i));
        }
        return mix(hash);
    }

    private static long hashList(ListTag list, long seed) {
        int size = list.size();
        long hash = beginSequence(seed, size);
        long elementSeed = seed ^ ELEMENT_DOMAIN;
        for (int i = 0; i < size; i++) {
            hash = appendOrdered(hash, hashTag(list.get(i), elementSeed));
        }
        return mix(hash);
    }

    private static long hashCompound(CompoundTag compound, long seed) {
        long sum = 0;
        long xor = 0;
        for (Map.Entry<String, Tag> entry : compound.entrySet()) {
            long nameHash = hashString(entry.getKey(), seed ^ NAME_DOMAIN);
            long valueHash = hashTag(entry.getValue(), seed ^ VALUE_DOMAIN);
            long entryHash = mix(seed ^ ENTRY_DOMAIN ^ nameHash ^ Long.rotateLeft(valueHash, 23));
            sum += entryHash;
            xor ^= Long.rotateLeft(entryHash, (int) entryHash);
        }
        return finishUnordered(seed ^ COMPOUND_DOMAIN, compound.size(), sum, xor);
    }

    private static long hashByteArray(ByteArrayTag tag, long seed) {
        byte[] values = tag.value();
        long hash = beginSequence(seed, values.length);
        for (int i = 0; i < values.length; i++) {
            hash = appendOrdered(hash, values[i]);
        }
        return mix(hash);
    }

    private static long hashIntArray(IntArrayTag tag, long seed) {
        int[] values = tag.value();
        long hash = beginSequence(seed, values.length);
        for (int i = 0; i < values.length; i++) {
            hash = appendOrdered(hash, values[i]);
        }
        return mix(hash);
    }

    private static long hashLongArray(LongArrayTag tag, long seed) {
        long[] values = tag.value();
        long hash = beginSequence(seed, values.length);
        for (int i = 0; i < values.length; i++) {
            hash = appendOrdered(hash, values[i]);
        }
        return mix(hash);
    }

    private static long beginSequence(long seed, int size) {
        return seed ^ SEQUENCE_DOMAIN ^ (size * SIZE_MULTIPLIER);
    }

    private static long appendOrdered(long hash, long value) {
        return Long.rotateLeft(hash ^ mix(value + ELEMENT_DOMAIN), 27) * ORDERED_MULTIPLIER;
    }

    private static long finishUnordered(long seed, int size, long sum, long xor) {
        long hash = seed ^ (size * SIZE_MULTIPLIER);
        hash ^= mix(sum + SEQUENCE_DOMAIN);
        hash ^= Long.rotateLeft(mix(xor + ELEMENT_DOMAIN), 31);
        return mix(hash);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        return value ^ value >>> 33;
    }

    public record ContentHash(long first, long second) {
    }
}
