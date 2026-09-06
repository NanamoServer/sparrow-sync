package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.ByteArrayTag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.StringTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

// 保存一次采集得到的地图持久内容, 供异步编码、内容比较和跨服传输使用.
@ApiStatus.Internal
public final class MapData {
    public static final int PIXEL_COUNT = 128 * 128;
    private final int dataVersion;
    private final CompoundTag tag;

    public MapData(int dataVersion, @NotNull CompoundTag tag) {
        if (dataVersion <= 0 || !(tag.get("dimension") instanceof StringTag dimension) || dimension.getAsString().isBlank()
                || !(tag.get("colors") instanceof ByteArrayTag colors) || colors.getAsByteArray().length != PIXEL_COUNT
                || tag.getByte("scale", (byte) 0) < 0 || tag.getByte("scale", (byte) 0) > 4) {
            throw new IllegalArgumentException("invalid native map data");
        }
        this.dataVersion = dataVersion;
        this.tag = tag.deepClone();
        // 展示框由接收服重建, Bukkit 世界 UUID 随接收维度重新生成.
        this.tag.remove("frames");
        this.tag.remove("UUIDMost");
        this.tag.remove("UUIDLeast");
    }

    public int dataVersion() {
        return this.dataVersion;
    }

    @NotNull
    public CompoundTag getTag() {
        return this.tag.deepClone();
    }

    @NotNull
    public byte[] encode() throws IOException {
        return NBT.toBytes(this.tag);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MapData data && this.dataVersion == data.dataVersion && this.tag.equals(data.tag);
    }

    @Override
    public int hashCode() {
        return 31 * this.dataVersion + this.tag.hashCode();
    }
}
