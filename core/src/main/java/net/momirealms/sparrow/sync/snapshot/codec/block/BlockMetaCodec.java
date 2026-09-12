package net.momirealms.sparrow.sync.snapshot.codec.block;

import net.momirealms.sparrow.nbt.ByteTag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.upgrade.BlockMetaUpgradePipeline;
import net.momirealms.sparrow.sync.snapshot.model.BlockMeta;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class BlockMetaCodec {
    private static final String VERSION = "version"; // Sparrow Sync 维护的元信息格式版本
    private static final String KEEP_UNKNOWN = "keepUnknown"; // 未注册类型是否保留, 缺失时为 true

    private BlockMetaCodec() {
    }

    /**
     * 读取 NBT 元信息并升级到当前布局, 当前布局用 byte 0/1 表示布尔值.
     *
     * @param tag 索引内独立的 meta compound
     * @return 当前版本的块元信息
     * @throws IOException 当版本不受支持, 字段类型错误或升级失败时
     */
    @NotNull
    public static BlockMeta read(@NotNull CompoundTag tag) throws IOException {
        CompoundTag current = BlockMetaUpgradePipeline.upgrade(tag, version(tag));
        Tag keep = current.get(KEEP_UNKNOWN);
        if (keep == null) return BlockMeta.DEFAULT;
        if (!(keep instanceof ByteTag) || (current.getByte(KEEP_UNKNOWN) != 0 && current.getByte(KEEP_UNKNOWN) != 1)) {
            throw new IOException("block meta keepUnknown must be a boolean byte");
        }
        return current.getByte(KEEP_UNKNOWN) == 0 ? BlockMeta.DISCARD_UNKNOWN : BlockMeta.DEFAULT;
    }

    /**
     * 读取存储中的元信息版本, 缺少版本的初始布局按 1 处理.
     *
     * @param tag 原始元信息树
     * @return 存储版本, 尚未执行升级
     * @throws IOException 当版本字段类型错误时
     */
    public static int version(@NotNull CompoundTag tag) throws IOException {
        Tag version = tag.get(VERSION);
        if (version == null) return 1;
        if (!(version instanceof IntTag)) throw new IOException("block metadata version must be an int");
        return tag.getInt(VERSION);
    }

    /**
     * 写出完整块元信息, 用作索引中的 meta 或完整快照树中的块 meta.
     *
     * @param meta 要保存的元信息
     * @return 含版本号和未知处理开关的独立 compound
     */
    @NotNull
    public static CompoundTag write(@NotNull BlockMeta meta) {
        CompoundTag tag = NBT.createCompound();
        tag.putInt(VERSION, meta.version());
        tag.putBoolean(KEEP_UNKNOWN, meta.keepUnknown());
        return tag;
    }

    /**
     * 读取 JSON 元信息并先执行其布局升级, 之后要求 keepUnknown 为 JSON 布尔值.
     *
     * @param document 单个类型的 meta 对象
     * @return 当前版本的块元信息
     * @throws IOException 当版本不受支持, 字段类型错误或升级失败时
     */
    @NotNull
    public static BlockMeta read(@NotNull Document document) throws IOException {
        int version = 1;
        if (document.containsKey(VERSION)) {
            Object value = document.get(VERSION);
            if (!(value instanceof Integer || value instanceof Long)
                    || ((Number) value).longValue() < Integer.MIN_VALUE || ((Number) value).longValue() > Integer.MAX_VALUE) {
                throw new IOException("block metadata version must be an int");
            }
            version = ((Number) value).intValue();
        }
        Document current = BlockMetaUpgradePipeline.upgrade(document, version);
        if (!current.containsKey(KEEP_UNKNOWN)) return BlockMeta.DEFAULT;
        if (!(current.get(KEEP_UNKNOWN) instanceof Boolean keepUnknown)) {
            throw new IOException("block meta keepUnknown must be a boolean");
        }
        return keepUnknown ? BlockMeta.DEFAULT : BlockMeta.DISCARD_UNKNOWN;
    }

    /**
     * 写出 JSON 块内的完整元信息对象.
     *
     * @param meta 要保存的元信息
     * @return 含版本号和未知处理开关的 JSON 对象
     */
    @NotNull
    public static Document toJson(@NotNull BlockMeta meta) {
        return new Document(VERSION, meta.version()).append(KEEP_UNKNOWN, meta.keepUnknown());
    }
}
