package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;

import java.util.Map;
import java.util.UUID;

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
