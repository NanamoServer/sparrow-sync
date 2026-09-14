package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

public final class SnapshotMetaCodec {
    static final String FIELD_ID = "id";           // 快照自身的 UUID
    static final String FIELD_PLAYER = "player";   // 玩家 UUID
    static final String FIELD_TIMESTAMP = "ts";    // 逻辑保存时间, 毫秒
    static final String FIELD_CAUSE = "cause";     // 保存原因的枚举名
    static final String FIELD_PINNED = "pinned";   // 固定快照不参与自动清理
    static final String FIELD_SERVER = "server";   // 采集服务器标识
    static final String FIELD_MC_DATA = "mcData";  // 保存服务器的 Minecraft 数据版本

    private SnapshotMetaCodec() {
    }

    // 将快照元数据写成未压缩 NBT.
    @NotNull
    public static byte[] encode(@NotNull SnapshotMeta meta) throws IOException {
        return NBT.toBytes(toCompoundTag(meta), false);
    }

    // 读取独立的元数据 NBT.
    @NotNull
    public static SnapshotMeta decode(byte @NotNull [] bytes) throws IOException {
        return decode(bytes, 0, bytes.length);
    }

    /**
     * 读取指定区间内的元数据 NBT, 根节点必须恰好占满区间.
     * @param offset 区间起点, <strong>须在来源数组内</strong>
     * @param length 区间字节数, <strong>须在来源数组范围内</strong>
     * @throws IOException NBT 无效、有多余字节或缺少身份字段时
     */
    @NotNull
    static SnapshotMeta decode(byte @NotNull [] bytes, int offset, int length) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, offset, length));
        Tag root = NBT.readUnnamedTag(input, false);
        if (!(root instanceof CompoundTag compound) || input.available() != 0) {
            throw new IOException("expected exactly one meta compound");
        }
        return fromCompoundTag(compound);
    }

    @NotNull
    static CompoundTag toCompoundTag(@NotNull SnapshotMeta meta) {
        CompoundTag root = NBT.createCompound();
        root.putUUID(FIELD_PLAYER, meta.player());
        root.putUUID(FIELD_ID, meta.id());
        root.putLong(FIELD_TIMESTAMP, meta.timestamp());
        root.putString(FIELD_CAUSE, meta.cause().name());
        root.putBoolean(FIELD_PINNED, meta.pinned());
        root.putString(FIELD_SERVER, meta.server());
        root.putInt(FIELD_MC_DATA, meta.mcDataVersion());
        return root;
    }

    @NotNull
    static SnapshotMeta fromCompoundTag(@NotNull CompoundTag root) throws IOException {
        // 快照 ID 和玩家 UUID 都必须存在
        UUID player = root.getUUID(FIELD_PLAYER, null);
        if (player == null) throw new IOException("missing player uuid");
        UUID id = root.getUUID(FIELD_ID, null);
        if (id == null) throw new IOException("missing snapshot id");
        return new SnapshotMeta(
                id,
                player,
                root.getLong(FIELD_TIMESTAMP),
                SaveCause.byName(root.getString(FIELD_CAUSE, "")),
                root.getBoolean(FIELD_PINNED),
                root.getString(FIELD_SERVER, ""),
                root.getInt(FIELD_MC_DATA)
        );
    }
}
