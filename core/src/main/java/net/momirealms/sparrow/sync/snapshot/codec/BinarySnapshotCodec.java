package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.Compressor;
import net.momirealms.sparrow.sync.exception.FormatException;
import net.momirealms.sparrow.sync.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.codec.upgrade.SnapshotUpgradePipeline;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 二进制形态的快照编解码, 整份快照编为单个自描述字节帧, 用于跨服消息等非落库载体.
 * 帧格式为 magic(2B) + version(1B) + compressorId(1B) + 压缩后的 NBT 值, 单份数据在任何载体中
 * 都能被独立识别版本与压缩算法; {@link #frame} 与 {@link #deframe} 供文档等其他形态封装二进制字段复用.
 */
public final class BinarySnapshotCodec implements SnapshotCodec<byte[]> {
    public static final int DEFAULT_COMPRESS_THRESHOLD = 256;   // 低于该字节数的载荷明文存储

    private static final byte MAGIC_0 = 'S';
    private static final byte MAGIC_1 = 'S';
    private static final int HEADER_LENGTH = 4;
    private static final int MAX_DECODED_SIZE = 16 * 1024 * 1024;   // 单字段解压后的上限

    static final String FIELD_ID = "id";
    static final String FIELD_PLAYER = "player";
    static final String FIELD_TIMESTAMP = "ts";
    static final String FIELD_CAUSE = "cause";
    static final String FIELD_PINNED = "pinned";
    static final String FIELD_SERVER = "server";
    static final String FIELD_MC_DATA = "mcData";
    static final String FIELD_DATA = "data";

    private SparrowSync plugin;
    private CompressorRegistry compressor;
    private final int compressThreshold;

    public BinarySnapshotCodec(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
        this.compressThreshold = DEFAULT_COMPRESS_THRESHOLD;
    }

    public BinarySnapshotCodec(@NotNull CompressorRegistry compressor) {
        this(compressor, DEFAULT_COMPRESS_THRESHOLD);
    }

    // 压缩器取自注册表, 因此帧头 id 必然能被读方查回
    public BinarySnapshotCodec(@NotNull CompressorRegistry compressor, int compressThreshold) {
        this.compressor = compressor;
        this.compressThreshold = compressThreshold;
    }

    /** 加载并验证启动配置选择的压缩器. */
    public void onLoad() throws IOException {
        CompressorRegistry compressor = PluginConfig.synchronization$compression();
        byte[] probe = compressor.compress(new byte[64]);
        compressor.decompress(probe, 0, probe.length, 256);
        this.compressor = compressor;
    }

    @Override
    public byte @NotNull [] encode(@NotNull Snapshot snapshot) throws IOException {
        return this.frame(this.toTagTree(snapshot));
    }

    /**
     * 把单个 NBT 值封装为自带帧头的字节.
     */
    public byte @NotNull [] frame(@NotNull Tag tag) throws IOException {
        byte[] body = NBT.toBytes(tag, false);
        CompressorRegistry used = body.length < this.compressThreshold ? CompressorRegistry.NONE : this.compressor;
        byte[] compressed;
        try {
            compressed = used.compress(body);
        } catch (IOException exception) {
            // 配置的压缩器压不动就换 JVM 内置的 Deflate.
            if (used == CompressorRegistry.DEFLATE) throw exception;
            used = CompressorRegistry.DEFLATE;
            compressed = used.compress(body);
        }
        byte[] out = new byte[HEADER_LENGTH + compressed.length];
        out[0] = MAGIC_0;
        out[1] = MAGIC_1;
        out[2] = (byte) CURRENT_VERSION;
        out[3] = used.id();
        System.arraycopy(compressed, 0, out, HEADER_LENGTH, compressed.length);
        return out;
    }

    @Override
    @NotNull
    public DecodedSnapshot decode(byte @NotNull [] encoded) {
        int format = encoded.length < HEADER_LENGTH ? 0 : encoded[2] & 0xFF;
        try {
            Tag root = this.deframe(encoded);
            if (!(root instanceof CompoundTag compound)) {
                return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, "root tag is not a compound");
            }
            return new DecodedSnapshot.Valid(fromTagTree(SnapshotUpgradePipeline.upgrade(compound, format)));
        } catch (FormatException exception) {
            return new DecodedSnapshot.Invalid(exception.reason(), String.valueOf(exception.getMessage()));
        } catch (Exception exception) {
            return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, String.valueOf(exception.getMessage()));
        }
    }

    /**
     * 从字节帧还原 NBT 值, 解压算法按帧头选择, 与本实例配置的压缩器无关.
     *
     * @throws FormatException 当帧头的魔数, 版本或压缩标识不受支持时
     * @throws IOException                   当数据损坏或解压结果超限时
     */
    @NotNull
    public Tag deframe(byte @NotNull [] bytes) throws IOException {
        if (bytes.length < HEADER_LENGTH) {
            throw new IOException("framed payload too short: " + bytes.length + " bytes");
        }
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) {
            throw new FormatException(InvalidReason.BAD_MAGIC, "unexpected magic bytes");
        }
        // 低版本经升级管线读入, 高版本一律拒绝.
        int version = bytes[2] & 0xFF;
        if (version < 1 || version > CURRENT_VERSION) {
            throw new FormatException(InvalidReason.UNSUPPORTED_FORMAT, "snapshot format " + version + ", supported up to " + CURRENT_VERSION);
        }
        Compressor compressor = CompressorRegistry.byId(bytes[3]);
        if (compressor == null) {
            throw new FormatException(InvalidReason.UNSUPPORTED_COMPRESSION, "unknown compression id " + bytes[3]);
        }
        byte[] body = compressor.decompress(bytes, HEADER_LENGTH, bytes.length - HEADER_LENGTH, MAX_DECODED_SIZE);
        return NBT.readUnnamedTag(new DataInputStream(new ByteArrayInputStream(body)), false);
    }

    private CompoundTag toTagTree(Snapshot snapshot) {
        SnapshotMeta meta = snapshot.meta();
        CompoundTag root = NBT.createCompound();
        root.putUUID(FIELD_PLAYER, meta.player());
        root.putUUID(FIELD_ID, meta.id());
        root.putLong(FIELD_TIMESTAMP, meta.timestamp());
        root.putString(FIELD_CAUSE, meta.cause().name());
        root.putBoolean(FIELD_PINNED, meta.pinned());
        root.putString(FIELD_SERVER, meta.server());
        root.putInt(FIELD_MC_DATA, meta.mcDataVersion());
        CompoundTag data = NBT.createCompound();
        for (Map.Entry<DataKey, Tag> entry : snapshot.data().entrySet()) {
            data.put(entry.getKey().asString(), entry.getValue());
        }
        root.put(FIELD_DATA, data);
        return root;
    }

    // 树形态的读取半侧, JSON 形态解码同构的树后同样经这里还原, 保持一份宽容读取逻辑
    static Snapshot fromTagTree(CompoundTag root) throws IOException {
        UUID player = root.getUUID(FIELD_PLAYER, null);
        if (player == null) throw new IOException("missing player uuid");
        UUID id = root.getUUID(FIELD_ID, null);
        if (id == null) throw new IOException("missing snapshot id");
        SnapshotMeta meta = new SnapshotMeta(
                id,
                player,
                root.getLong(FIELD_TIMESTAMP),
                SaveCause.byName(root.getString(FIELD_CAUSE, "")),
                root.getBoolean(FIELD_PINNED),
                root.getString(FIELD_SERVER, ""),
                root.getInt(FIELD_MC_DATA)
        );
        Map<DataKey, Tag> data = new LinkedHashMap<>();
        CompoundTag values = root.getCompound(FIELD_DATA, null);
        if (values != null) {
            for (Map.Entry<String, Tag> entry : values.entrySet()) {
                data.put(DataKey.parse(entry.getKey()), entry.getValue());
            }
        }
        return new Snapshot(meta, data);
    }
}
