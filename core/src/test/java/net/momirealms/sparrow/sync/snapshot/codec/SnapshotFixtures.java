package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;

import java.util.Map;
import java.util.UUID;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

// 编解码测试共享的标准快照
public final class SnapshotFixtures {
    static final DataKey INVENTORY = DataKey.of("sparrow", "inventory");
    static final DataKey HEALTH = DataKey.of("sparrow", "health");
    static final DataKey UNKNOWN_BLOB = DataKey.of("other", "blob");
    public static final DataKey UNKNOWN_DOC = DataKey.of("other", "doc");

    static final UUID PLAYER = UUID.fromString("7f2b3c1d-0a9e-4b8c-9d6f-112233445566");
    static final UUID SNAPSHOT_ID = UUID.fromString("11112222-3333-4444-5555-666677778888");

    private SnapshotFixtures() {
    }

    /**
     * 根据头部的段长定位第一块, 供测试修改块头.
     *
     * @param frame 合法分块帧
     * @return 块区的绝对起点
     */
    public static int blockBase(byte[] frame) {
        ByteBuffer header = ByteBuffer.wrap(frame);
        return 14 + Short.toUnsignedInt(header.getShort(4)) + header.getInt(6);
    }

    /**
     * 构造 CRC 正确但索引根为整数的数据帧, 检查载体是否拒绝非法 NBT 结构.
     *
     * @return 仅索引结构非法的分块帧
     * @throws IOException 当测试 NBT 序列化失败时
     */
    public static byte[] nonCompoundIndexFrame() throws IOException {
        byte[] index = NBT.toBytes(NBT.createInt(3), false);
        CRC32 crc = new CRC32();
        crc.update(index);
        return ByteBuffer.allocate(14 + index.length).put((byte) 'S').put((byte) 'S')
                .put((byte) SnapshotCodec.CURRENT_VERSION).put((byte) 0).putShort((short) 0)
                .putInt(index.length).putInt((int) crc.getValue()).put(index).array();
    }

    public static SnapshotMeta meta() {
        return SnapshotMeta.builder()
                .player(PLAYER)
                .id(SNAPSHOT_ID)
                .timestamp(1_756_300_000_000L)
                .cause(SaveCause.DISCONNECT)
                .server("lobby-1")
                .mcDataVersion(4189)
                .build();
    }

    // 覆盖嵌套物品数据, 普通数值和外部类型的数据, 总大小超过压缩阈值
    public static Snapshot snapshot() {
        return new Snapshot(meta(), Map.of(
                INVENTORY, inventoryTag(),
                HEALTH, healthTag(),
                UNKNOWN_BLOB, NBT.createByteArray(new byte[]{9, 8, 7, 6, 5}),
                UNKNOWN_DOC, unknownDocTag()
        ));
    }

    static CompoundTag inventoryTag() {
        CompoundTag inventory = NBT.createCompound();
        inventory.putInt("heldSlot", 3);
        byte[] blob = new byte[600];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i * 31);
        }
        inventory.putByteArray("payload", blob);
        ListTag items = NBT.createList();
        for (int slot = 0; slot < 4; slot++) {
            CompoundTag item = NBT.createCompound();
            item.putInt("slot", slot);
            item.putString("id", "minecraft:stone");
            item.putShort("count", (short) 64);
            items.add(item);
        }
        inventory.put("items", items);
        return inventory;
    }

    static CompoundTag healthTag() {
        CompoundTag health = NBT.createCompound();
        health.putDouble("value", 19.5);
        health.putDouble("scale", 20.0);
        health.putInt("food", 18);
        health.putLong("since", 123_456_789L);
        health.putString("mode", "SURVIVAL");
        return health;
    }

    private static Tag unknownDocTag() {
        CompoundTag doc = NBT.createCompound();
        doc.putString("origin", "third-party");
        doc.putInt("level", 7);
        return doc;
    }
}
