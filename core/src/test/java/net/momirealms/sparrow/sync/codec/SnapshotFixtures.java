package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistration;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 编解码测试共享的注册表与标准快照
final class SnapshotFixtures {
    static final DataKey INVENTORY = DataKey.of("sparrow", "inventory");
    static final DataKey HEALTH = DataKey.of("sparrow", "health");
    static final DataKey UNKNOWN_BLOB = DataKey.of("other", "blob");
    static final DataKey UNKNOWN_DOC = DataKey.of("other", "doc");

    static final UUID PLAYER = UUID.fromString("7f2b3c1d-0a9e-4b8c-9d6f-112233445566");

    private SnapshotFixtures() {
    }

    static DataRegistry registry() {
        DataRegistry registry = new DataRegistry();
        registry.register(DataRegistration.of(INVENTORY, StorageFormat.BINARY, true, Set.of()));
        registry.register(DataRegistration.of(HEALTH, StorageFormat.STRUCTURED));
        return registry;
    }

    static SnapshotMeta meta() {
        return SnapshotMeta.builder()
                .player(PLAYER)
                .version(42L)
                .timestamp(1_756_300_000_000L)
                .cause(SaveCause.DISCONNECT)
                .server("lobby-1")
                .mcDataVersion(4189)
                .build();
    }

    // BINARY 字段超过压缩阈值, STRUCTURED 与未知字段只用 BSON 安全类型, 保证文档形态严格往返
    static Snapshot snapshot() {
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
