package net.momirealms.sparrow.sync.util;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class UUIDUtils {
    private UUIDUtils() {
    }

    /**
     * 生成时间有序的 UUIDv7, 前 48 位是生成时刻的毫秒时间戳.
     * <p>作为 BINARY(16) 主键时插入集中在索引末尾.
     *
     * @return 一个新的 UUIDv7
     */
    @NotNull
    public static UUID timeOrdered() {
        long millis = System.currentTimeMillis();
        long head = ThreadLocalRandom.current().nextLong();
        long tail = ThreadLocalRandom.current().nextLong();
        // 高 48 位放毫秒时间戳, 接着 4 位版本号 7, 再 12 位随机段.
        long most = (millis & 0xFFFFFFFFFFFFL) << 16 | 0x7000L | (head & 0x0FFFL);
        // 最高 2 位固定为 10 作为变体位, 其余 62 位随机.
        long least = tail & 0x3FFFFFFFFFFFFFFFL | 0x8000000000000000L;
        return new UUID(most, least);
    }

    public static byte @NotNull [] toBytes(@NotNull UUID uuid) {
        // ByteBuffer 默认使用大端序, 高 64 位在前、低 64 位在后.
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    @NotNull
    public static UUID fromBytes(byte @NotNull [] bytes) {
        if (bytes.length != 16) throw new IllegalArgumentException("UUID requires 16 bytes, got " + bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
